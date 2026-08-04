package com.example.whereami

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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
    private lateinit var battOptStatus: TextView

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

    override fun onResume() {
        super.onResume()
        // Юзер мог только что вернуться с системного экрана и включить/выключить экономию —
        // строку статуса обновляем при каждом входе на вкладку.
        if (::battOptStatus.isInitialized) refreshBattOptStatus()
    }

    private fun refreshBattOptStatus() {
        val pm = requireContext().getSystemService(PowerManager::class.java)
        val ok = pm?.isIgnoringBatteryOptimizations(requireContext().packageName) == true
        battOptStatus.text = getString(
            if (ok) R.string.batt_opt_status_ok else R.string.batt_opt_status_bad
        )
        // Кликабельно только в "плохом" состоянии — незачем ронять юзера на системный экран,
        // если и так уже разрешено.
        battOptStatus.isClickable = !ok
        battOptStatus.isFocusable = !ok
    }

    @SuppressLint("BatteryLife")
    private fun openBatteryOptSettings() {
        val pkg = requireContext().packageName
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$pkg"))
            )
        }.onFailure {
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = SettingsRepository(requireContext())
        walker = BackgroundReliabilityWalker(requireContext())

        keepBgSwitch = view.findViewById(R.id.settings_keep_bg_switch)
        keepBgSwitch.isChecked = settings.keepInBackground
        keepBgSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) startBackgroundWalk()
            else {
                settings.keepInBackground = false
                // Не сбрасываем alarm-chain немедленно — LocationService.stop/ACTION_STOP
                // здесь не дёргается (юзер может просто хотеть быть только в foreground).
                // Но schedule() внутри Receiver сам увидит keepInBackground=false и не
                // будет переставлять следующий tick — цепочка выродится через ≤10 мин.
                LocationTickReceiver.cancel(requireContext())
            }
        }

        battOptStatus = view.findViewById(R.id.settings_batt_opt_status)
        battOptStatus.setOnClickListener { openBatteryOptSettings() }
        refreshBattOptStatus()

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

        val openLog = view.findViewById<TextView>(R.id.settings_open_log)
        openLog.paintFlags = openLog.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        openLog.setOnClickListener {
            startActivity(Intent(requireContext(), LogActivity::class.java))
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
                // — стороннее приложение его не имеет. Это ожидаемое
                // ограничение платформы, не наша баг — не пугаем юзера
                // диалогом, тихо логируем и идём к следующему шагу.
                android.util.Log.w("SettingsFragment",
                    "systemSettingsLauncher.launch(${next.component}) failed: " +
                    "${t.javaClass.simpleName}: ${t.message}")
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
        LocationTickReceiver.cancel(requireContext())
        keepBgSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) startBackgroundWalk()
            else {
                settings.keepInBackground = false
                LocationTickReceiver.cancel(requireContext())
            }
        }
    }
}
