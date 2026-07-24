package com.example.whereami

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Дренирует UploadQueue: берёт батч, шлёт по очереди в /api/location.
 *
 * Поведение по результатам (ANDROID-TASK § 2.1):
 *   - 200 ok / 200 thinned → удалить из очереди
 *   - 400                  → удалить (payload битый, повтор бесполезен)
 *   - 401                  → прерваться, очередь не трогать (ждём, что владелец сменит токен)
 *   - 5xx / сеть           → прерваться, periodic запустится снова через 15 мин
 */
class RetryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx      = applicationContext
        val settings = SettingsRepository(ctx)
        val queue    = UploadQueue(ctx)

        val token = settings.token
        if (token.length != 32 || settings.mySlug.isEmpty()) {
            Log.i(TAG, "skip: не сконфигурирован токен/slug")
            return@withContext Result.success()
        }

        val api = ApiClient(settings.serverUrl)
        var processed   = 0
        var stopOnAuth  = false

        val batch = queue.peekBatch(UploadQueue.DRAIN_BATCH_SIZE)
        if (batch.isEmpty()) return@withContext Result.success()

        Log.i(TAG, "drain start: ${batch.size} в очереди")

        for (item in batch) {
            when (val res = api.update(token, item)) {
                is ApiClient.Result.Ok -> processed++
                is ApiClient.Result.Err -> {
                    when (res.code) {
                        401 -> { stopOnAuth = true; break }
                        400 -> { Log.w(TAG, "drop bad payload: ${res.message}"); processed++ }
                        else -> {
                            Log.w(TAG, "drain stop: ${res.code} ${res.message}")
                            break // 5xx или сеть — оставляем хвост на следующий период
                        }
                    }
                }
            }
        }

        if (processed > 0) queue.removeFirst(processed)
        Log.i(TAG, "drain end: отправлено $processed, осталось ${queue.size()}")

        if (stopOnAuth) {
            // Не Result.retry() — иначе будет крутиться вхолостую с битым токеном.
            return@withContext Result.success()
        }
        Result.success()
    }

    companion object {
        private const val TAG = "RetryWorker"
        private const val PERIODIC_NAME = "whereami_retry_periodic"
        private const val ONESHOT_NAME  = "whereami_retry_oneshot"

        /** Запустить периодическую разгрузку очереди (раз в 15 мин — минимально допустимо для PeriodicWork). */
        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<RetryWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** Однократный «дёрни прямо сейчас» — например, после смены токена или появления сети. */
        fun kickOnce(context: Context) {
            val req = OneTimeWorkRequestBuilder<RetryWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(ONESHOT_NAME, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
