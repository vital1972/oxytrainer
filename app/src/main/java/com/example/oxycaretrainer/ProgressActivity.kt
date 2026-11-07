package com.example.oxycaretrainer

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Экран «Прогресс»: динамика показателей по завершённым сеансам. */
class ProgressActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        supportActionBar?.setDisplayHomeAsUpEnabled(true); supportActionBar?.title = "Прогресс"
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(24)) }
        setContentView(ScrollView(this).apply { addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) })
    }

    override fun onResume() {
        super.onResume()
        root.removeAllViews()
        val metrics = ProgressAnalytics.metrics(SessionRepository(this).all())
        if (metrics.isEmpty()) { body("Пока нет сеансов с данными датчика. Проведите тренировку с пульсоксиметром — здесь появится динамика."); science(); return }
        val weeks = ProgressAnalytics.weekly(metrics)
        val fmt = SimpleDateFormat("dd.MM", Locale.getDefault())

        heading("Итого")
        val totalMin = metrics.sumOf { it.durationSeconds } / 60
        val (okWeeks, ofWeeks) = ProgressAnalytics.adherence(weeks)
        body("Сеансов: ${metrics.size}, общее время: $totalMin мин.\nРегулярность: $okWeeks из $ofWeeks последних недель с ≥${ProgressAnalytics.TARGET_SESSIONS_PER_WEEK} сеансами.")
        metrics.lastOrNull { it.cautionNadir }?.let {
            warn("Минимум SpO₂ ниже ${ProgressAnalytics.CAUTION_NADIR}% был в сеансе ${fmt.format(Date(it.startTime))}. Не снижайте SpO₂ так глубоко без медицинского наблюдения.")
        }

        heading("Гипоксическая нагрузка (T90), мин за сеанс")
        caption("Время с SpO₂ ниже ${ProgressAnalytics.HYPOXIC_START}%.")
        chart(metrics.map { it.secondsBelow90 / 60.0 }, Color.rgb(156, 39, 176), unit = " мин", bars = true)

        heading("Минимальная SpO₂ (надир), %")
        caption("Рост при той же нагрузке может говорить о лучшей переносимости гипоксии; пунктир — ${ProgressAnalytics.CAUTION_NADIR}%.")
        chart(metrics.mapNotNull { it.nadirSpo2?.toDouble() }, Color.rgb(33, 150, 243), unit = "%", reference = ProgressAnalytics.CAUTION_NADIR.toDouble())
        trendLine(metrics.mapNotNull { it.nadirSpo2?.toDouble() }, "надира SpO₂", "п.п.")

        heading("Средняя SpO₂, %")
        chart(metrics.mapNotNull { it.meanSpo2 }, Color.rgb(0, 150, 136), unit = "%")

        heading("Восстановление SpO₂, сек")
        caption("Время от минимума эпизода до возврата к ${ProgressAnalytics.RECOVERED}%. Меньше — лучше.")
        val rec = metrics.mapNotNull { it.meanRecoverySeconds }
        chart(rec, Color.rgb(255, 152, 0), unit = " с")
        trendLine(rec, "времени восстановления", "с")

        heading("Пульс покоя (оценка), уд/мин")
        caption("10-й перцентиль пульса за сеанс. Снижение при регулярных тренировках — типичный признак адаптации.")
        val rp = metrics.mapNotNull { it.restingPulse?.toDouble() }
        chart(rp, Color.rgb(244, 67, 54), unit = "")
        trendLine(rp, "пульса покоя", "уд/мин")

        heading("Нагрузка по неделям, мин")
        caption("Недели с ${fmt.format(Date(weeks.first().weekStart))}.")
        chart(weeks.map { it.minutes }, Color.rgb(63, 81, 181), unit = " мин", bars = true)

        science()
    }

    private fun trendLine(values: List<Double>, what: String, unit: String) {
        val t = ProgressAnalytics.trend(values) ?: return
        val dir = if (Math.abs(t.slopePerSession) < 0.05) "почти не меняется" else if (t.slopePerSession > 0) "растёт" else "снижается"
        caption("Тренд $what: $dir (%+.2f %s за сеанс; %.1f → %.1f).".format(t.slopePerSession, unit, t.first, t.last))
    }

    private fun science() {
        heading("Научная основа")
        body("• Интервальная гипоксическая тренировка (IHT) — чередование коротких эпизодов дыхания гипоксической смесью с периодами восстановления; типичные курсы 2–4 недели, 3–5 раз в неделю (Navarrete-Opazo & Mitchell, 2014; Serebrovskaya, 2002).\n" +
            "• Время с SpO₂ < 90% (T90) используется как показатель гипоксической нагрузки (по аналогии с оценкой гипоксемии во сне и при высоте).\n" +
            "• Снижение пульса покоя и более быстрое восстановление SpO₂ — косвенные признаки адаптации; точной диагностики они не дают.\n" +
            "• Значения SpO₂ ниже 80% и плохое самочувствие — повод прекратить сеанс; при заболеваниях сердца, лёгких, беременности и приёме лекарств проконсультируйтесь с врачом.\n" +
            "Приложение — тренажёр и не заменяет медицинского наблюдения.")
    }

    private fun chart(values: List<Double>, color: Int, unit: String, bars: Boolean = false, reference: Double? = null) {
        root.addView(TrendChartView(this).apply { this.values = values; lineColor = color; asBars = bars; this.reference = reference; this.unit = unit },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private fun heading(t: String) = root.addView(TextView(this).apply { text = t; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(18), 0, dp(2)) })
    private fun caption(t: String) = root.addView(TextView(this).apply { text = t; textSize = 13f; setTextColor(Color.GRAY) })
    private fun body(t: String) = root.addView(TextView(this).apply { text = t; textSize = 15f; setPadding(0, dp(4), 0, dp(4)) })
    private fun warn(t: String) = root.addView(TextView(this).apply { text = t; textSize = 14f; setTextColor(Color.rgb(183, 28, 28)); setPadding(0, dp(6), 0, dp(6)) })
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
