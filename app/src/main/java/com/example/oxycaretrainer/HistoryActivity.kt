package com.example.oxycaretrainer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*

class HistoryActivity : AppCompatActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); setContentView(R.layout.activity_history)
        supportActionBar?.setDisplayHomeAsUpEnabled(true); supportActionBar?.title = "История тренировок"
    }
    override fun onResume() {
        super.onResume()
        val sessions = SessionRepository(this).all()
        findViewById<TextView>(R.id.tv_empty).visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
        val format = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_2, android.R.id.text1,
            sessions.map { "${format.format(Date(it.startTime))} — ${formatDuration(it.durationSeconds)}\nSpO₂: ${it.averageSpo2?.let { v -> "%.1f".format(v) } ?: "—"}% (${it.minSpo2 ?: "—"}–${it.maxSpo2 ?: "—"}), пульс: ${it.minPulse ?: "—"}–${it.maxPulse ?: "—"}" })
        findViewById<ListView>(R.id.list_history).apply { this.adapter = adapter; setOnItemClickListener { _, _, pos, _ -> startActivity(Intent(this@HistoryActivity, SessionDetailActivity::class.java).putExtra("id", sessions[pos].id)) } }
    }
    private fun formatDuration(s: Long) = "%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
