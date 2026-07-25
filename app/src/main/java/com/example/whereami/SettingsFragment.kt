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
import com.google.android.material.materialswitch.MaterialSwitch

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

        view.findViewById<TextView>(R.id.settings_version).text =
            getString(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

        val logoutLink = view.findViewById<TextView>(R.id.settings_logout_link)
        logoutLink.paintFlags = logoutLink.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        logoutLink.setOnClickListener {
            (requireActivity() as MainActivity).confirmLogout()
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
            bgLocationLauncher.launch(BackgroundReliabilityWalker.BG_LOCATION_PERM)
            return
        }
        val next = walker.nextRequiredIntent()
        if (next != null) {
            systemSettingsLauncher.launch(next)
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
