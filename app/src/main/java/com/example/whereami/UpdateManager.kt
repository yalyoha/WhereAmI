package com.example.whereami

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/*
 * Конвенция релиза на GitHub (см. ANDROID-TODO задача 1):
 *   - тег: v<versionName>          (например v3.1)
 *   - первая строка body: versionCode=<int>   (например versionCode=4)
 *   - ровно один .apk в assets    (например WhereAmI-release.apk)
 * Всё, что не подходит под конвенцию, считается «нет обновления» + warning в лог.
 */

/**
 * Проверяет обновление APK и устанавливает его через системный интент.
 *
 * Поток: fetchManifest() → сравнить versionCode → downloadApk() → showInstallNotification().
 * Источник манифеста — GitHub Releases API (BuildConfig.GITHUB_RELEASES_URL);
 * конвенция релиза описана в комментарии на верху файла.
 */
class UpdateManager(private val context: Context) {

    data class VersionManifest(val versionCode: Int, val versionName: String, val apkUrl: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Основной метод: проверяет, скачивает при необходимости, показывает уведомление. */
    suspend fun checkAndUpdate(): Boolean {
        val manifest = fetchManifest() ?: return false
        val installed = installedVersionCode()
        if (manifest.versionCode <= installed) {
            Log.i(TAG, "up to date: installed=$installed server=${manifest.versionCode}")
            deleteStaleCachedApk()
            return false
        }
        Log.i(TAG, "update available: ${manifest.versionCode} > $installed")
        val apk = cachedApkFile()
        if (!apk.exists() || apk.length() == 0L) {
            if (!downloadApk(manifest.apkUrl, apk)) return false
        }
        showInstallNotification(apk, manifest.versionName)
        return true
    }

    /** Возвращает true если скачанный APK ожидает установки. */
    fun hasPendingApk(): Boolean = cachedApkFile().let { it.exists() && it.length() > 0 }

    /** Запускает системный диалог установки для уже скачанного APK. */
    fun launchInstall() {
        val apk = cachedApkFile()
        if (!apk.exists()) return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Log.e(TAG, "install launch failed: ${it.message}") }
    }

    private fun fetchManifest(): VersionManifest? = try {
        val req = Request.Builder()
            .url(BuildConfig.GITHUB_RELEASES_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "GitHub releases HTTP ${resp.code}")
                return null
            }
            val json = JSONObject(resp.body?.string() ?: return null)
            val tag = json.getString("tag_name")             // "v3.1"
            val versionName = tag.removePrefix("v")
            val body = json.optString("body", "")
            val versionCode = VERSION_CODE_REGEX
                .find(body)?.groupValues?.get(1)?.toIntOrNull()
                ?: run {
                    Log.w(TAG, "no versionCode= in release body")
                    return null
                }
            val assets = json.getJSONArray("assets")
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.getString("browser_download_url")
                    break
                }
            }
            if (apkUrl == null) {
                Log.w(TAG, "no .apk asset in release")
                return null
            }
            VersionManifest(versionCode = versionCode, versionName = versionName, apkUrl = apkUrl)
        }
    } catch (e: Throwable) {
        Log.w(TAG, "manifest fetch failed: ${e.message}")
        null
    }

    private fun downloadApk(url: String, dest: File): Boolean {
        dest.parentFile?.mkdirs()
        return try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "APK download HTTP ${resp.code}")
                    return false
                }
                dest.outputStream().use { out ->
                    resp.body?.byteStream()?.copyTo(out) ?: return false
                }
            }
            Log.i(TAG, "APK downloaded: ${dest.length()} bytes")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "download failed: ${e.message}")
            dest.delete()
            false
        }
    }

    private fun showInstallNotification(apk: File, versionName: String) {
        ensureChannel()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(
            context, 0, installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("WhereAmI $versionName готова")
            .setContentText("Нажмите для установки обновления")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
    }

    private fun installedVersionCode(): Int = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            pi.longVersionCode.toInt()
        else
            @Suppress("DEPRECATION") pi.versionCode
    } catch (e: Throwable) { 0 }

    private fun cachedApkFile() = File(context.externalCacheDir, "update/WhereAmI.apk")

    /**
     * Удаляет кешированный APK, если он не новее установленной версии
     * (например, после успешной установки). Вызывается при каждой проверке —
     * «только последний APK на диске» держится автоматически.
     */
    private fun deleteStaleCachedApk() {
        val apk = cachedApkFile()
        if (apk.exists()) apk.delete()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Обновления приложения", NotificationManager.IMPORTANCE_HIGH)
                )
            }
        }
    }

    companion object {
        private const val TAG       = "UpdateManager"
        const val CHANNEL_ID        = "whereami_updates"
        private const val NOTIF_ID  = 1001
        private val VERSION_CODE_REGEX = Regex("""versionCode\s*=\s*(\d+)""")
    }
}
