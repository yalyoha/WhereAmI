package com.example.whereami

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Таб «Карта». Показывает все live-позиции участников. Логика идентична бывшей
 * MapActivity: WebView с Я-картой + JS-bridge requestUpdate каждые ~15 сек.
 *
 * Список юзеров теперь грузится динамически через GET /api/me/users (Bearer),
 * больше нет хардкода KNOWN_SLUGS.
 */
class MapFragment : Fragment(R.layout.fragment_map) {

    private lateinit var settings: SettingsRepository
    private var webView: WebView? = null
    private val tokenRegex = Regex("^[a-fA-F0-9]{32}$")

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = SettingsRepository(requireContext())
        val web = view.findViewById<WebView>(R.id.map_web)
        webView = web
        // Прозрачный фон WebView — сквозь него виден фирменный градиент активити,
        // пока карта грузится или в редких прозрачных зонах тайлов.
        web.setBackgroundColor(0)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(MapBridge(), "Android")
        web.webViewClient = WebViewClient()
        web.loadUrl("file:///android_asset/map.html")
    }

    override fun onDestroyView() {
        webView?.let {
            it.loadUrl("about:blank")
            it.removeJavascriptInterface("Android")
        }
        webView = null
        super.onDestroyView()
    }

    private inner class MapBridge {
        @JavascriptInterface
        fun requestUpdate() {
            val token = settings.token
            if (!tokenRegex.matches(token)) {
                postJs("if(window.onUsersError)window.onUsersError('no_token');")
                return
            }
            val mySlug = settings.mySlug
            val baseUrl = settings.serverUrl
            lifecycleScope.launch(Dispatchers.IO) {
                val api = ApiClient(baseUrl)
                // 1) свежий список юзеров
                val users = when (val r = api.getUsers(token)) {
                    is ApiClient.Result.Ok -> r.value
                    is ApiClient.Result.Err -> {
                        if (r.code == 401) postJs("if(window.onUsersError)window.onUsersError('invalid_token');")
                        return@launch
                    }
                }
                // 2) последняя позиция для каждого
                val arr = JSONArray()
                for (u in users) {
                    when (val res = api.last(token, u.slug)) {
                        is ApiClient.Result.Ok -> {
                            val pl = res.value
                            val o = JSONObject()
                            o.put("slug", pl.slug)
                            o.put("displayName", pl.displayName ?: pl.slug)
                            if (pl.color != null) o.put("color", pl.color)
                            o.put("online", pl.online)
                            o.put("isMe", pl.slug == mySlug)
                            pl.position?.let {
                                o.put("lat", it.lat)
                                o.put("lon", it.lon)
                                o.put("recordedAt", it.recordedAtSec)
                                if (it.battery != null) o.put("battery", it.battery)
                            }
                            arr.put(o)
                        }
                        is ApiClient.Result.Err -> {
                            if (res.code == 401) {
                                postJs("if(window.onUsersError)window.onUsersError('invalid_token');")
                                return@launch
                            }
                        }
                    }
                }
                val arg = JSONObject.quote(arr.toString())
                postJs("if(window.onUsersUpdated)window.onUsersUpdated($arg);")
            }
        }
    }

    private fun postJs(js: String) {
        val w = webView ?: return
        w.post { w.evaluateJavascript(js, null) }
    }
}
