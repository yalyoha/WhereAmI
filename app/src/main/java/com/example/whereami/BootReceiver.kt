package com.example.whereami

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Поднимает LocationService после перезагрузки устройства, если пользователь
 * ранее включил «Работать в фоновом режиме» и уже сконфигурирован токен/slug.
 * Без флага не делаем ничего, чтобы не запускать сервис у тех, кто явно этого не просил.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: "(null)"
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        FileLogger.init(context.applicationContext)
        val settings = SettingsRepository(context)
        if (!settings.keepInBackground) {
            FileLogger.i(TAG, "$action ignored: keepInBackground=false")
            return
        }
        if (!settings.isConfigured()) {
            FileLogger.i(TAG, "$action ignored: не сконфигурирован")
            return
        }
        FileLogger.i(TAG, "$action → restart LocationService")
        LocationService.start(context)
    }
    companion object { private const val TAG = "BootReceiver" }
}
