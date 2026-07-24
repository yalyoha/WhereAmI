package com.example.whereami

import org.json.JSONObject

/**
 * Одна отправка на POST /api/location.
 * recordedAtSec — UNIX-секунды момента ЗАМЕРА (не отправки). При retry из очереди
 * server увидит реальное время фикса, а не время повторной попытки.
 */
data class LocationUpload(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float? = null,
    val speedMps: Float? = null,
    val battery: Int? = null,
    val recordedAtSec: Long
) {
    fun toJsonString(): String = JSONObject().apply {
        put("lat", lat)
        put("lon", lon)
        if (accuracyM != null) put("accuracy_m", accuracyM.toDouble())
        if (speedMps != null) put("speed_mps", speedMps.toDouble())
        if (battery != null) put("battery", battery)
        put("recorded_at", recordedAtSec)
    }.toString()

    companion object {
        fun fromJson(line: String): LocationUpload {
            val o = JSONObject(line)
            return LocationUpload(
                lat           = o.getDouble("lat"),
                lon           = o.getDouble("lon"),
                accuracyM     = o.optDoubleOrNull("accuracy_m")?.toFloat(),
                speedMps      = o.optDoubleOrNull("speed_mps")?.toFloat(),
                battery       = o.optIntOrNull("battery"),
                recordedAtSec = o.optLong("recorded_at", 0L)
            )
        }
    }
}

data class PartnerPosition(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float?,
    val speedMps: Float?,
    val battery: Int?,
    val recordedAtSec: Long
)

data class PartnerLast(
    val slug: String,
    val displayName: String?,
    val color: String?,
    val online: Boolean,
    val position: PartnerPosition?
)

/** Кратко юзер из /api/me/users — без токена. */
data class UserBrief(
    val slug: String,
    val displayName: String?,
    val color: String?,
)

/** Точка трека из /api/me/track. */
data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val recordedAtSec: Long
)

data class TrackResult(
    val slug: String,
    val displayName: String?,
    val color: String?,
    val points: List<TrackPoint>
)

// ---- JSONObject helpers (нативный optString возвращает "null" — раздражает) ----
fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null

fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) getDouble(key) else null

fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) getInt(key) else null

fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) getLong(key) else null
