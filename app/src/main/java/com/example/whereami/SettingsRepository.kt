package com.example.whereami

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Конфиг приложения. Токен хранится в [EncryptedSharedPreferences] (ANDROID-TASK § 3.4).
 * Остальные поля — там же; EncryptedSharedPreferences не тяжелее обычных по API, читать
 * всё из одной точки удобнее.
 *
 * Если на устройстве по какой-то причине не поднимается AndroidKeyStore (старый эмулятор,
 * сломанный keystore, повреждённый prefs-файл) — деградируемся до plain SharedPreferences,
 * чтобы приложение не падало. В лог пишем warning.
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = openSecurely(context.applicationContext)

    /** Server URL не редактируется из UI: всегда BuildConfig.API_BASE_URL. */
    val serverUrl: String get() = BuildConfig.API_BASE_URL

    var token: String
        get() = prefs.getString(KEY_TOKEN, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_TOKEN, v.trim()).apply()

    var mySlug: String
        get() = prefs.getString(KEY_MY_SLUG, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_MY_SLUG, v.trim().lowercase()).apply()

    var sharingEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHARE, false)
        set(v) = prefs.edit().putBoolean(KEY_SHARE, v).apply()

    /** Один раз показали диалог «исключи из батарейной оптимизации» — не доставать пользователя повторно. */
    var batteryOptAsked: Boolean
        get() = prefs.getBoolean(KEY_BATT_OPT_ASKED, false)
        set(v) = prefs.edit().putBoolean(KEY_BATT_OPT_ASKED, v).apply()

    /** Пользователь явно включил режим «работать в фоне» и прошёл визард системных настроек. */
    var keepInBackground: Boolean
        get() = prefs.getBoolean(KEY_KEEP_BG, false)
        set(v) = prefs.edit().putBoolean(KEY_KEEP_BG, v).apply()

    /**
     * Последняя ошибка из LocationService/BootReceiver, которую нельзя было показать
     * сразу (сервис в фоне — не может показать диалог). MainActivity.onResume её
     * покажет и очистит. Пусто = никаких ошибок с прошлого показа.
     */
    var lastError: String
        get() = prefs.getString(KEY_LAST_ERROR, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_LAST_ERROR, v).apply()

    fun isConfigured(): Boolean =
        token.length == 32 && mySlug.isNotEmpty()

    fun clearIdentity() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_MY_SLUG)
            .apply()
    }

    private fun openSecurely(ctx: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                ctx,
                PREFS_SECURE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (t: Throwable) {
            Log.w(TAG, "EncryptedSharedPreferences недоступны (${t.message}), fallback на обычные prefs")
            ctx.getSharedPreferences(PREFS_PLAIN, Context.MODE_PRIVATE)
        }
    }

    companion object {
        private const val TAG                  = "SettingsRepository"
        private const val PREFS_SECURE         = "whereami_secure"
        private const val PREFS_PLAIN          = "whereami_prefs"
        private const val KEY_TOKEN            = "bearer_token"
        private const val KEY_MY_SLUG          = "my_slug"
        private const val KEY_SHARE            = "sharing_enabled"
        private const val KEY_BATT_OPT_ASKED   = "batt_opt_asked"
        private const val KEY_KEEP_BG          = "keep_in_background"
        private const val KEY_LAST_ERROR       = "last_error"
    }
}
