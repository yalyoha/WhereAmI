package com.example.whereami

import android.app.Application
import androidx.work.WorkManager

class WhereamiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RetryWorker.schedulePeriodic(this)
        // Периодическая проверка обновлений больше не нужна: LocationService
        // триггерит UpdateWorker каждым 5-м апдейтом (§ ANDROID-TODO задача 2).
        // Уборка ранее зарегистрированного периодического воркера у уже
        // установленных клиентов; можно удалить через 1–2 релиза.
        WorkManager.getInstance(this).cancelUniqueWork("whereami_update_periodic")
        UpdateWorker.checkNow(this)     // one-shot при старте приложения
    }
}
