package com.example.whereami

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
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
 * Providers — подписываемся на ВСЕ доступные одновременно (FUSED + GPS + NETWORK,
 * сколько система показывает как enabled). На Huawei/HarmonyOS FUSED_PROVIDER часто
 * молча не отдаёт callback'ов (без GMS реализация своя); дублирование покрывает такой
 * тихий провайдер. Сервер дедуплицирует через thinning, лишний трафик — единицы байт/мин.
 *
 * Startup UX «открыл приложение → маркер сразу двигается»:
 *   (1) seed: закешированный getLastKnownLocation свежее 5 мин — уходит в POST сразу;
 *   (2) bootstrap: одноразовый getCurrentLocation (API 30+) на каждый провайдер параллельно —
 *       не ждём 60-секундного интервала подписки для первого fix'а;
 *   (3) обычная подписка каждые 60 сек;
 *   (4) watchdog каждые 5 мин: если тишина > 6 мин — перекатываем всё, чтобы вылезти
 *       из состояния «FGS жив, но провайдер молчит».
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

    // Watchdog / стабильность:
    //   lastHandledAtMs — время последнего handleLocation(), обновляется в handleLocation.
    //   startedAtMs     — момент последнего applyRequest(), точка отсчёта для «тишины с рождения».
    //   subscribedProviders — на каких провайдерах активна подписка (для лога).
    // Watchdog каждые 5 мин проверяет, что что-то приходит; если тишина > 6 мин —
    // перекатывает подписку и заново дёргает getCurrentLocation. Это спасает от тихо
    // умершего провайдера (частый сценарий на Huawei/HarmonyOS: FUSED_PROVIDER числится
    // enabled, но callback'ов не даёт).
    @Volatile private var lastHandledAtMs: Long = 0L
    @Volatile private var startedAtMs: Long = 0L
    private val subscribedProviders = mutableListOf<String>()
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val watchdog = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val silentMs = if (lastHandledAtMs == 0L) (now - startedAtMs) else (now - lastHandledAtMs)
            if (silentMs > WATCHDOG_STALE_MS) {
                FileLogger.w(TAG, "watchdog: no fix for ${silentMs / 1000}s " +
                        "(subscribed=$subscribedProviders) → re-apply request")
                applyRequest()
            }
            mainHandler.postDelayed(this, WATCHDOG_PERIOD_MS)
        }
    }

    // LocationListener — SAM (functional interface), достаточно onLocationChanged.
    private val listener = LocationListener { loc -> scope.launch { handleLocation(loc) } }

    /**
     * Ловит SCREEN_ON и USER_PRESENT — когда кто-то (жена или нашедший телефон)
     * касается экрана, мгновенно перезапрашиваем свежий fix. Батарея ~0, реакция
     * секундная. Критично для сценария «телефон забыт, нашедший включает экран».
     * Динамическая регистрация обязательна: с Android 8+ SCREEN_ON статически
     * из манифеста не приходит.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            FileLogger.i(TAG, "screen event ${intent?.action ?: "(null)"} → refresh fix")
            // applyRequest пересобирает seed + bootstrap + подписку, а также
            // ресетит watchdog. Достаточно тяжело для screen-on, но простое и надёжное.
            try { applyRequest() } catch (t: Throwable) {
                FileLogger.w(TAG, "screen-triggered applyRequest failed: " +
                        "${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }
    private var screenReceiverRegistered: Boolean = false

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(LOCATION_SERVICE) as? LocationManager
        settings = SettingsRepository(this)
        queue    = UploadQueue(this)
        api      = ApiClient(settings.serverUrl)
        ensureChannel()
        registerScreenReceiver()
        FileLogger.i(TAG, "onCreate pid=${android.os.Process.myPid()}")
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            // RECEIVER_NOT_EXPORTED (API 33+): системные бродкасты — не «экспортируемые»
            // с точки зрения нашего процесса, флаг явно указан для compliance.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(screenReceiver, filter)
            }
            screenReceiverRegistered = true
            FileLogger.i(TAG, "screen receiver registered")
        } catch (t: Throwable) {
            FileLogger.w(TAG, "screen receiver register failed: " +
                    "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        try { unregisterReceiver(screenReceiver) } catch (_: Throwable) {}
        screenReceiverRegistered = false
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
            val enabled = try { locationManager.getProviders(true) } catch (_: Throwable) { emptyList() }
            FileLogger.i(TAG, "providers enabled=$enabled")

            // Подписываемся на ВСЕ доступные провайдеры одновременно (FUSED + GPS + NETWORK,
            // сколько система отдаёт как enabled). Причина — на Huawei/HarmonyOS FUSED_PROVIDER
            // часто числится enabled, но callback'ов не отдаёт молча (у Huawei своя реализация,
            // без GMS). Дублирование покрывает молчащий провайдер; сервер дедуплицирует
            // по thinning'у, лишний трафик — единицы байт на минуту.
            val subs = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= 31 && enabled.contains(LocationManager.FUSED_PROVIDER))
                subs += LocationManager.FUSED_PROVIDER
            if (enabled.contains(LocationManager.GPS_PROVIDER))
                subs += LocationManager.GPS_PROVIDER
            if (enabled.contains(LocationManager.NETWORK_PROVIDER))
                subs += LocationManager.NETWORK_PROVIDER

            if (subs.isEmpty()) {
                saveError("applyRequest", IllegalStateException("нет включённых провайдеров локации"))
                stopSelf(); return
            }

            // Снимаем прошлые подписки перед перерегистрацией (актуально для watchdog re-apply).
            try { locationManager.removeUpdates(listener) } catch (_: Throwable) {}
            subscribedProviders.clear()

            // (1) Мгновенный seed: закешированный last-known с любого провайдера, если свежий.
            //     Это делает UX «открыл приложение → маркер сразу двигается» реальным
            //     (иначе первого fix'а ждём до 60 сек + время холодного старта GPS).
            var freshest: Location? = null
            for (p in subs) {
                val cached = try { locationManager.getLastKnownLocation(p) } catch (_: SecurityException) { null }
                if (cached != null && (freshest == null || cached.time > freshest.time)) freshest = cached
            }
            val nowMs = System.currentTimeMillis()
            if (freshest != null && nowMs - freshest.time in 0..LAST_KNOWN_MAX_AGE_MS) {
                FileLogger.i(TAG, "seed cached last-known age=${(nowMs - freshest.time) / 1000}s " +
                        "provider=${freshest.provider}")
                scope.launch { handleLocation(freshest) }
            }

            // (2) Bootstrap: разовый свежий fix через getCurrentLocation (API 30+),
            //     без ожидания 60-секундного интервала подписки. Быстрый ответ для NETWORK,
            //     точный для GPS. Отдаём оба — первый пришедший увидит и thinned сервер отобьёт.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                requestSingleShot(locationManager, subs)
            }

            // (3) Основная подписка на каждый провайдер, 60 сек.
            for (p in subs) {
                try {
                    locationManager.requestLocationUpdates(
                        p,
                        INTERVAL_MS,
                        0f,
                        listener,
                        Looper.getMainLooper()
                    )
                    subscribedProviders += p
                } catch (t: Throwable) {
                    FileLogger.w(TAG, "requestLocationUpdates($p) failed: " +
                            "${t.javaClass.simpleName}: ${t.message}")
                }
            }
            if (subscribedProviders.isEmpty()) {
                saveError("applyRequest", IllegalStateException("не удалось подписаться ни на один провайдер"))
                stopSelf(); return
            }
            FileLogger.i(TAG, "subscribed on $subscribedProviders interval=${INTERVAL_MS}ms")

            // (4) Watchdog: если 6 минут молчания — тихий провайдер, перекатим подписку.
            startedAtMs = nowMs
            mainHandler.removeCallbacks(watchdog)
            mainHandler.postDelayed(watchdog, WATCHDOG_PERIOD_MS)
        } catch (t: Throwable) {
            saveError("applyRequest", t)
            stopSelf()
        }
    }

    /**
     * Разовый свежий fix через LocationManager.getCurrentLocation (API 30+).
     * Дёргаем на каждый подписанный провайдер параллельно — первый ответ и решает.
     * SecurityException'ы (нет BG_LOCATION у не-foreground сервиса на Huawei) глотаем.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun requestSingleShot(locationManager: LocationManager, providers: List<String>) {
        val executor = ContextCompat.getMainExecutor(this)
        for (p in providers) {
            try {
                locationManager.getCurrentLocation(p, null, executor) { loc ->
                    if (loc != null) {
                        FileLogger.i(TAG, "getCurrentLocation($p) → fresh fix")
                        scope.launch { handleLocation(loc) }
                    } else {
                        FileLogger.w(TAG, "getCurrentLocation($p) → null (timeout/no signal)")
                    }
                }
            } catch (t: Throwable) {
                FileLogger.w(TAG, "getCurrentLocation($p) failed: " +
                        "${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private fun handleLocation(loc: Location) {
        lastHandledAtMs = System.currentTimeMillis()
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
        try { mainHandler.removeCallbacks(watchdog) } catch (_: Throwable) {}
        unregisterScreenReceiver()
        subscribedProviders.clear()
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

        // Watchdog: раз в 5 мин проверяем, что handleLocation вызывался; если тишина
        // > 6 мин (5 период + 1 мин слэк на интервал подписки) — считаем провайдер
        // тихо мёртвым и перекатываем подписку через applyRequest().
        private const val WATCHDOG_PERIOD_MS = 5L * 60L * 1000L
        private const val WATCHDOG_STALE_MS  = 6L * 60L * 1000L

        // Кешированный last-known свежее этого срока годится как мгновенный seed
        // при старте сервиса. Старше — не даём, чтобы не показывать «где был вчера».
        private const val LAST_KNOWN_MAX_AGE_MS = 5L * 60L * 1000L

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
            // Всегда переставляем alarm chain при каждом start — идемпотентно.
            // Даже если startForegroundService упал (напр. на Huawei ForegroundServiceStart-
            // NotAllowedException), alarm chain останется и через 10 мин снова оживит.
            LocationTickReceiver.schedule(context)
        }

        fun stop(context: Context) {
            val i = Intent(context, LocationService::class.java).setAction(ACTION_STOP)
            FileLogger.i("LocationService", "stop() → dispatched ACTION_STOP + cancel tick")
            try { context.startService(i) } catch (_: Throwable) {}
            // Отменяем alarm chain — иначе после logout мы будем продолжать POST'ить.
            LocationTickReceiver.cancel(context)
        }
    }
}
