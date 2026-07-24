package com.example.whereami

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Дистанция между двумя точками на Земле в метрах. Haversine, R=6371 км.
 * Симметрична. Для близких точек точность ~миллиметровая.
 */
object Haversine {
    private const val R = 6_371_000.0
    private fun toRad(deg: Double) = deg * Math.PI / 180.0

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = toRad(lat2 - lat1)
        val dLon = toRad(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
                cos(toRad(lat1)) * cos(toRad(lat2)) *
                sin(dLon / 2).let { it * it }
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    /** Суммарная длина пути по списку точек, отсортированных ASC по времени. */
    fun totalLengthMeters(points: List<Pair<Double, Double>>): Double {
        if (points.size < 2) return 0.0
        var sum = 0.0
        for (i in 1 until points.size) {
            sum += distanceMeters(
                points[i - 1].first, points[i - 1].second,
                points[i].first, points[i].second
            )
        }
        return sum
    }
}
