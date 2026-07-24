package com.example.whereami

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.whereami.databinding.ActivityAuthBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Экран авторизации: два поля (token, my_slug) + две кнопки (Сохранить, Тест отправки).
 * Если приложение уже сконфигурировано — сразу уходим на [MainActivity].
 * Разрешения / запуск сервиса делает [MainActivity], тут только ввод и валидация.
 */
class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private lateinit var settings: SettingsRepository

    private val tokenRegex = Regex("^[a-fA-F0-9]{32}$")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsRepository(this)

        // Уже залогинены — сразу карта.
        if (settings.isConfigured()) {
            goToMap()
            return
        }

        enableEdgeToEdge()
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // На случай, если поля где-то лежали (но isConfigured вернул false из-за частичного state).
        binding.inputToken.setText(settings.token)
        binding.inputMySlug.setText(settings.mySlug)

        binding.btnSave.setOnClickListener { onSaveClick() }
        binding.btnTest.setOnClickListener { onTestClick() }
    }

    private fun onSaveClick() {
        val token  = binding.inputToken.text?.toString().orEmpty().trim()
        val mySlug = binding.inputMySlug.text?.toString().orEmpty().trim().lowercase()

        if (!tokenRegex.matches(token)) { snack(R.string.toast_token_format); return }
        if (mySlug.isEmpty())            { snack(R.string.toast_my_slug_empty); return }

        settings.token  = token
        settings.mySlug = mySlug
        settings.sharingEnabled = true
        RetryWorker.kickOnce(this)
        goToMap()
    }

    private fun onTestClick() {
        val token = binding.inputToken.text?.toString().orEmpty().trim()
        if (!tokenRegex.matches(token)) { snack(R.string.toast_token_format); return }

        binding.btnTest.isEnabled = false
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                ApiClient(settings.serverUrl).update(
                    token,
                    LocationUpload(
                        lat = 55.7558, lon = 37.6173,
                        accuracyM = 1f, speedMps = null, battery = null,
                        recordedAtSec = System.currentTimeMillis() / 1000
                    )
                )
            }
            binding.btnTest.isEnabled = true
            when (res) {
                is ApiClient.Result.Ok  -> snack(R.string.toast_test_connected)
                is ApiClient.Result.Err -> snack(R.string.toast_test_failed)
            }
        }
    }

    private fun goToMap() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun snack(resId: Int) = Snackbar.make(binding.root, resId, Snackbar.LENGTH_LONG).show()
}
