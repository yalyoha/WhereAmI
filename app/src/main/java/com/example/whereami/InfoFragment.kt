package com.example.whereami

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Таб «Инфо» — статистика по моим перемещениям за период.
 * Дёргает GET /api/me/track?since=&until=, Haversine считает суммарную длину.
 *
 * Периоды: сегодня, неделя, месяц. (Вчера для статистики неинтересно, в отличие
 * от визуализации трека.)
 */
class InfoFragment : Fragment(R.layout.fragment_info) {

    private lateinit var settings: SettingsRepository
    private val tokenRegex = Regex("^[a-fA-F0-9]{32}$")
    private var currentPeriodId = R.id.info_period_today
    private val timeFmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

    private lateinit var distance: TextView
    private lateinit var points: TextView
    private lateinit var firstLast: TextView
    private lateinit var status: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = SettingsRepository(requireContext())

        distance = view.findViewById(R.id.info_distance)
        points = view.findViewById(R.id.info_points)
        firstLast = view.findViewById(R.id.info_first_last)
        status = view.findViewById(R.id.info_status)

        val group = view.findViewById<MaterialButtonToggleGroup>(R.id.info_period_group)
        group.check(currentPeriodId)
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentPeriodId = checkedId
                loadStats()
            }
        }
        loadStats()
    }

    private fun loadStats() {
        val token = settings.token
        if (!tokenRegex.matches(token)) {
            showError("нет токена")
            return
        }
        status.text = getString(R.string.info_loading)
        distance.text = ""
        points.text = ""
        firstLast.text = ""

        val (since, until) = periodToRange(currentPeriodId)
        val baseUrl = settings.serverUrl

        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                ApiClient(baseUrl).getMyTrack(token, since, until)
            }
            when (res) {
                is ApiClient.Result.Ok -> renderStats(res.value)
                is ApiClient.Result.Err -> showError(res.message)
            }
        }
    }

    private fun renderStats(tr: TrackResult) {
        status.text = ""
        val pts = tr.points
        if (pts.isEmpty()) {
            distance.text = "—"
            points.text = getString(R.string.info_points, 0)
            firstLast.text = ""
            return
        }
        // Server отдаёт DESC по recorded_at, нам для длины нужен ASC.
        val asc = pts.sortedBy { it.recordedAtSec }
        val total = Haversine.totalLengthMeters(asc.map { it.lat to it.lon })
        distance.text = formatDistance(total)
        points.text = getString(R.string.info_points, pts.size)
        firstLast.text = getString(
            R.string.info_first_last,
            timeFmt.format(Date(asc.first().recordedAtSec * 1000)),
            timeFmt.format(Date(asc.last().recordedAtSec * 1000))
        )
    }

    private fun showError(msg: String) {
        distance.text = ""
        points.text = ""
        firstLast.text = ""
        status.text = getString(R.string.info_error, msg)
    }

    private fun formatDistance(meters: Double): String = when {
        meters >= 10_000 -> String.format(Locale.getDefault(), "%.1f км", meters / 1000.0)
        meters >= 1_000  -> String.format(Locale.getDefault(), "%.2f км", meters / 1000.0)
        else             -> String.format(Locale.getDefault(), "%d м", meters.toInt())
    }

    private fun periodToRange(periodId: Int): Pair<Long, Long> {
        val nowMs = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val midnight = cal.timeInMillis
        val nowSec = nowMs / 1000
        return when (periodId) {
            R.id.info_period_today -> Pair(midnight / 1000, nowSec)
            R.id.info_period_week  -> Pair(nowSec - 7L * 86400, nowSec)
            R.id.info_period_month -> Pair(nowSec - 30L * 86400, nowSec)
            else -> Pair(midnight / 1000, nowSec)
        }
    }
}
