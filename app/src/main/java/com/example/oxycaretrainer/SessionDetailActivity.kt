package com.example.oxycaretrainer

import android.os.Bundle
import android.widget.TextView
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*

class SessionDetailActivity : AppCompatActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); setContentView(R.layout.activity_session_detail)
        supportActionBar?.setDisplayHomeAsUpEnabled(true); supportActionBar?.title = "Результаты тренировки"
        val sessionId = intent.getStringExtra("id")
        val session = sessionId?.let { SessionRepository(this).get(it) }
        if (session == null) { finish(); return }
        val f = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
        findViewById<TextView>(R.id.tv_session_info).text = "Начало: ${f.format(Date(session.startTime))}\nОкончание: ${f.format(Date(session.endTime))}\nПродолжительность: ${session.durationSeconds / 60} мин ${session.durationSeconds % 60} сек\n\nSpO₂: средняя ${session.averageSpo2?.let { "%.1f".format(it) } ?: "—"}%, минимум ${session.minSpo2 ?: "—"}%, максимум ${session.maxSpo2 ?: "—"}%\nПульс: средний ${session.averagePulse?.let { "%.1f".format(it) } ?: "—"}, минимум ${session.minPulse ?: "—"}, максимум ${session.maxPulse ?: "—"} уд/мин"
        findViewById<SessionChartView>(R.id.chart).samples = session.samples
        findViewById<Button>(R.id.btn_delete_session).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Удалить сведения о сеансе?")
                .setMessage("Статистика и график будут удалены без возможности восстановления.")
                .setNegativeButton("Нет", null)
                .setPositiveButton("Удалить") { _, _ ->
                    if (sessionId != null && SessionRepository(this).delete(sessionId)) {
                        Toast.makeText(this, "Сеанс удалён", Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK)
                        finish()
                    } else Toast.makeText(this, "Не удалось удалить сеанс", Toast.LENGTH_LONG).show()
                }.show()
        }
    }
    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
