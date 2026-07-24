package com.example.whereami

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Поднимает LocationService после перезагрузки устройства, если пользователь
 * ранее включил «Работать в фоновом режиме» и уже сконфигурирован токен/slug.
 * Без флага не делаем ничего, чтобы не запускать сервис у тех, кто явно этого не просил.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        val settings = SettingsRepository(context)
        if (!settings.keepInBackground) return
        if (!settings.isConfigured()) return
        Log.i(TAG, "BOOT_COMPLETED → restart LocationService")
        LocationService.start(context)
    }
    companion object { private const val TAG = "BootReceiver" }
}
