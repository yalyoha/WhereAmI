package com.example.whereami

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Клиент API из ANDROID-TASK / ANDROID-TEST:
 *   - POST /api/location  (Bearer)
 *   - GET  /api/last/:slug (Bearer)
 *   - GET  /api/health     (open)
 *
 * Все вызовы синхронные — оборачивай в Dispatchers.IO или используй из CoroutineWorker.
 * baseUrl должен оканчиваться на '/'.
 */
class ApiClient(private val baseUrl: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)     // ANDROID-TASK § 2.1: таймаут 10 сек
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)          // retry-логика — на WorkManager, не на OkHttp
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun fullUrl(path: String): String {
        val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return base + path.trimStart('/')
    }

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class Err(val message: String, val code: Int = 0) : Result<Nothing>()
    }

    /** Поле `thinned` от сервера — это «успешно, но точка не сохранена (дубликат)». Считаем успехом. */
    data class UpdateResult(val thinned: Boolean, val positionId: Long?)

    fun update(token: String, upload: LocationUpload): Result<UpdateResult> {
        val req = Request.Builder()
            .url(fullUrl("location"))
            .addHeader("Authorization", "Bearer $token")
            .post(upload.toJsonString().toRequestBody(jsonMedia))
            .build()
        return executeOk(req) { json ->
            UpdateResult(
                thinned    = json.optBoolean("thinned", false),
                positionId = json.optLongOrNull("position_id")
            )
        }
    }

    fun last(token: String, slug: String): Result<PartnerLast> {
        val req = Request.Builder()
            .url(fullUrl("last/" + slug.trim()))
            .addHeader("Authorization", "Bearer $token")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when (resp.code) {
                    200 -> {
                        val json = JSONObject(body)
                        val userJson = json.optJSONObject("user")
                            ?: return Result.Err("malformed_response", 200)
                        val posJson = json.optJSONObject("position")
                        val position = if (posJson != null) PartnerPosition(
                            lat           = posJson.getDouble("lat"),
                            lon           = posJson.getDouble("lon"),
                            accuracyM     = posJson.optDoubleOrNull("accuracy_m")?.toFloat(),
                            speedMps      = posJson.optDoubleOrNull("speed_mps")?.toFloat(),
                            battery       = posJson.optIntOrNull("battery"),
                            recordedAtSec = posJson.getLong("recorded_at")
                        ) else null
                        Result.Ok(PartnerLast(
                            slug        = userJson.optString("slug", slug),
                            displayName = userJson.optStringOrNull("display_name"),
                            color       = userJson.optStringOrNull("color"),
                            online      = json.optBoolean("online", false),
                            position    = position
                        ))
                    }
                    401 -> Result.Err("invalid_token", 401)
                    404 -> Result.Err("not_found", 404)
                    else -> Result.Err("HTTP ${resp.code}: ${body.take(200)}", resp.code)
                }
            }
        } catch (e: Throwable) {
            Result.Err("net: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** GET /api/me/users — список всех юзеров (без токенов). Bearer. */
    fun getUsers(token: String): Result<List<UserBrief>> {
        val req = Request.Builder()
            .url(fullUrl("me/users"))
            .addHeader("Authorization", "Bearer $token")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when (resp.code) {
                    200 -> {
                        val arr = org.json.JSONArray(body)
                        val list = (0 until arr.length()).map { i ->
                            val o = arr.getJSONObject(i)
                            UserBrief(
                                slug = o.optString("slug"),
                                displayName = o.optStringOrNull("display_name"),
                                color = o.optStringOrNull("color"),
                            )
                        }
                        Result.Ok(list)
                    }
                    401 -> Result.Err("invalid_token", 401)
                    else -> Result.Err("HTTP ${resp.code}: ${body.take(200)}", resp.code)
                }
            }
        } catch (e: Throwable) {
            Result.Err("net: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * GET /api/me/track — точки трека авторизованного юзера (slug определяется по Bearer).
     * since/until — UNIX-секунды (опционально).
     */
    fun getMyTrack(token: String, sinceSec: Long, untilSec: Long? = null): Result<TrackResult> {
        val params = buildString {
            append("?since=").append(sinceSec)
            if (untilSec != null) append("&until=").append(untilSec)
        }
        val req = Request.Builder()
            .url(fullUrl("me/track$params"))
            .addHeader("Authorization", "Bearer $token")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when (resp.code) {
                    200 -> {
                        val json = JSONObject(body)
                        val userJson = json.optJSONObject("user")
                            ?: return Result.Err("malformed_response", 200)
                        val pointsArr = json.optJSONArray("points") ?: org.json.JSONArray()
                        val points = (0 until pointsArr.length()).map { i ->
                            val p = pointsArr.getJSONObject(i)
                            TrackPoint(
                                lat = p.getDouble("lat"),
                                lon = p.getDouble("lon"),
                                recordedAtSec = p.getLong("recorded_at"),
                            )
                        }
                        Result.Ok(TrackResult(
                            slug = userJson.optString("slug"),
                            displayName = userJson.optStringOrNull("display_name"),
                            color = userJson.optStringOrNull("color"),
                            points = points,
                        ))
                    }
                    401 -> Result.Err("invalid_token", 401)
                    else -> Result.Err("HTTP ${resp.code}: ${body.take(200)}", resp.code)
                }
            }
        } catch (e: Throwable) {
            Result.Err("net: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Явный выход: помечает юзера offline на сервере немедленно.
     * Без вызова /api/logout юзер «протухает» по таймауту (180 сек), что слишком долго для UX
     * «нажал выход — сразу пропал с карты у партнёров». ANDROID-TASK § 8.
     */
    fun logout(token: String): Result<Unit> {
        val req = Request.Builder()
            .url(fullUrl("logout"))
            .addHeader("Authorization", "Bearer $token")
            .post("".toRequestBody(jsonMedia))
            .build()
        return executeOk(req) { Unit }
    }

    /** Хелпер для эндпоинтов, возвращающих `{"ok":true, ...}`. */
    private inline fun <T> executeOk(req: Request, parse: (JSONObject) -> T): Result<T> = try {
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val json = runCatching { JSONObject(body.ifEmpty { "{}" }) }.getOrNull()
            when {
                resp.code == 401 -> Result.Err("invalid_token", 401)
                !resp.isSuccessful -> Result.Err(
                    json?.optStringOrNull("error") ?: "HTTP ${resp.code}: ${body.take(200)}",
                    resp.code
                )
                json == null -> Result.Err("bad_json", resp.code)
                !json.optBoolean("ok", false) -> Result.Err(
                    json.optStringOrNull("error") ?: "unknown_error",
                    resp.code
                )
                else -> Result.Ok(parse(json))
            }
        }
    } catch (e: Throwable) {
        Result.Err("net: ${e.message ?: e.javaClass.simpleName}")
    }
}
