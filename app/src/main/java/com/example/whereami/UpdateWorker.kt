package com.example.whereami

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Однократная проверка наличия обновления APK и его закачка.
 * Триггерится [checkNow] при старте приложения и каждым N-м апдейтом локации
 * из [LocationService] (§ ANDROID-TODO задача 2).
 */
class UpdateWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.i(TAG, "checking for update")
        UpdateManager(applicationContext).checkAndUpdate()
        Result.success()
    }

    companion object {
        private const val TAG           = "UpdateWorker"
        private const val ONESHOT_NAME  = "whereami_update_oneshot"
        private val NET = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Однократная немедленная проверка. Идемпотентно: REPLACE поверх ранее запущенной. */
        fun checkNow(context: Context) {
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(
                    ONESHOT_NAME,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<UpdateWorker>()
                        .setConstraints(NET)
                        .build()
                )
        }
    }
}
