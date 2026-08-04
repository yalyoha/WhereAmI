package com.example.whereami

import android.app.Application
import android.util.Log
import androidx.work.WorkManager
import java.io.PrintWriter
import java.io.StringWriter

class WhereamiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)  // до installCrashHandler — чтобы UEH тоже мог писать в файл
        FileLogger.i("WhereamiApp", "onCreate pid=${android.os.Process.myPid()} " +
                "version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
        installCrashHandler()
        try {
            RetryWorker.schedulePeriodic(this)
            // Периодическая проверка обновлений больше не нужна: LocationService
            // триггерит UpdateWorker каждым 5-м апдейтом (§ ANDROID-TODO задача 2).
            // Уборка ранее зарегистрированного периодического воркера у уже
            // установленных клиентов; можно удалить через 1–2 релиза.
            WorkManager.getInstance(this).cancelUniqueWork("whereami_update_periodic")
            UpdateWorker.checkNow(this)     // one-shot при старте приложения
            // Alarm-chain: если юзер сейчас делится (sharingEnabled=true), гарантируем что
            // цепочка армирована. schedule() сам проверит guard. Важно на Huawei:
            // если процесс воскрес не через LocationService.start (а, скажем, через
            // UpdateWorker или FileProvider), alarm мог не быть переставлен последним
            // тиком и цепочка порвётся.
            LocationTickReceiver.schedule(this)
        } catch (t: Throwable) {
            // Ничего не должно валить процесс на этом этапе. Пишем в lastError,
            // диалог покажется при следующем открытии MainActivity.
            saveCrash("WhereamiApp.onCreate", t)
        }
    }

    /**
     * Ставим глобальный UncaughtExceptionHandler: любой не пойманный throwable
     * (в UI-потоке, воркере, сервисе) пишется в SettingsRepository.lastError
     * до того как система убьёт процесс. Потом делегируем прежнему хендлеру —
     * система по-прежнему получит краш, покажет ANR/крэш-диалог и т.д.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                saveCrash("UEH[${thread.name}]", throwable)
            } catch (_: Throwable) {
                // если даже сохранить не смогли — не мешаем системному хендлеру.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun saveCrash(where: String, t: Throwable) {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        // Обрезаем stacktrace — SharedPreferences не тюннель для мегабайтов.
        val trace = sw.toString().take(4000)
        val msg = "$where: ${t.javaClass.simpleName}: ${t.message ?: "(no message)"}\n\n$trace"
        Log.e("WhereamiApp", msg)
        FileLogger.e("WhereamiApp", "CRASH $where", t)
        try {
            SettingsRepository(this).lastError = msg
        } catch (_: Throwable) { /* best effort */ }
    }
}
