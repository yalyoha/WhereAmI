package com.example.whereami

import android.content.Intent
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Таб «Настройки»: Switch «Работать в фоновом режиме», номер версии и ссылка «Выйти».
 * Всё, что настраивается пользователем после первичной авторизации, живёт здесь;
 * InfoFragment остаётся чисто статистическим.
 */
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private lateinit var settings: SettingsRepository
    private lateinit var walker: BackgroundReliabilityWalker
    private lateinit var keepBgSwitch: MaterialSwitch

    private val bgLocationLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) advanceBackgroundWalk()
            else revertKeepBgSwitch()
        }

    private val systemSettingsLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // Возвращение с любого системного экрана — просто продолжаем визард.
            advanceBackgroundWalk()
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = SettingsRepository(requireContext())
        walker = BackgroundReliabilityWalker(requireContext())

        keepBgSwitch = view.findViewById(R.id.settings_keep_bg_switch)
        keepBgSwitch.isChecked = settings.keepInBackground
        keepBgSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) startBackgroundWalk()
            else settings.keepInBackground = false
        }

        view.findViewById<TextView>(R.id.settings_installed).text =
            getString(R.string.settings_installed, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

        val githubTV = view.findViewById<TextView>(R.id.settings_github)
        val updateBtn = view.findViewById<MaterialButton>(R.id.settings_update_btn)
        githubTV.text = getString(R.string.settings_github_loading)
        loadLatestVersion(githubTV, updateBtn)

        val logoutLink = view.findViewById<TextView>(R.id.settings_logout_link)
        logoutLink.paintFlags = logoutLink.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        logoutLink.setOnClickListener {
            (requireActivity() as MainActivity).confirmLogout()
        }
    }

    /**
     * Читает `releases/latest` с GitHub, выводит в githubTV,
     * зажигает updateBtn если installed < latest. Клик по кнопке —
     * checkAndUpdate() (скачивает APK) + launchInstall() (системный диалог).
     */
    private fun loadLatestVersion(githubTV: TextView, updateBtn: MaterialButton) {
        val manager = UpdateManager(requireContext())
        val installedCode = manager.installedCode()
        lifecycleScope.launch {
            val latest = withContext(Dispatchers.IO) { manager.latestVersion() }
            if (latest == null) {
                githubTV.text = getString(R.string.settings_github_error)
                updateBtn.isEnabled = false
                updateBtn.text = getString(R.string.settings_update_btn)
                return@launch
            }
            githubTV.text = getString(R.string.settings_github, latest.versionName, latest.versionCode)
            if (latest.versionCode > installedCode) {
                updateBtn.isEnabled = true
                updateBtn.text = getString(R.string.settings_update_btn)
                updateBtn.setOnClickListener {
                    triggerUpdate(updateBtn)
                }
            } else {
                updateBtn.isEnabled = false
                updateBtn.text = getString(R.string.settings_update_up_to_date)
            }
        }
    }

    private fun triggerUpdate(updateBtn: MaterialButton) {
        updateBtn.isEnabled = false
        updateBtn.text = getString(R.string.settings_github_loading)
        val manager = UpdateManager(requireContext())
        lifecycleScope.launch {
            val downloaded = withContext(Dispatchers.IO) { manager.checkAndUpdate() }
            if (downloaded || manager.hasPendingApk()) {
                manager.launchInstall()
            } else {
                // Ничего не скачалось (например уже up-to-date). Обновим статус.
                updateBtn.isEnabled = false
                updateBtn.text = getString(R.string.settings_update_up_to_date)
            }
        }
    }

    private fun startBackgroundWalk() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.keep_bg_walkthrough_title)
            .setMessage(R.string.keep_bg_walkthrough_msg)
            .setPositiveButton(android.R.string.ok) { _, _ -> advanceBackgroundWalk() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> revertKeepBgSwitch() }
            .show()
    }

    private fun advanceBackgroundWalk() {
        if (walker.needsBackgroundLocationPermission()) {
            try {
                bgLocationLauncher.launch(BackgroundReliabilityWalker.BG_LOCATION_PERM)
            } catch (t: Throwable) {
                settings.lastError =
                    "bgLocationLauncher.launch: ${t.javaClass.simpleName}: ${t.message ?: "(no message)"}"
                revertKeepBgSwitch()
            }
            return
        }
        val next = walker.nextRequiredIntent()
        if (next != null) {
            try {
                systemSettingsLauncher.launch(next)
            } catch (t: Throwable) {
                // Например: vendor autostart Activity (Huawei) требует
                // com.huawei.permission.external_app_settings.USE_COMPONENT
                // — стороннее приложение его не имеет. Пропускаем этот шаг
                // и идём дальше по визарду (batteryOpt мы уже прошли, значит
                // остаётся только vendor — помечаем skipped и завершаем).
                settings.lastError =
                    "systemSettingsLauncher.launch(${next.component}): " +
                    "${t.javaClass.simpleName}: ${t.message ?: "(no message)"}"
                walker.markVendorAutostartSkipped()
                advanceBackgroundWalk()   // рекурсивно идём к следующему шагу
            }
            return
        }
        // Визард пройден.
        settings.keepInBackground = true
        if (settings.isConfigured()) LocationService.start(requireContext())
    }

    private fun revertKeepBgSwitch() {
        keepBgSwitch.setOnCheckedChangeListener(null)
        keepBgSwitch.isChecked = false
        settings.keepInBackground = false
        keepBgSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) startBackgroundWalk() else settings.keepInBackground = false
        }
    }
}
