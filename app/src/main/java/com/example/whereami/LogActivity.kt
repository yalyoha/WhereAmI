package com.example.whereami

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

/**
 * Экран «Полный лог» — показывает содержимое [FileLogger]. Открывается из
 * SettingsFragment. Читает лог заново при onResume и при клике «Обновить»
 * (не поллинг: раз в 60 сек прилетает по 1 строке, автообновление избыточно
 * и заставляет прыгать скролл, когда юзер читает старое).
 */
class LogActivity : AppCompatActivity() {

    private lateinit var textView: TextView
    private lateinit var metaView: TextView
    private lateinit var scroll:   ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)
        setSupportActionBar(findViewById<Toolbar>(R.id.log_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        textView = findViewById(R.id.log_text)
        metaView = findViewById(R.id.log_meta)
        scroll   = findViewById(R.id.log_scroll)
    }

    override fun onResume() {
        super.onResume()
        loadLog(scrollToBottom = true)
    }

    private fun loadLog(scrollToBottom: Boolean) {
        val text = FileLogger.dump()
        textView.text = if (text.isEmpty()) "(лог пуст)" else text
        val bytes  = text.toByteArray(Charsets.UTF_8).size
        val kb     = bytes / 1024
        val lines  = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1
        metaView.text = "$lines строк · $kb KB"
        if (scrollToBottom) scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_log, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home     -> { finish(); true }
        R.id.action_refresh   -> { loadLog(scrollToBottom = true); true }
        R.id.action_copy      -> { copyToClipboard(); true }
        R.id.action_share     -> { shareLog(); true }
        R.id.action_clear     -> { confirmClear(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun copyToClipboard() {
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("WhereAmI log", textView.text))
        Toast.makeText(this, getString(R.string.log_copied), Toast.LENGTH_SHORT).show()
    }

    private fun shareLog() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "WhereAmI log")
            putExtra(Intent.EXTRA_TEXT, textView.text.toString())
        }
        startActivity(Intent.createChooser(send, getString(R.string.log_share)))
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle(R.string.log_clear)
            .setMessage(R.string.log_clear_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                FileLogger.clear()
                loadLog(scrollToBottom = false)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
