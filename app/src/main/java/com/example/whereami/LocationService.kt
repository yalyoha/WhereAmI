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
 * Foreground location service — адаптивный интервал по дистанции, работает
 * ТОЛЬКО через системный android.location.LocationManager (без Google Play
 * Services). Такое решение:
 *   - работает на любых Android 10+ (Pixel, Samsung, Xiaomi, Huawei/HarmonyOS…),
 *   - убирает риск, что Huawei-система «сворачивает» приложения из-за наличия
 *     GMS-классов в APK,
 *   - использует FUSED_PROVIDER (API 31+, system-side) когда доступен —
 *     это по сути тот же fused что и в Play Services, но встроенный в AOSP.
 *
 * Интервал (см. adjustIntervalForDistance): от 1 сек (быстрое движение)
 * до 10 мин (стоим). Halving при dist>20м, doubling при 10 подряд <20м.
 *
 * Provider выбирается по интервалу:
 *   ≤ 30 сек (движение) → GPS_PROVIDER (высокая точность);
 *   иначе (стоянка)     → FUSED_PROVIDER / NETWORK / GPS в fallback.
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

    // Адаптивный интервал по расстоянию.
    // Стартуем с BOOTSTRAP (1 сек) — чтобы первый fix пришёл быстро и
    // клиент сразу появился online на веб-карте. Дальше adaptive
    // логика поднимает интервал вплоть до INTERVAL_DEFAULT_MS (10 мин)
    // если клиент стоит на месте.
    private var intervalMs: Long = INTERVAL_MIN_MS
    private var closeStreak: Int = 0
    private var lastLat: Double? = null
    private var lastLon: Double? = null

    // § ANDROID-TODO задача 2: проверяем обновления каждым 5-м апдейтом.
    private var sendCounter: Int = 0

    // LocationListener — SAM (functional interface), достаточно onLocationChanged.
    private val listener = LocationListener { loc -> onNewFix(loc) }

    private fun onNewFix(loc: Location) {
        adjustIntervalForDistance(loc)
        scope.launch { handleLocation(loc) }
    }

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(LOCATION_SERVICE) as? LocationManager
        settings = SettingsRepository(this)
        queue    = UploadQueue(this)
        api      = ApiClient(settings.serverUrl)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
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
                Log.w(TAG, "No location permission — stopping")
                stopSelf()
                return START_NOT_STICKY
            }
            if (!settings.isConfigured()) {
                Log.w(TAG, "Не сконфигурирован токен/slug — stopping")
                stopSelf()
                return START_NOT_STICKY
            }
            if (lm == null) {
                Log.w(TAG, "LocationManager недоступен — stopping")
                stopSelf()
                return START_NOT_STICKY
            }

            // API client пересоздаём — serverUrl мог поменяться в Settings.
            api = ApiClient(settings.serverUrl)
            applyRequest(intervalMs)
            return START_STICKY
        } catch (t: Throwable) {
            saveError("onStartCommand", t)
            stopSelf()
            return START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopAll()
        scope.cancel()
        super.onDestroy()
    }

    /** Сохраняет строку ошибки в SettingsRepository — MainActivity.onResume её покажет в AlertDialog. */
    private fun saveError(where: String, t: Throwable) {
        val msg = "$where: ${t.javaClass.simpleName}: ${t.message ?: "(no message)"}"
        Log.w(TAG, msg)
        try { settings.lastError = msg } catch (_: Throwable) {}
    }

    private fun startInForeground(notif: Notification) {
        startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }

    private fun applyRequest(interval: Long) {
        val locationManager = lm ?: run {
            saveError("applyRequest", IllegalStateException("LocationManager is null"))
            stopSelf(); return
        }
        try {
            // Provider выбираем по «активности» клиента:
            //   interval ≤ 30 сек → движение → GPS_PROVIDER (нужна точность);
            //   иначе               → FUSED_PROVIDER (API 31+, system-side) / NETWORK / GPS.
            val provider = when {
                interval <= 30_000L -> LocationManager.GPS_PROVIDER
                Build.VERSION.SDK_INT >= 31 &&
                    locationManager.getProviders(true).contains(LocationManager.FUSED_PROVIDER)
                    -> LocationManager.FUSED_PROVIDER
                locationManager.getProviders(true).contains(LocationManager.NETWORK_PROVIDER)
                    -> LocationManager.NETWORK_PROVIDER
                else -> LocationManager.GPS_PROVIDER
            }
            // Пере-подписка: сначала снимаем прежнюю, потом ставим новую с новым интервалом.
            locationManager.removeUpdates(listener)
            locationManager.requestLocationUpdates(
                provider,
                interval,      // minTime — приблизительный интервал между fix'ами
                0f,            // minDistance — 0, наша адаптивная логика решает сама
                listener,
                Looper.getMainLooper()
            )
            Log.d(TAG, "requestLocationUpdates interval=${interval}ms provider=$provider")
        } catch (t: Throwable) {
            saveError("requestLocationUpdates", t)
            stopSelf()
        }
    }

    /**
     * Правит [intervalMs] по дистанции до предыдущей точки:
     *   dist > MOVE_THRESHOLD_M                → halve, closeStreak = 0
     *   dist ≤ MOVE_THRESHOLD_M                → closeStreak++;
     *     если closeStreak ≥ CALM_STREAK_TO_SLOW → double, closeStreak = 0
     * Границы: [INTERVAL_MIN_MS, INTERVAL_DEFAULT_MS].
     */
    private fun adjustIntervalForDistance(loc: Location) {
        val prevLat = lastLat
        val prevLon = lastLon
        lastLat = loc.latitude
        lastLon = loc.longitude
        if (prevLat == null || prevLon == null) return   // первая точка, база

        val dist = Haversine.distanceMeters(prevLat, prevLon, loc.latitude, loc.longitude)
        val old = intervalMs
        if (dist > MOVE_THRESHOLD_M) {
            intervalMs = (intervalMs / 2).coerceAtLeast(INTERVAL_MIN_MS)
            closeStreak = 0
        } else {
            closeStreak++
            if (closeStreak >= CALM_STREAK_TO_SLOW) {
                intervalMs = (intervalMs * 2).coerceAtMost(INTERVAL_DEFAULT_MS)
                closeStreak = 0
            }
        }
        if (intervalMs != old) {
            Log.d(TAG, "interval ${old}ms → ${intervalMs}ms  (dist=${dist.toInt()}m, streak=$closeStreak)")
            applyRequest(intervalMs)
        }
    }

    private fun handleLocation(loc: Location) {
        val token = settings.token
        if (token.length != 32) {
            Log.w(TAG, "Токен пуст/битый — stop")
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

        when (val res = api.update(token, upload)) {
            is ApiClient.Result.Ok -> {
                Log.d(TAG, "send ok thinned=${res.value.thinned} id=${res.value.positionId}")
            }
            is ApiClient.Result.Err -> {
                when (res.code) {
                    401 -> {
                        Log.w(TAG, "401 invalid_token — stop service")
                        postAuthErrorNotif()
                        stopAllAndStop()
                    }
                    400 -> {
                        Log.w(TAG, "400 ${res.message} — drop")
                    }
                    else -> {
                        Log.w(TAG, "send err code=${res.code} msg=${res.message} → в очередь")
                        queue.enqueue(upload)
                        // Сеть могла починиться — попросим WorkManager попробовать прямо сейчас.
                        RetryWorker.kickOnce(applicationContext)
                    }
                }
            }
        }

        sendCounter++
        if (sendCounter % UPDATE_CHECK_EVERY_N == 0) {
            Log.d(TAG, "trigger update check on send #$sendCounter")
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
                NotificationManager.IMPORTANCE_MIN  // без иконки в статусбаре, без звука/вибро,
                                                    // видно только когда полностью раскрыл шторку
            ).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                description = "Фоновая передача координат — без звука и в свёрнутом виде"
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
        // _v2 — чтобы Android создал свежий канал с IMPORTANCE_MIN. Каналы immutable
        // после createNotificationChannel(), у уже установленного приложения старый
        // канал с IMPORTANCE_LOW остался бы, поэтому меняем ID.
        private const val CHANNEL_ID     = "whereami_location_v2"
        private const val CHANNEL_AUTH_ID= "whereami_auth"
        private const val NOTIF_ID       = 1001
        private const val NOTIF_AUTH_ID  = 1002
        const val ACTION_STOP            = "com.example.whereami.STOP"

        // Адаптивный по дистанции интервал (см. adjustIntervalForDistance):
        private const val INTERVAL_DEFAULT_MS  = 600_000L
        private const val INTERVAL_MIN_MS      = 1_000L
        private const val MOVE_THRESHOLD_M     = 20.0
        private const val CALM_STREAK_TO_SLOW  = 10

        /** Проверять обновление каждый N-й POST-локации. § ANDROID-TODO 2. */
        private const val UPDATE_CHECK_EVERY_N = 5

        fun start(context: Context) {
            val i = Intent(context, LocationService::class.java)
            try {
                ContextCompat.startForegroundService(context, i)
            } catch (t: Throwable) {
                val msg = "LocationService.start: ${t.javaClass.simpleName}: ${t.message ?: "(no message)"}"
                Log.w("LocationService", msg)
                try { SettingsRepository(context).lastError = msg } catch (_: Throwable) {}
            }
        }

        fun stop(context: Context) {
            val i = Intent(context, LocationService::class.java).setAction(ACTION_STOP)
            try { context.startService(i) } catch (_: Throwable) {}
        }
    }
}
