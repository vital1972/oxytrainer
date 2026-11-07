package com.example.oxycaretrainer

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true); supportActionBar?.title = "Настройки тренировки"
        val total = findViewById<EditText>(R.id.et_total); val phase = findViewById<EditText>(R.id.et_phase); val low = findViewById<EditText>(R.id.et_low)
        AppSettings.load(this).also { total.setText(it.totalMinutes.toString()); phase.setText(it.phaseMinutes.toString()); low.setText(it.lowSpo2.toString()) }
        findViewById<Button>(R.id.btn_save).setOnClickListener {
            val t = total.text.toString().toIntOrNull(); val p = phase.text.toString().toIntOrNull(); val l = low.text.toString().toIntOrNull()
            when {
                t == null || t !in 1..240 -> total.error = "От 1 до 240 минут"
                p == null || p !in 1..60 || p > t -> phase.error = "От 1 до 60 минут, не больше общего времени"
                l == null || l !in 70..95 -> low.error = "От 70 до 95%"
                else -> { AppSettings.save(this, AppSettings(t, p, l)); Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show(); finish() }
            }
        }
        findViewById<Button>(R.id.btn_export_log).setOnClickListener {
            runCatching {
                val file = DiagnosticLogger.createExportFile(this)
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Журнал OxyCare Trainer")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                DiagnosticLogger.info("diagnostics_exported")
                startActivity(Intent.createChooser(share, "Отправить журнал диагностики"))
            }.onFailure {
                DiagnosticLogger.error("diagnostics_export_failed", it)
                Toast.makeText(this, "Не удалось подготовить журнал", Toast.LENGTH_LONG).show()
            }
        }
    }
    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
