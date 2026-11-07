package com.example.oxycaretrainer

import android.content.Intent
import android.os.Bundle
import android.widget.*
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider

class SettingsActivity : AppCompatActivity() {
    private val exportHistory = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { writeHistory(it) } }
    private val importHistory = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { readHistory(it) } }

    private fun writeHistory(uri: Uri) {
        runCatching {
            val sessions = SessionRepository(this).all()
            contentResolver.openOutputStream(uri, "wt")!!.use { it.write(HistoryBackup.toJson(sessions).toByteArray(Charsets.UTF_8)) }
            Toast.makeText(this, "Сохранено сеансов: ${sessions.size}", Toast.LENGTH_LONG).show()
        }.onFailure {
            DiagnosticLogger.error("history_export_failed", it)
            Toast.makeText(this, "Не удалось сохранить файл", Toast.LENGTH_LONG).show()
        }
    }

    private fun readHistory(uri: Uri) {
        runCatching {
            val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            val result = HistoryBackup.parse(text)
            val saved = SessionRepository(this).importAll(result.sessions)
            val skipped = result.skipped + (result.sessions.size - saved)
            Toast.makeText(this, "Загружено сеансов: $saved" + if (skipped > 0) ", пропущено: $skipped" else "", Toast.LENGTH_LONG).show()
        }.onFailure {
            DiagnosticLogger.error("history_import_failed", it)
            Toast.makeText(this, it.message ?: "Не удалось загрузить файл", Toast.LENGTH_LONG).show()
        }
    }

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
        findViewById<Button>(R.id.btn_programs).setOnClickListener { startActivity(Intent(this, ProgramsActivity::class.java)) }
        findViewById<Button>(R.id.btn_updates).setOnClickListener { startActivity(Intent(this, UpdateActivity::class.java)) }
        findViewById<Button>(R.id.btn_export_history).setOnClickListener { exportHistory.launch("oxycare-history.json") }
        findViewById<Button>(R.id.btn_import_history).setOnClickListener { importHistory.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
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
