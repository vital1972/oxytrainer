package com.example.oxycaretrainer

import android.content.Context

/** Насколько параметры программы подтверждены источниками. */
enum class Evidence(val label: String) {
    SOURCE_BASED("Параметры взяты из опубликованных протоколов"),
    ADAPTED("Основано на опубликованных протоколах, упрощено под формат приложения"),
    EXPERT("Экспертное предложение, подтверждения в источниках нет")
}

/**
 * Готовая программа. Приложение чередует равные фазы (маска / без маски), поэтому программа
 * сводится к общему времени, длительности фазы и порогу SpO₂; частота и длина курса нужны для отслеживания выполнения.
 * Медицинские рекомендации не заменяет: см. [SAFETY_TEXT].
 */
data class TrainingProgram(
    val id: String,
    val title: String,
    val goal: String,
    val settings: AppSettings,
    val sessionsPerWeek: Int,
    val weeks: Int,
    val evidence: Evidence,
    val details: String
) {
    val cycles get() = settings.totalMinutes / (2 * settings.phaseMinutes)
    val totalSessions get() = sessionsPerWeek * weeks
    val isValid get() = settings.totalMinutes in 1..240 && settings.phaseMinutes in 1..60 &&
        settings.phaseMinutes <= settings.totalMinutes && settings.lowSpo2 in 70..95 &&
        sessionsPerWeek in 1..7 && weeks >= 1

    companion object {
        const val SAFETY_TEXT =
            "Перед стартом\n" +
            "• Гипоксическая тренировка не подходит при беременности, неконтролируемой гипертензии, ишемической болезни сердца, перенесённых инфаркте или инсульте, аритмиях, эпилепсии, тяжёлых заболеваниях лёгких, анемии, ретинопатии. При любом хроническом заболевании и приёме лекарств сначала проконсультируйтесь с врачом.\n" +
            "• В исследованиях IHHT проводили под медицинским наблюдением; данных о безопасности самостоятельных занятий мало.\n" +
            "• Прекратите сеанс, если SpO₂ падает ниже 80%, или при головокружении, боли в груди, нарушении координации, сильной одышке. Перед следующим циклом SpO₂ должен восстановиться примерно до 90–92% и выше (порог — экспертное предложение).\n" +
            "• Тренировка не гарантирует защиту от горной болезни.\n" +
            "• Приложение — тренажёр и не заменяет медицинского наблюдения."

        val catalog: List<TrainingProgram> = listOf(
            TrainingProgram(
                "gentle_start", "Мягкий старт", "Первое знакомство с методом",
                AppSettings(20, 2, 88), 3, 2, Evidence.EXPERT,
                "Короткие циклы 2+2 мин и повышенный порог предупреждения. Подходит, чтобы освоить приложение и оценить переносимость; в исследованиях такой схемы нет."
            ),
            TrainingProgram(
                "health", "Общее оздоровление", "Профилактика, снижение стресса, общий тонус",
                AppSettings(32, 4, 85), 3, 4, Evidence.ADAPTED,
                "Около 4 циклов гипоксии по 4–5 мин с восстановлением, 3 раза в неделю, 3–5 недель — близко к протоколам IHHT (Serebrovska 2019: 4×5 мин, Dudnik 2018 и Glazachev 2017: 5–7 циклов; по обзору Tessema 2022). В исследованиях восстановление проводили гипероксией, здесь — дыханием воздухом."
            ),
            TrainingProgram(
                "cardio", "Кардио-протокол (курс 15 сеансов)", "Сердечно-сосудистая адаптация, снижение пульса покоя",
                AppSettings(50, 5, 85), 3, 5, Evidence.ADAPTED,
                "5 циклов по 5 мин, 3 раза в неделю, 5 недель (15 сеансов) — по протоколам IHHT Dudnik 2018 и Glazachev 2017 (обзор Tessema 2022). Использовались под наблюдением врача."
            ),
            TrainingProgram(
                "athlete", "Спорт: базовый IHT", "Периодическое дополнение к тренировкам спортсмена",
                AppSettings(40, 10, 85), 2, 5, Evidence.ADAPTED,
                "Обзор по IHT/IHE для спортсменов: 30–45 мин в гипоксии (около 2500–3000 м) блоками 10–20 мин, 2 раза в неделю. Здесь — блоки по 10 мин в покое; в исследованиях нагрузку выполняли около анаэробного порога. Длина курса 4–6 недель — экспертная оценка. Эффект на аэробную и анаэробную работоспособность в исследованиях неоднозначен."
            ),
            TrainingProgram(
                "endurance", "Выносливость: длительная гипоксия", "Аэробная база, выносливость",
                AppSettings(120, 20, 85), 4, 3, Evidence.EXPERT,
                "Обзор по IHE упоминает около 3 часов покоя в гипоксии 4–5 раз в неделю (симулированные 3000–4000 м). Здесь доза уменьшена до 2 часов; срок курса 3–4 недели — экспертная оценка, источники не подтверждены."
            ),
            TrainingProgram(
                "altitude", "Подготовка к высокогорью", "Пред-акклиматизация перед походом",
                AppSettings(60, 10, 85), 7, 1, Evidence.EXPERT,
                "Опирается на исследование Treml 2020: 1 час в день гипоксии (FiO₂ 0,11) 7 дней подряд улучшал показатель акклиматизации, эффект сохранялся неделю. Здесь — 3 цикла по 10+10 мин: схема адаптирована, подтверждения в источниках нет. Начинайте за 1–3 недели до выезда. Защиту от горной болезни не гарантирует."
            )
        )

        fun byId(id: String?) = catalog.firstOrNull { it.id == id }
    }
}

/** Выбранная программа и дата её начала. */
object ProgramStore {
    private fun prefs(c: Context) = c.getSharedPreferences("program_state", Context.MODE_PRIVATE)
    fun current(c: Context): Pair<TrainingProgram, Long>? {
        val p = prefs(c)
        val program = TrainingProgram.byId(p.getString("id", null)) ?: return null
        return program to p.getLong("start", 0L)
    }
    fun select(c: Context, program: TrainingProgram, now: Long = System.currentTimeMillis()) {
        prefs(c).edit().putString("id", program.id).putLong("start", now).apply()
        AppSettings.save(c, program.settings)
    }
    fun clear(c: Context) { prefs(c).edit().clear().apply() }
    fun safetyAcknowledged(c: Context) = prefs(c).getBoolean("safety_ack_v1", false)
    fun acknowledgeSafety(c: Context) { prefs(c).edit().putBoolean("safety_ack_v1", true).apply() }
}

/** Выполнение выбранной программы. */
object ProgramProgress {
    private const val WEEK_MS = 7L * 24 * 3600 * 1000

    data class Status(val week: Int, val weeks: Int, val doneThisWeek: Int, val perWeek: Int, val doneTotal: Int, val planned: Int, val finished: Boolean)

    /** Сеанс засчитывается, если длился не менее половины запланированного времени. */
    fun counts(s: TrainingSession) = s.plannedMinutes > 0 && s.durationSeconds >= s.plannedMinutes * 30L

    fun status(program: TrainingProgram, start: Long, sessions: List<TrainingSession>, now: Long): Status {
        val counted = sessions.filter { it.startTime >= start && counts(it) }
        val elapsedWeeks = (((now - start).coerceAtLeast(0)) / WEEK_MS).toInt()
        val week = (elapsedWeeks + 1).coerceAtMost(program.weeks)
        val weekStart = start + elapsedWeeks.coerceAtMost(program.weeks - 1) * WEEK_MS
        val thisWeek = counted.count { it.startTime >= weekStart && it.startTime < weekStart + WEEK_MS }
        val finished = (now - start) >= program.weeks * WEEK_MS
        return Status(week, program.weeks, thisWeek, program.sessionsPerWeek, counted.size.coerceAtMost(program.totalSessions), program.totalSessions, finished)
    }
}
