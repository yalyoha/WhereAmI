package com.example.whereami

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistent ring-buffer лог для диагностики фоновой работы на OEM (Huawei и др.).
 *
 * Проблема: `adb logcat` требует ПК — на телефоне пользователя мы не видим,
 * почему система убила FGS. Стандартный `SettingsRepository.lastError` держит
 * только одну строку.
 *
 * Что делает: пишет строки в 2 файла `log-0.txt`/`log-1.txt` в приватном хранилище
 * приложения (`filesDir/logs`). Каждый файл ограничен MAX_FILE_BYTES; при переполнении
 * активного файла — свопаем: активный становится «old», old — truncate'ится и становится
 * «new active». Всегда на диске максимум 2 × MAX_FILE_BYTES.
 *
 * Инициализируется лениво в [init]; до инициализации вызовы буферизуются в памяти
 * (INIT_MEM_BUFFER). Это защищает от гонки «Application.onCreate ещё не отработал,
 * а UEH уже пишет краш».
 */
object FileLogger {

    private const val TAG_TAG            = "FileLogger"
    private const val MAX_FILE_BYTES     = 128L * 1024L   // 128 KB × 2 = 256 KB на диске
    private const val DIR_NAME           = "logs"
    private const val FILE_A             = "log-0.txt"
    private const val FILE_B             = "log-1.txt"
    private const val INIT_MEM_BUFFER    = 256            // до init() держим в памяти

    private val ready = AtomicBoolean(false)
    private val preInit = ArrayDeque<String>()

    @Volatile private var dir: File? = null
    @Volatile private var active: File? = null
    @Volatile private var passive: File? = null

    private val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val ioLock = Any()

    fun init(context: Context) {
        if (ready.get()) return
        synchronized(this) {
            if (ready.get()) return
            try {
                val d = File(context.filesDir, DIR_NAME).apply { mkdirs() }
                dir = d
                active  = File(d, FILE_A)
                passive = File(d, FILE_B)
                // Свежий active — файл, у которого mtime новее (после установки апдейта
                // filesDir остаётся, файлы могли быть недорасходованы).
                if ((passive?.lastModified() ?: 0L) > (active?.lastModified() ?: 0L)) {
                    val swap = active; active = passive; passive = swap
                }
                ready.set(true)
                // Флашим то что успело набежать до init.
                val buffered = synchronized(preInit) { val c = preInit.toList(); preInit.clear(); c }
                for (line in buffered) writeLine(line)
                writeLine(format("I", "FileLogger", "init OK dir=${d.absolutePath}"))
            } catch (t: Throwable) {
                // Если даже файлы не создались — молча деградируем в logcat-only.
                Log.w(TAG_TAG, "init failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    fun d(tag: String, msg: String) { Log.d(tag, msg); enqueue(format("D", tag, msg)) }
    fun i(tag: String, msg: String) { Log.i(tag, msg); enqueue(format("I", tag, msg)) }
    fun w(tag: String, msg: String) { Log.w(tag, msg); enqueue(format("W", tag, msg)) }
    fun w(tag: String, msg: String, t: Throwable) {
        Log.w(tag, msg, t)
        enqueue(format("W", tag, "$msg | ${t.javaClass.simpleName}: ${t.message}"))
    }
    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
        enqueue(format("E", tag, if (t == null) msg else "$msg | ${t.javaClass.simpleName}: ${t.message}"))
    }

    private fun format(level: String, tag: String, msg: String): String {
        val safe = msg.replace('\n', ' ').replace('\r', ' ')
        return "${ts.format(Date())}  $level  $tag  $safe"
    }

    private fun enqueue(line: String) {
        if (!ready.get()) {
            synchronized(preInit) {
                preInit.addLast(line)
                while (preInit.size > INIT_MEM_BUFFER) preInit.removeFirst()
            }
            return
        }
        writeLine(line)
    }

    private fun writeLine(line: String) {
        val a = active ?: return
        synchronized(ioLock) {
            try {
                if (a.length() >= MAX_FILE_BYTES) rotate()
                RandomAccessFile(active ?: return, "rw").use { raf ->
                    raf.seek(raf.length())
                    raf.write((line + "\n").toByteArray(Charsets.UTF_8))
                }
            } catch (t: Throwable) {
                Log.w(TAG_TAG, "writeLine failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private fun rotate() {
        val a = active ?: return
        val p = passive ?: return
        try {
            // passive выкидываем, активный делаем passive'ом (сохраняем хвост),
            // затем создаём новый пустой active.
            if (p.exists()) p.delete()
            a.renameTo(p)   // если rename не сработал, просто уронит следующую запись — не крит
        } catch (t: Throwable) {
            Log.w(TAG_TAG, "rotate failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Полный текст лога: passive (старее) + active (новее). Возвращает не больше limitBytes хвоста. */
    fun dump(limitBytes: Int = 256 * 1024): String {
        if (!ready.get()) return "(logger not initialized)"
        return synchronized(ioLock) {
            val sb = StringBuilder()
            passive?.takeIf { it.exists() }?.let { sb.append(it.readText(Charsets.UTF_8)) }
            active?.takeIf  { it.exists() }?.let { sb.append(it.readText(Charsets.UTF_8)) }
            val s = sb.toString()
            if (s.length <= limitBytes) s else s.substring(s.length - limitBytes)
        }
    }

    fun clear() {
        synchronized(ioLock) {
            try {
                active?.writeText("", Charsets.UTF_8)
                passive?.writeText("", Charsets.UTF_8)
                writeLine(format("I", "FileLogger", "log cleared by user"))
            } catch (t: Throwable) {
                Log.w(TAG_TAG, "clear failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }
}
