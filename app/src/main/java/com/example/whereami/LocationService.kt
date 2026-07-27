package com.example.whereami

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground location service — фиксированный интервал 60 сек, работает
 * ТОЛЬКО через системный android.location.LocationManager (без Google Play
 * Services). Такое решение:
 *   - работает на любых Android 10+ (Pixel, Samsung, Xiaomi, Huawei/HarmonyOS…),
 *   - убирает риск, что Huawei-система «сворачивает» приложения из-за наличия
 *     GMS-классов в APK,
 *   - использует FUSED_PROVIDER (API 31+, system-side) когда доступен —
 *     это по сути тот же fused что и в Play Services, но встроенный в AOSP.
 *
 * Почему фиксированный 60 сек, а не адаптив:
 *   - сервер считает клиента offline через 180 сек молчания;
 *   - 60 сек гарантированно покрывает online-порог с трёхкратным запасом;
 *   - сервер сам прореживает дубликаты (thinned) — экономия трафика на его стороне;
 *   - адаптив 1сек↔10мин раньше уводил интервал за 180 сек при простое, и
 *     клиент "исчезал" с карты партнёра, даже пока FGS был жив.
 *
 * Provider — FUSED (API 31+) если доступен, иначе NETWORK, иначе GPS.
 * FUSED делает sensor-fusion внутри AOSP и выдаёт точки без активного GPS —
 * заметно экономит батарею на минутном интервале по сравнению с чистым GPS.
 *
 * На каждую точку: читаем battery, POST в /api/location.
 * 200 ok / thinned → лог. 401 → стоп + notif. 400 → drop.
 * 5xx / сеть → UploadQueue + RetryWorker.
 */
class LocationService : Service() {

    private var lm: LocationManager? = null
    private lateinit var settings: SettingsRepository
    private lateinit var queue: UploadQueue
    private lateinit var api: ApiClient

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // § ANDROID-TODO задача 2: проверяем обновления каждым 5-м апдейтом.
    private var sendCounter: Int = 0

    // LocationListener — SAM (functional interface), достаточно onLocationChanged.
    private val listener = LocationListener { loc -> scope.launch { handleLocation(loc) } }

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(LOCATION_SERVICE) as? LocationManager
        settings = SettingsRepository(this)
        queue    = UploadQueue(this)
        api      = ApiClient(settings.serverUrl)
        ensureChannel()
        FileLogger.i(TAG, "onCreate pid=${android.os.Process.myPid()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: "(null)"
        FileLogger.i(TAG, "onStartCommand action=$action flags=$flags startId=$startId " +
                "sharing=${settings.sharingEnabled} keepBg=${settings.keepInBackground}")
        if (intent?.action == ACTION_STOP) {
            FileLogger.i(TAG, "ACTION_STOP → stopSelf")
            stopAll()
            stopSelf()
            return START_NOT_STICKY
        }

        // Foreground заводим до запроса локации. На Android 14+ startForeground
        // с типом LOCATION может выбросить ForegroundServiceStartNotAllowedException
        // (если стартовали из ограниченного контекста) — ловим.
        try {
            startInForeground(buildNotification(getString(R.string.notif_text_running)))
        } catch (t: Throwable) {
            saveError("startForeground", t)
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            if (!hasLocationPermission()) {
                FileLogger.w(TAG, "no location permission — stopping")
                stopSelf()
                return START_NOT_STICKY
            }
            if (!settings.isConfigured()) {
                FileLogger.w(TAG, "не сконфигурирован токен/slug — stopping")
                stopSelf()
                return START_NOT_STICKY
            }
            if (lm == null) {
                FileLogger.w(TAG, "LocationManager недоступен — stopping")
                stopSelf()
                return START_NOT_STICKY
            }

            // API client пересоздаём — serverUrl мог поменяться в Settings.
            api = ApiClient(settings.serverUrl)
            applyRequest()
            return START_STICKY
        } catch (t: Throwable) {
            saveError("onStartCommand", t)
            stopSelf()
            return START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        FileLogger.i(TAG, "onDestroy — сервис останавливается " +
                "(система, ACTION_STOP или stopSelf — контекст ищите в предыдущих строках)")
        stopAll()
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Юзер смахнул приложение из свежих. FGS должен продолжать жить — но некоторые
        // OEM (Huawei, Xiaomi) на этом убивают процесс. Логируем — чтобы понять,
        // предшествовал ли этот момент неожиданному onDestroy.
        FileLogger.w(TAG, "onTaskRemoved — приложение смахнули из recents")
        super.onTaskRemoved(rootIntent)
    }

    override fun onTrimMemory(level: Int) {
        // TRIM_MEMORY_COMPLETE / _RUNNING_CRITICAL часто предшествует убийству процесса
        // системой (background app killer, low memory). Ловим, чтобы связать причину.
        FileLogger.w(TAG, "onTrimMemory level=$level")
        super.onTrimMemory(level)
    }

    override fun onLowMemory() {
        FileLogger.w(TAG, "onLowMemory")
        super.onLowMemory()
    }

    /** Сохраняет строку ошибки в SettingsRepository — MainActivity.onResume её покажет в AlertDialog. */
    private fun saveError(where: String, t: Throwable) {
        val msg = "$where: ${t.javaClass.simpleName}: ${t.message ?: "(no message)"}"
        FileLogger.w(TAG, msg)
        try { settings.lastError = msg } catch (_: Throwable) {}
    }

    private fun startInForeground(notif: Notification) {
        startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }

    private fun applyRequest() {
        val locationManager = lm ?: run {
            saveError("applyRequest", IllegalStateException("LocationManager is null"))
            stopSelf(); return
        }
        try {
            // FUSED (API 31+, system-side) → NETWORK → GPS fallback.
            // FUSED делает sensor-fusion внутри AOSP: одинаково хорошо в помещении
            // (по WiFi/сотам) и на улице (по GPS), без активного удержания GPS-приёмника.
            val provider = when {
                Build.VERSION.SDK_INT >= 31 &&
                    locationManager.getProviders(true).contains(LocationManager.FUSED_PROVIDER)
                    -> LocationManager.FUSED_PROVIDER
                locationManager.getProviders(true).contains(LocationManager.NETWORK_PROVIDER)
                    -> LocationManager.NETWORK_PROVIDER
                else -> LocationManager.GPS_PROVIDER
            }
            locationManager.removeUpdates(listener)
            locationManager.requestLocationUpdates(
                provider,
                INTERVAL_MS,   // minTime — 60 сек
                0f,            // minDistance — 0, интервал определяет частоту
                listener,
                Looper.getMainLooper()
            )
            FileLogger.i(TAG, "requestLocationUpdates interval=${INTERVAL_MS}ms provider=$provider")
        } catch (t: Throwable) {
            saveError("requestLocationUpdates", t)
            stopSelf()
        }
    }

    private fun handleLocation(loc: Location) {
        val token = settings.token
        if (token.length != 32) {
            FileLogger.w(TAG, "токен пуст/битый — stop")
            postAuthErrorNotif()
            stopAllAndStop()
            return
        }

        val upload = LocationUpload(
            lat           = loc.latitude,
            lon           = loc.longitude,
            accuracyM     = if (loc.hasAccuracy()) loc.accuracy else null,
            speedMps      = if (loc.hasSpeed())    loc.speed    else null,
            battery       = readBatteryLevel(),
            recordedAtSec = if (loc.time > 0) loc.time / 1000 else System.currentTimeMillis() / 1000
        )

        val accStr = upload.accuracyM?.let { "%.0f".format(it) } ?: "—"
        val spdStr = upload.speedMps?.let { "%.1f".format(it) } ?: "—"
        val batStr = upload.battery?.toString() ?: "—"
        val coordsStr = "%.6f,%.6f".format(upload.lat, upload.lon)

        when (val res = api.update(token, upload)) {
            is ApiClient.Result.Ok -> {
                FileLogger.i(TAG, "send ok $coordsStr ±${accStr}м ${spdStr}м/с bat=$batStr " +
                        "thinned=${res.value.thinned} id=${res.value.positionId}")
            }
            is ApiClient.Result.Err -> {
                when (res.code) {
                    401 -> {
                        FileLogger.w(TAG, "401 invalid_token — stop service")
                        postAuthErrorNotif()
                        stopAllAndStop()
                    }
                    400 -> {
                        FileLogger.w(TAG, "400 ${res.message} — drop $coordsStr")
                    }
                    else -> {
                        FileLogger.w(TAG, "send err code=${res.code} msg=${res.message} " +
                                "$coordsStr → в очередь")
                        queue.enqueue(upload)
                        // Сеть могла починиться — попросим WorkManager попробовать прямо сейчас.
                        RetryWorker.kickOnce(applicationContext)
                    }
                }
            }
        }

        sendCounter++
        if (sendCounter % UPDATE_CHECK_EVERY_N == 0) {
            FileLogger.d(TAG, "trigger update check on send #$sendCounter")
            UpdateWorker.checkNow(applicationContext)
        }
    }

    private fun readBatteryLevel(): Int? {
        val bm = getSystemService(BATTERY_SERVICE) as? BatteryManager ?: return null
        val v = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (v in 0..100) v else null
    }

    private fun stopAll() {
        try { lm?.removeUpdates(listener) } catch (_: Throwable) {}
    }
    private fun stopAllAndStop() {
        stopAll()
        stopSelf()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW  // иконка в статусбаре видна, но без звука/вибро.
                                                    // MIN нельзя: OEM'ы (Xiaomi/Huawei) убивают FGS
                                                    // с MIN-каналом заметно охотнее.
            ).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                description = "Фоновая передача координат — иконка в статусбаре, без звука"
            })
        }
        if (nm.getNotificationChannel(CHANNEL_AUTH_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_AUTH_ID,
                getString(R.string.notif_channel_auth),
                NotificationManager.IMPORTANCE_HIGH
            ))
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, AuthActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, LocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notif_action_stop), stopIntent)
            // DEFERRED — нотификация не мигает первые 10 сек, меньше беспокойства пользователя.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun postAuthErrorNotif() {
        val openIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, AuthActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_AUTH_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_auth_error))
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_AUTH_ID, notif)
        settings.sharingEnabled = false
    }

    companion object {
        private const val TAG            = "LocationService"
        // _v3 — каналы immutable после createNotificationChannel(), новый ID
        // нужен чтобы Android создал канал с IMPORTANCE_LOW (было _v2 с MIN).
        // Причина смены: IMPORTANCE_MIN сигналит OEM battery managers'ам
        // "работа не user-visible" — Xiaomi/Huawei такие FGS убивают агрессивнее.
        // LOW = тихо (без звука/вибро), но иконка в статусбаре видна.
        private const val CHANNEL_ID     = "whereami_location_v3"
        private const val CHANNEL_AUTH_ID= "whereami_auth"
        private const val NOTIF_ID       = 1001
        private const val NOTIF_AUTH_ID  = 1002
        const val ACTION_STOP            = "com.example.whereami.STOP"

        // Фиксированный интервал 60 сек. Сервер считает клиента offline через 180 сек —
        // 60 покрывает трёхкратным запасом. Сервер сам прореживает (thinned).
        private const val INTERVAL_MS = 60_000L

        /** Проверять обновление каждый N-й POST-локации. § ANDROID-TODO 2. */
        private const val UPDATE_CHECK_EVERY_N = 5

        fun start(context: Context) {
            val i = Intent(context, LocationService::class.java)
            try {
                ContextCompat.startForegroundService(context, i)
                FileLogger.i("LocationService", "start() → startForegroundService dispatched")
            } catch (t: Throwable) {
                val msg = "LocationService.start: ${t.javaClass.simpleName}: ${t.message ?: "(no message)"}"
                FileLogger.w("LocationService", msg)
                try { SettingsRepository(context).lastError = msg } catch (_: Throwable) {}
            }
        }

        fun stop(context: Context) {
            val i = Intent(context, LocationService::class.java).setAction(ACTION_STOP)
            FileLogger.i("LocationService", "stop() → dispatched ACTION_STOP")
            try { context.startService(i) } catch (_: Throwable) {}
        }
    }
}
