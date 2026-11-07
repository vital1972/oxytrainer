package com.example.oxycaretrainer

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Готовые программы тренировок: выбор программы применяет её параметры к настройкам. */
class ProgramsActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        supportActionBar?.setDisplayHomeAsUpEnabled(true); supportActionBar?.title = "Программы тренировок"
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(24)) }
        setContentView(ScrollView(this).apply { addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) })
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        root.removeAllViews()
        text(TrainingProgram.SAFETY_TEXT, 13f, Color.rgb(183, 28, 28))

        val current = ProgramStore.current(this)
        if (current != null) {
            val (program, start) = current
            val s = ProgramProgress.status(program, start, SessionRepository(this).all(), System.currentTimeMillis())
            heading("Текущая программа: ${program.title}")
            text("Неделя ${s.week} из ${s.weeks}. На этой неделе: ${s.doneThisWeek} из ${s.perWeek}. Всего: ${s.doneTotal} из ${s.planned}." +
                if (s.finished) "\nКурс завершён по срокам — можно выбрать новую программу или повторить." else "", 15f)
            root.addView(Button(this).apply { text = "Снять программу"; setOnClickListener { ProgramStore.clear(this@ProgramsActivity); render() } })
        }

        heading("Доступные программы")
        TrainingProgram.catalog.forEach { addCard(it, current?.first?.id == it.id) }
        text("Метки достоверности показывают, насколько параметры подтверждены источниками. Это не врачебная рекомендация.", 12f, Color.GRAY)
    }

    private fun addCard(p: TrainingProgram, selected: Boolean) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(if (selected) Color.rgb(227, 242, 253) else Color.rgb(245, 245, 245))
        }
        fun add(t: String, size: Float, bold: Boolean = false, color: Int = Color.BLACK) = card.addView(TextView(this).apply {
            text = t; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(2), 0, dp(2))
        })
        add(p.title + if (selected) " ✓" else "", 17f, true)
        add(p.goal, 14f, color = Color.DKGRAY)
        add("${p.settings.totalMinutes} мин, фаза ${p.settings.phaseMinutes} мин (циклов: ${p.cycles}), порог SpO₂ ${p.settings.lowSpo2}%, ${p.sessionsPerWeek} раз(а) в неделю, ${p.weeks} нед.", 14f)
        add(p.evidence.label, 13f, true, if (p.evidence == Evidence.EXPERT) Color.rgb(230, 81, 0) else Color.rgb(46, 125, 50))
        add(p.details, 13f, color = Color.DKGRAY)
        card.addView(Button(this).apply { text = if (selected) "Начать заново" else "Выбрать программу"; setOnClickListener { choose(p) } })
        root.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
    }

    private fun choose(p: TrainingProgram) {
        if (ProgramStore.safetyAcknowledged(this)) { applyProgram(p); return }
        AlertDialog.Builder(this)
            .setTitle("Подтверждение")
            .setMessage(TrainingProgram.SAFETY_TEXT + "\n\nПодтверждаю, что прочитал(а) это, у меня нет противопоказаний и при сомнениях я консультируюсь с врачом.")
            .setPositiveButton("Подтверждаю") { _, _ -> ProgramStore.acknowledgeSafety(this); applyProgram(p) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun applyProgram(p: TrainingProgram) {
        ProgramStore.select(this, p)
        Toast.makeText(this, "Программа выбрана: параметры тренировки обновлены", Toast.LENGTH_LONG).show()
        render()
    }

    private fun heading(t: String) = root.addView(TextView(this).apply { text = t; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(18), 0, dp(2)) })
    private fun text(t: String, size: Float, color: Int = Color.BLACK) = root.addView(TextView(this).apply { text = t; textSize = size; setTextColor(color); setPadding(0, dp(4), 0, dp(4)) })
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
