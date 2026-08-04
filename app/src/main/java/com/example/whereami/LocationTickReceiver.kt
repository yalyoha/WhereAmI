package com.example.whereami

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Периодический (10 мин) «тик», планируемый через AlarmManager. Работает даже
 * когда LocationService мёртв — алармы хранит система, а не наш процесс. При
 * срабатывании Android воскрешает процесс приложения, доставляет broadcast,
 * receiver делает свою работу.
 *
 * Это архитектурный костыль против Huawei Power Genie / EMUI battery manager,
 * которые убивают FGS без предупреждения. FGS остаётся «быстрой веткой»
 * (60-сек апдейты, когда живёт), а этот alarm-chain — «медленная гарантированная
 * ветка», которая переживает смерть процесса.
 *
 * На каждом тике:
 *   (1) сразу пере-планируем СЛЕДУЮЩИЙ тик — до любой работы. Если этот
 *       упадёт, мы навсегда потеряем канал; поэтому reschedule первым.
 *   (2) оживляем LocationService: если он мёртв — startForegroundService поднимет;
 *       если жив — onStartCommand просто перезарегистрирует подписку.
 *   (3) прямо из receiver'а через goAsync делаем свежий getCurrentLocation
 *       и POST в /api/location. Не полагаемся на FGS, который может быть
 *       снова убит через 30 секунд.
 *
 * Ограничение goAsync: система даёт receiver'у ~10 секунд hard-wall. Держим
 * getCurrentLocation-таймаут в 8 сек, чтобы успеть POST'нуть до убийства.
 */
class LocationTickReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        FileLogger.init(context.applicationContext)
        val action = intent?.action ?: "(null)"
        FileLogger.i(TAG, "tick action=$action")

        // (1) Всегда сразу переставляем следующий tick — цепочка не должна прерваться.
        schedule(context.applicationContext)

        val settings = SettingsRepository(context)
        if (!settings.isConfigured()) {
            FileLogger.i(TAG, "skip: не сконфигурирован")
            return
        }
        if (!settings.sharingEnabled) {
            FileLogger.i(TAG, "skip: sharingEnabled=false (юзер сделал logout или token невалиден)")
            return
        }

        // (2) Оживляем FGS. Если он жив — no-op (onStartCommand просто перепишет подписку).
        LocationService.start(context)

        // (3) Свежий fix + POST прямо из receiver'а. goAsync держит процесс живым до pending.finish().
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                fetchAndPost(context.applicationContext, settings)
            } catch (t: Throwable) {
                FileLogger.w(TAG, "tick work failed: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun fetchAndPost(context: Context, settings: SettingsRepository) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) { FileLogger.w(TAG, "LocationManager null"); return }

        val loc = withTimeoutOrNull(TICK_LOCATION_TIMEOUT_MS) { getFreshLocation(context, lm) }
        if (loc == null) {
            FileLogger.w(TAG, "tick: no fix in ${TICK_LOCATION_TIMEOUT_MS / 1000}s")
            return
        }
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val battery = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        val upload = LocationUpload(
            lat = loc.latitude,
            lon = loc.longitude,
            accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
            speedMps = if (loc.hasSpeed()) loc.speed else null,
            battery = battery,
            recordedAtSec = if (loc.time > 0) loc.time / 1000 else System.currentTimeMillis() / 1000,
        )
        val api = ApiClient(settings.serverUrl)
        val res = api.update(settings.token, upload)
        val coords = "%.6f,%.6f".format(upload.lat, upload.lon)
        when (res) {
            is ApiClient.Result.Ok -> FileLogger.i(TAG, "tick send ok $coords " +
                    "thinned=${res.value.thinned} id=${res.value.positionId}")
            is ApiClient.Result.Err -> {
                FileLogger.w(TAG, "tick send err code=${res.code} msg=${res.message} $coords → в очередь")
                UploadQueue(context).enqueue(upload)
                RetryWorker.kickOnce(context)
            }
        }
    }

    /**
     * Свежий fix за минимальное время:
     *   - если есть last-known свежее 2 мин — берём его (0 сек, 0 батареи);
     *   - иначе (API 30+) getCurrentLocation на предпочтительный провайдер
     *     (NETWORK — быстрее, GPS — точнее); первый ответ решает.
     */
    private suspend fun getFreshLocation(context: Context, lm: LocationManager): Location? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) return null

        val enabled = try { lm.getProviders(true) } catch (_: Throwable) { emptyList() }
        var best: Location? = null
        for (p in enabled) {
            val loc = try { lm.getLastKnownLocation(p) } catch (_: SecurityException) { null }
            if (loc != null && (best == null || loc.time > best.time)) best = loc
        }
        val now = System.currentTimeMillis()
        if (best != null && now - best.time < LAST_KNOWN_MAX_AGE_MS) return best

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return best

        // Приоритет NETWORK (быстро при наличии wifi/cell) → GPS (точно) → FUSED.
        val provider = when {
            enabled.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            enabled.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            enabled.contains(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            else -> return best
        }
        return suspendCancellableCoroutine { cont ->
            try {
                lm.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(context)) { loc ->
                    if (cont.isActive) cont.resume(loc ?: best)
                }
            } catch (t: Throwable) {
                FileLogger.w(TAG, "getCurrentLocation($provider) failed: " +
                        "${t.javaClass.simpleName}: ${t.message}")
                if (cont.isActive) cont.resume(best)
            }
        }
    }

    companion object {
        private const val TAG = "LocationTickReceiver"
        private const val ACTION_TICK = "com.example.whereami.LOCATION_TICK"

        /**
         * 10 мин — компромисс: чаще = больше батарея, реже = дольше искать при потере.
         * В doze при setAndAllowWhileIdle реальный интервал ~9-15 мин, приемлемо.
         */
        private const val TICK_INTERVAL_MS = 10L * 60L * 1000L

        /**
         * BroadcastReceiver.goAsync даёт ~10 сек hard-wall. 8 сек на getCurrentLocation
         * оставляет ~2 сек на POST — обычно хватает.
         */
        private const val TICK_LOCATION_TIMEOUT_MS = 8_000L

        /** last-known свежее 2 мин считаем валидным без getCurrentLocation. */
        private const val LAST_KNOWN_MAX_AGE_MS = 2L * 60L * 1000L

        private const val REQUEST_CODE = 77

        /**
         * Планирует следующий tick через AlarmManager. Идемпотентно — можно звать хоть каждый апдейт.
         *
         * Guard: армируем при sharingEnabled=true. Это условие «юзер сейчас делится» —
         * ставится в MainActivity.startSharingIfReady при открытии приложения и снимается
         * только на 401 или explicit logout. Отдельный флаг keepInBackground (пройден визард
         * системных настроек) НЕ требуется для alarm chain — он нужен только для BootReceiver
         * (перезапуск после ребута — более сильный opt-in). Причина изменения: у пользователей
         * без включённого Switch'а Power Genie на Huawei убивал FGS без воскрешения — теперь
         * alarm chain работает автоматически при любом активном шаринге.
         */
        fun schedule(context: Context) {
            val settings = SettingsRepository(context)
            if (!settings.sharingEnabled) {
                FileLogger.i("LocationTickReceiver", "schedule skipped: sharingEnabled=false")
                return
            }
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            if (am == null) {
                FileLogger.w("LocationTickReceiver", "AlarmManager null — cannot schedule")
                return
            }
            val pi = pendingIntent(context)
            val triggerAt = SystemClock.elapsedRealtime() + TICK_INTERVAL_MS
            try {
                // setAndAllowWhileIdle не требует SCHEDULE_EXACT_ALARM, работает в doze
                // с white-list исключением. Реальная точность ~9-15 мин, для нашей задачи ок.
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                FileLogger.i("LocationTickReceiver",
                    "scheduled next tick in ${TICK_INTERVAL_MS / 60_000} min")
            } catch (t: Throwable) {
                FileLogger.w("LocationTickReceiver",
                    "schedule failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }

        /** Отменяет chain — использовать при logout / disable sharing. */
        fun cancel(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            try { am.cancel(pendingIntent(context)) } catch (_: Throwable) {}
            FileLogger.i("LocationTickReceiver", "chain cancelled")
        }

        private fun pendingIntent(context: Context): PendingIntent {
            val i = Intent(context, LocationTickReceiver::class.java).setAction(ACTION_TICK)
            return PendingIntent.getBroadcast(
                context, REQUEST_CODE, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
