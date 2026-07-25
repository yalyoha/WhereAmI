package com.example.whereami

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Визард «Работать в фоновом режиме»: гоняет пользователя по системным экранам,
 * которые у Android/вендоров нужны для того, чтобы foreground-сервис не убивали.
 *
 * Использование:
 *  1. Проверить [needsBackgroundLocationPermission] — если true, попросить
 *     [BG_LOCATION_PERM] через ActivityResultContracts.RequestPermission.
 *  2. После выдачи разрешения (или если оно уже было) вызвать [nextRequiredIntent];
 *     пока возвращает не-null — стартовать его через StartActivityForResult, после
 *     возврата снова звать [nextRequiredIntent].
 *  3. Когда всё пройдено — [nextRequiredIntent] отдаёт null.
 */
class BackgroundReliabilityWalker(private val context: Context) {

    /**
     * Флаг: пробовали открыть вендорский autostart-экран и он провалился
     * (SecurityException и т.п. — например, Huawei требует внутреннего
     * permission USE_COMPONENT, которого у сторонних приложений нет).
     * Пропускаем этот шаг, дальнейшие вызовы nextRequiredIntent его игнорируют.
     */
    private var vendorAutostartSkipped: Boolean = false

    /** Помечаем vendor-autostart шаг как «недоступный, пропустить». */
    fun markVendorAutostartSkipped() { vendorAutostartSkipped = true }

    /** Следующий системный экран, который нужно показать пользователю. */
    fun nextRequiredIntent(): Intent? {
        if (needsBackgroundLocationPermission()) return null // выдаётся через RequestPermission
        if (!isBatteryOptIgnored()) return batteryOptIntent()
        if (!vendorAutostartSkipped) vendorAutostartIntent()?.let { return it }
        return null
    }

    fun needsBackgroundLocationPermission(): Boolean = !hasBackgroundLocation()

    fun isBatteryOptIgnored(): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun hasBackgroundLocation(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun batteryOptIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))

    /**
     * Каждый вендор прячет autostart в своём кастомном Activity.
     * Проверяем через PackageManager.resolveActivity — если такого Activity нет
     * (обычное стоковое Android), возвращаем null и пропускаем шаг.
     */
    fun vendorAutostartIntent(): Intent? {
        val candidates = listOf(
            // Xiaomi (MIUI)
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            // Huawei
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            // Oppo
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            // Vivo
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            // OnePlus / Realme
            ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
            // Samsung
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        )
        val pm = context.packageManager
        return candidates
            .firstOrNull { cn ->
                val i = Intent().setComponent(cn)
                pm.resolveActivity(i, 0) != null
            }
            ?.let { cn ->
                Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }

    companion object {
        const val BG_LOCATION_PERM: String = Manifest.permission.ACCESS_BACKGROUND_LOCATION
    }
}
