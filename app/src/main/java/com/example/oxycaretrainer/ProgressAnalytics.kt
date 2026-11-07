package com.example.oxycaretrainer

import java.util.Calendar

/**
 * Показатели прогресса по завершённым сеансам.
 *
 * Допущения (см. также раздел «Научная основа» на экране прогресса):
 *  - один валидный отсчёт = одна секунда;
 *  - «гипоксический эпизод» начинается при SpO₂ < [HYPOXIC_START] и заканчивается при возврате к SpO₂ ≥ [RECOVERED];
 *  - T90 / T85 — время с SpO₂ ниже 90 % / 85 %: общепринятая метрика гипоксической нагрузки (по аналогии с T90 в исследованиях сна и гипоксемии);
 *  - показатели — ориентир для самонаблюдения, а не медицинский вывод.
 */
object ProgressAnalytics {
    const val HYPOXIC_START = 90
    const val RECOVERED = 94
    const val DEEP_HYPOXIA = 85
    /** Нижняя граница, ниже которой сеанс помечается как требующий осторожности. */
    const val CAUTION_NADIR = 80
    /** Типичная частота IHT-протоколов: 3–5 сеансов в неделю; минимум для оценки регулярности. */
    const val TARGET_SESSIONS_PER_WEEK = 3

    data class SessionMetrics(
        val id: String,
        val startTime: Long,
        val durationSeconds: Long,
        val meanSpo2: Double?,
        val nadirSpo2: Int?,
        val secondsBelow90: Int,
        val secondsBelow85: Int,
        val episodes: Int,
        /** Среднее время возврата SpO₂ ≥ 94 % от минимума эпизода, секунды; null если завершённых эпизодов нет. */
        val meanRecoverySeconds: Double?,
        /** Оценка пульса покоя — 10-й перцентиль пульса за сеанс. */
        val restingPulse: Int?,
        val peakPulse: Int?
    ) {
        val hasHypoxicDose get() = secondsBelow90 > 0
        val cautionNadir get() = nadirSpo2?.let { it < CAUTION_NADIR } ?: false
    }

    data class WeekStat(val weekStart: Long, val sessions: Int, val minutes: Double, val minutesBelow90: Double)

    data class Trend(val slopePerSession: Double, val first: Double, val last: Double)

    fun metrics(s: TrainingSession): SessionMetrics {
        val samples = s.samples
        var below90 = 0; var below85 = 0
        var episodes = 0
        val recoveries = ArrayList<Long>()
        var inEpisode = false
        var nadirValue = Int.MAX_VALUE; var nadirTime = 0L
        for (x in samples) {
            if (x.spo2 < HYPOXIC_START) below90++
            if (x.spo2 < DEEP_HYPOXIA) below85++
            if (!inEpisode && x.spo2 < HYPOXIC_START) {
                inEpisode = true; episodes++; nadirValue = x.spo2; nadirTime = x.elapsedSeconds
            } else if (inEpisode) {
                if (x.spo2 < nadirValue) { nadirValue = x.spo2; nadirTime = x.elapsedSeconds }
                if (x.spo2 >= RECOVERED) { recoveries.add(x.elapsedSeconds - nadirTime); inEpisode = false; nadirValue = Int.MAX_VALUE }
            }
        }
        val pulses = samples.map { it.pulse }.sorted()
        return SessionMetrics(
            id = s.id, startTime = s.startTime, durationSeconds = s.durationSeconds,
            meanSpo2 = s.averageSpo2, nadirSpo2 = s.minSpo2,
            secondsBelow90 = below90, secondsBelow85 = below85, episodes = episodes,
            meanRecoverySeconds = recoveries.takeIf { it.isNotEmpty() }?.average(),
            restingPulse = pulses.takeIf { it.isNotEmpty() }?.let { it[((it.size - 1) * 0.10).toInt()] },
            peakPulse = pulses.lastOrNull()
        )
    }

    /** Сеансы без отсчётов (тренировка без датчика) в аналитику не входят. */
    fun metrics(sessions: List<TrainingSession>): List<SessionMetrics> =
        sessions.filter { it.samples.isNotEmpty() }.map { metrics(it) }.sortedBy { it.startTime }

    /** Недели начинаются с понедельника (локальное время). */
    fun weekly(metrics: List<SessionMetrics>, cal: Calendar = Calendar.getInstance()): List<WeekStat> =
        metrics.groupBy { weekStart(it.startTime, cal) }.map { (week, list) ->
            WeekStat(week, list.size, list.sumOf { it.durationSeconds } / 60.0, list.sumOf { it.secondsBelow90 } / 60.0)
        }.sortedBy { it.weekStart }

    private fun weekStart(time: Long, cal: Calendar): Long {
        val c = cal.clone() as Calendar
        c.firstDayOfWeek = Calendar.MONDAY
        c.timeInMillis = time
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        val offset = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 // понедельник = 0
        c.add(Calendar.DAY_OF_MONTH, -offset)
        return c.timeInMillis
    }

    /** Наклон линейной регрессии по номеру сеанса; null если точек меньше 3. */
    fun trend(values: List<Double>): Trend? {
        if (values.size < 3) return null
        val n = values.size
        val xm = (n - 1) / 2.0; val ym = values.average()
        var num = 0.0; var den = 0.0
        values.forEachIndexed { i, v -> num += (i - xm) * (v - ym); den += (i - xm) * (i - xm) }
        return Trend(num / den, values.first(), values.last())
    }

    /** Доля из последних 4 недель, в которых выполнено не менее [TARGET_SESSIONS_PER_WEEK] сеансов. */
    fun adherence(weeks: List<WeekStat>, now: Long = System.currentTimeMillis(), cal: Calendar = Calendar.getInstance()): Pair<Int, Int> {
        val current = weekStart(now, cal)
        val byWeek = weeks.associateBy { it.weekStart }
        var ok = 0
        val c = cal.clone() as Calendar
        for (i in 0 until 4) {
            c.timeInMillis = current; c.add(Calendar.WEEK_OF_YEAR, -i)
            if ((byWeek[c.timeInMillis]?.sessions ?: 0) >= TARGET_SESSIONS_PER_WEEK) ok++
        }
        return ok to 4
    }
}
