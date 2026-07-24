package com.example.whereami

import android.content.Context
import java.io.File

/**
 * Файловая FIFO-очередь для координат, не доехавших до сервера.
 *
 * ANDROID-TASK § 3.3 предполагал Room. Здесь — JSONL-файл в `filesDir/pending_uploads.jsonl`.
 * Причина: AGP 9.x + Room/KSP компиляция в этой сборке нестабильна; для очереди до ~1000 записей
 * файл с одной записью на строку покрывает требования (FIFO + батчевая выборка + удаление).
 *
 * Координаты НЕ хранятся в EncryptedSharedPreferences — это не токен. Сами по себе они
 * локально на устройстве; компромисс приемлем для семейного use case.
 */
class UploadQueue(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val lock = Any()

    fun enqueue(upload: LocationUpload) = synchronized(lock) {
        file.appendText(upload.toJsonString() + "\n")
    }

    /** Текущий размер очереди. */
    fun size(): Int = synchronized(lock) {
        if (!file.exists()) 0 else file.bufferedReader().useLines { it.count() }
    }

    /** Первые [n] записей в FIFO-порядке. */
    fun peekBatch(n: Int): List<LocationUpload> = synchronized(lock) {
        if (!file.exists() || n <= 0) return@synchronized emptyList()
        file.readLines()
            .take(n)
            .mapNotNull { line ->
                runCatching { LocationUpload.fromJson(line) }.getOrNull()
            }
    }

    /** Удаляет первые [n] записей. Атомарно через временный файл. */
    fun removeFirst(n: Int) {
        synchronized(lock) {
            if (!file.exists() || n <= 0) return@synchronized
            val remaining = file.readLines().drop(n)
            if (remaining.isEmpty()) {
                file.delete()
            } else {
                val tmp = File(file.parentFile, "$FILE_NAME.tmp")
                tmp.writeText(remaining.joinToString(separator = "\n", postfix = "\n"))
                // Files.move с ATOMIC_MOVE на Android требует API 26+; renameTo достаточно надёжен.
                if (!tmp.renameTo(file)) {
                    file.writeText(tmp.readText())
                    tmp.delete()
                }
            }
        }
    }

    /** Полная очистка (например, при смене токена). */
    fun clear() {
        synchronized(lock) { file.delete() }
    }

    companion object {
        private const val FILE_NAME = "pending_uploads.jsonl"
        const val DRAIN_BATCH_SIZE = 100
    }
}
