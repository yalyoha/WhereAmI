package com.example.whereami

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Главная активность — host для 4 фрагментов в BottomNavigationView:
 * Карта · Путь · Инфо · Выход.
 *
 * Permission flow и старт LocationService живут здесь (общий для всех табов).
 * Тап «Выход» НЕ переключает фрагмент — открывает confirm dialog → logout.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var settings: SettingsRepository
    private lateinit var bottomNav: BottomNavigationView
    private val tokenRegex = Regex("^[a-fA-F0-9]{32}$")

    private val foregroundPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val ok = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                 grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (!ok) {
            showSettingsSnack(R.string.need_location_permission)
            settings.sharingEnabled = false
            return@registerForActivityResult
        }
        requestBackgroundIfNeeded()
    }

    private val backgroundPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) showSettingsSnack(R.string.need_background_permission)
        startSharingIfReady()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsRepository(this)

        if (!settings.isConfigured()) {
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        bottomNav = findViewById(R.id.bottom_nav)
        val container = findViewById<View>(R.id.fragment_container)

        // Status-bar inset → padding-top контейнера; nav-bar inset → padding-bottom для bottomNav
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { _, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            container.updatePadding(top = sys.top)
            bottomNav.updatePadding(bottom = sys.bottom)
            insets
        }

        // Стартовый фрагмент
        if (savedInstanceState == null) {
            showFragment(MapFragment(), "map")
        }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_map -> { showFragment(MapFragment(), "map"); true }
                R.id.nav_path -> { showFragment(PathFragment(), "path"); true }
                R.id.nav_info -> { showFragment(InfoFragment(), "info"); true }
                R.id.nav_logout -> { confirmLogout(); false /* не переключаемся */ }
                else -> false
            }
        }

        // Поднять сервис, если разрешения уже даны; иначе попросить.
        if (hasLocationPermission()) startSharingIfReady() else requestForegroundPermsThenStart()

        checkPendingUpdate()
    }

    override fun onResume() {
        super.onResume()
        checkPendingUpdate()
    }

    private fun checkPendingUpdate() {
        if (!UpdateManager(this).hasPendingApk()) return
        Snackbar.make(findViewById(R.id.main), "Обновление WhereAmI готово", Snackbar.LENGTH_INDEFINITE)
            .setAction("Установить") { UpdateManager(this).launchInstall() }
            .show()
    }

    private fun showFragment(f: androidx.fragment.app.Fragment, tag: String) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, f, tag)
            .commit()
    }

    // ---------- Logout ----------
    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle(R.string.logout_confirm_title)
            .setMessage(R.string.logout_confirm_message)
            .setPositiveButton(R.string.logout_confirm_yes) { _, _ -> doLogout() }
            .setNegativeButton(R.string.logout_confirm_no, null)
            .show()
    }

    private fun doLogout() {
        val oldToken = settings.token
        val baseUrl = settings.serverUrl
        LocationService.stop(this)
        settings.sharingEnabled = false
        settings.clearIdentity()
        if (tokenRegex.matches(oldToken)) {
            // fire-and-forget: серверу мгновенный offline
            lifecycleScope.launch(Dispatchers.IO) {
                ApiClient(baseUrl).logout(oldToken)
            }
        }
        startActivity(Intent(this, AuthActivity::class.java))
        finish()
    }

    // ---------- Permissions ----------
    private fun requestForegroundPermsThenStart() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        val notGranted = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isEmpty()) requestBackgroundIfNeeded()
        else foregroundPermLauncher.launch(notGranted.toTypedArray())
    }

    private fun requestBackgroundIfNeeded() {
        val bg = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        if (bg == PackageManager.PERMISSION_GRANTED) { startSharingIfReady(); return }
        AlertDialog.Builder(this)
            .setTitle(R.string.need_background_permission)
            .setMessage(R.string.need_background_permission)
            .setPositiveButton(R.string.grant) { _, _ ->
                backgroundPermLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> startSharingIfReady() }
            .show()
    }

    private fun startSharingIfReady() {
        if (!settings.isConfigured()) return
        settings.sharingEnabled = true
        LocationService.start(this)
        maybeAskBatteryOptimization()
    }

    @SuppressLint("BatteryLife")
    private fun maybeAskBatteryOptimization() {
        if (settings.batteryOptAsked) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        settings.batteryOptAsked = true
        AlertDialog.Builder(this)
            .setTitle(R.string.batt_opt_title)
            .setMessage(R.string.batt_opt_message)
            .setPositiveButton(R.string.batt_opt_open) { _, _ ->
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                }.onFailure {
                    runCatching {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
            }
            .setNegativeButton(R.string.batt_opt_skip, null)
            .show()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun showSettingsSnack(msgRes: Int) {
        Snackbar.make(findViewById(R.id.main), msgRes, Snackbar.LENGTH_LONG)
            .setAction(R.string.open_settings) {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$packageName"))
                )
            }
            .show()
    }
}
