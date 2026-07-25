package com.example.whereami

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Таб «Путь» — рисует мой трек polyline'ом за выбранный период:
 * сегодня / вчера / неделя / месяц. Период считается локально:
 *   today      = с 00:00 сегодня
 *   yesterday  = вчера с 00:00 до 23:59
 *   week       = последние 7 суток
 *   month      = последние 30 суток
 *
 * Дёргает GET /api/me/track?since=&until= с Bearer токеном.
 */
class PathFragment : Fragment(R.layout.fragment_path) {

    private lateinit var settings: SettingsRepository
    private var webView: WebView? = null
    private val tokenRegex = Regex("^[a-fA-F0-9]{32}$")
    private var currentPeriodId = R.id.period_today

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = SettingsRepository(requireContext())

        val web = view.findViewById<WebView>(R.id.path_web)
        webView = web
        // Прозрачный фон — сквозь WebView виден фирменный градиент.
        web.setBackgroundColor(0)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = WebViewClient()
        web.loadUrl("file:///android_asset/path_map.html")

        val group = view.findViewById<MaterialButtonToggleGroup>(R.id.period_group)
        group.check(currentPeriodId)
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentPeriodId = checkedId
                loadTrack()
            }
        }

        // первая загрузка после mount'а карты — ymaps инициализируется быстро,
        // полилайн пушится через onTrackUpdated с очередью pending (см. path_map.html)
        loadTrack()
    }

    override fun onDestroyView() {
        webView?.loadUrl("about:blank")
        webView = null
        super.onDestroyView()
    }

    private fun loadTrack() {
        val token = settings.token
        if (!tokenRegex.matches(token)) return
        val baseUrl = settings.serverUrl
        val (sinceSec, untilSec) = periodToRange(currentPeriodId)

        lifecycleScope.launch {
            val tr = withContext(Dispatchers.IO) {
                ApiClient(baseUrl).getMyTrack(token, sinceSec, untilSec)
            }
            when (tr) {
                is ApiClient.Result.Ok -> renderTrack(tr.value)
                is ApiClient.Result.Err -> {
                    val empty = JSONObject().apply {
                        put("color", "#888")
                        put("label", "ошибка: ${tr.message}")
                        put("points", JSONArray())
                    }
                    postJs("if(window.onTrackUpdated)window.onTrackUpdated(${JSONObject.quote(empty.toString())});")
                }
            }
        }
    }

    private fun renderTrack(tr: TrackResult) {
        val arr = JSONArray()
        for (p in tr.points) {
            arr.put(JSONArray().apply { put(p.lat); put(p.lon); put(p.recordedAtSec) })
        }
        val data = JSONObject().apply {
            put("color", tr.color ?: "#4a90ff")
            put("label", tr.displayName ?: tr.slug)
            put("points", arr)
        }
        postJs("if(window.onTrackUpdated)window.onTrackUpdated(${JSONObject.quote(data.toString())});")
    }

    private fun postJs(js: String) {
        val w = webView ?: return
        w.post { w.evaluateJavascript(js, null) }
    }

    /** Возвращает (sinceSec, untilSec) на основе R.id.period_*. */
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
            R.id.period_today -> Pair(midnight / 1000, nowSec)
            R.id.period_yesterday -> {
                val startYest = midnight - 24L * 3600 * 1000
                val endYest = midnight - 1
                Pair(startYest / 1000, endYest / 1000)
            }
            R.id.period_week -> Pair(nowSec - 7L * 86400, nowSec)
            R.id.period_month -> Pair(nowSec - 30L * 86400, nowSec)
            else -> Pair(midnight / 1000, nowSec)
        }
    }
}
