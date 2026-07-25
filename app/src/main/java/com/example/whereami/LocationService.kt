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
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground location service по контракту ANDROID-TASK § 3.1–3.3:
 *   - FusedLocationProviderClient с адаптивным режимом:
 *       SLOW (по умолчанию): interval 60s, PRIORITY_BALANCED_POWER_ACCURACY — пешеход/стоянка.
 *       FAST (speed ≥ 3 м/с): interval 15s, PRIORITY_HIGH_ACCURACY — велосипед/авто.
 *       Возврат в SLOW при скорости <1.5 м/с (гистерезис).
 *   - На каждую точку: читаем battery, POST в /api/location
 *   - 200 ok / 200 thinned → лог, обновили lastSendAt
 *   - 400 → лог, дроп (повторять бесполезно)
 *   - 401 → стоп сервиса + уведомление "токен невалиден"
 *   - 5xx / сеть → enqueue в UploadQueue + kick RetryWorker
 */
class LocationService : Service() {

    // fused — nullable: на устройствах без Google Play Services
    // (Huawei EMUI/HarmonyOS, RE-ROMы) getFusedLocationProviderClient() бросает.
    // В таком случае сервис аккуратно останавливается, приложение не крашится.
    private var fused: FusedLocationProviderClient? = null
    private lateinit var settings: SettingsRepository
    private lateinit var queue: UploadQueue
    private lateinit var api: ApiClient

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Адаптивный интервал по расстоянию:
    //   - штатный INTERVAL_DEFAULT_MS = 10 мин (стоим на месте),
    //   - каждый апдейт с dist > MOVE_THRESHOLD_M ускоряем в 2 раза (пол-интервала),
    //     пока не упрёмся в INTERVAL_MIN_MS = 1 сек,
    //   - CALM_STREAK_TO_SLOW подряд «близких» апдейтов → замедляем в 2 раза,
    //     пока не вернёмся к INTERVAL_DEFAULT_MS.
    private var intervalMs: Long = INTERVAL_DEFAULT_MS
    private var closeStreak: Int = 0
    private var lastLat: Double? = null
    private var lastLon: Double? = null

    // § ANDROID-TODO задача 2: проверяем обновления каждым 5-м апдейтом.
    // Отсчёт от старта сервиса — переживёт логаут/старт-стоп циклы,
    // т. к. сервис пересоздаётся вместе со счётчиком.
    private var sendCounter: Int = 0

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            adjustIntervalForDistance(loc)
            scope.launch { handleLocation(loc) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fused    = try {
            LocationServices.getFusedLocationProviderClient(this)
        } catch (t: Throwable) {
            Log.w(TAG, "GMS FusedLocationProviderClient unavailable: ${t.message}")
            null
        }
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

        // Foreground заводим до запроса локации (требование Android 14+).
        startInForeground(buildNotification(getString(R.string.notif_text_running)))

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
        if (fused == null) {
            Log.w(TAG, "GMS FusedLocationProviderClient недоступен (нет Google Play Services) — stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        // API client пересоздаём — serverUrl мог поменяться в Settings.
        api = ApiClient(settings.serverUrl)
        requestUpdates()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopAll()
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(notif: Notification) {
        startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }

    private fun requestUpdates() {
        applyRequest(intervalMs)
    }

    private fun applyRequest(interval: Long) {
        val client = fused ?: run {
            Log.w(TAG, "applyRequest: fused null — stop")
            stopSelf(); return
        }
        // Ниже 30 сек — считаем, что клиент движется: включаем HIGH_ACCURACY.
        // На больших интервалах BALANCED экономит батарею.
        val priority = if (interval <= 30_000L) Priority.PRIORITY_HIGH_ACCURACY
                       else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        val req = LocationRequest.Builder(interval)
            .setMinUpdateIntervalMillis(interval)
            .setMaxUpdateDelayMillis(interval)
            .setPriority(priority)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            // requestLocationUpdates с тем же callback'ом просто перезапишет конфиг (не плодит подписки).
            client.requestLocationUpdates(req, callback, Looper.getMainLooper())
            Log.d(TAG, "locationRequest interval=${interval}ms priority=$priority")
        } catch (t: Throwable) {
            Log.w(TAG, "requestLocationUpdates failed: ${t.message}")
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
        try { fused?.removeLocationUpdates(callback) } catch (_: Throwable) {}
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
        //   INTERVAL_DEFAULT_MS  — штатный, стоим на месте (10 мин).
        //   INTERVAL_MIN_MS      — «в машине», максимум частоты (1 сек).
        //   MOVE_THRESHOLD_M     — сдвиг больше этого = ускоряемся ×2.
        //   CALM_STREAK_TO_SLOW  — столько подряд «близких» точек чтобы замедлиться ×2.
        private const val INTERVAL_DEFAULT_MS  = 600_000L
        private const val INTERVAL_MIN_MS      = 1_000L
        private const val MOVE_THRESHOLD_M     = 20.0
        private const val CALM_STREAK_TO_SLOW  = 10

        /** Проверять обновление каждый N-й POST-локации. § ANDROID-TODO 2. */
        private const val UPDATE_CHECK_EVERY_N = 5

        fun start(context: Context) {
            val i = Intent(context, LocationService::class.java)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            val i = Intent(context, LocationService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }
    }
}
