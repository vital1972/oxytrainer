package com.example.oxycaretrainer

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class ProgressAnalyticsTest {
    private fun session(vararg spo2: Int, pulse: Int = 70, start: Long = 0L) = TrainingSession(
        "s$start", start, start + spo2.size * 1000L, 10, 3, 85,
        spo2.mapIndexed { i, v -> TrainingSample(i.toLong(), v, pulse) }
    )

    @Test fun countsHypoxicDoseAndEpisodes() {
        val m = ProgressAnalytics.metrics(session(98, 95, 88, 84, 86, 92, 95, 96, 89, 94))
        assertEquals(4, m.secondsBelow90)
        assertEquals(1, m.secondsBelow85)
        assertEquals(2, m.episodes)
        assertEquals(84, m.nadirSpo2)
    }

    @Test fun recoveryTimeFromNadirToReturn() {
        // минимум 84 на t=3, возврат ≥94 на t=6 -> 3 с; второй эпизод 89 (t=8) -> 94 (t=9) -> 1 с; среднее 2
        val m = ProgressAnalytics.metrics(session(98, 95, 88, 84, 86, 92, 95, 96, 89, 94))
        assertEquals(2.0, m.meanRecoverySeconds!!, 1e-9)
    }

    @Test fun unfinishedEpisodeNotCountedInRecovery() {
        val m = ProgressAnalytics.metrics(session(98, 88, 85))
        assertEquals(1, m.episodes)
        assertNull(m.meanRecoverySeconds)
    }

    @Test fun noHypoxiaMeansNoDose() {
        val m = ProgressAnalytics.metrics(session(97, 98, 97))
        assertFalse(m.hasHypoxicDose); assertEquals(0, m.episodes)
    }

    @Test fun cautionFlagBelow80() {
        assertTrue(ProgressAnalytics.metrics(session(95, 79, 95)).cautionNadir)
        assertFalse(ProgressAnalytics.metrics(session(95, 80, 95)).cautionNadir)
    }

    @Test fun skipsSessionsWithoutSamples() {
        val empty = TrainingSession("e", 0, 1000, 10, 3, 85, emptyList())
        assertTrue(ProgressAnalytics.metrics(listOf(empty)).isEmpty())
    }

    @Test fun trendSlope() {
        val t = ProgressAnalytics.trend(listOf(80.0, 82.0, 84.0, 86.0))!!
        assertEquals(2.0, t.slopePerSession, 1e-9)
        assertNull(ProgressAnalytics.trend(listOf(1.0, 2.0)))
    }

    @Test fun groupsByWeekStartingMonday() {
        val cal = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 2, 12, 0, 0) } // понедельник
        val monday = cal.timeInMillis
        val sunday = monday + 6 * 24 * 3600_000L
        val nextMonday = monday + 7 * 24 * 3600_000L
        val ms = ProgressAnalytics.metrics(listOf(
            session(95, 96, start = monday), session(95, 96, start = sunday), session(95, 96, start = nextMonday)
        ))
        val weeks = ProgressAnalytics.weekly(ms)
        assertEquals(listOf(2, 1), weeks.map { it.sessions })
    }

    @Test fun restingPulseIsLowPercentile() {
        val s = TrainingSession("p", 0, 10000, 10, 3, 85, (0 until 10).map { TrainingSample(it.toLong(), 95, 60 + it * 10) })
        val m = ProgressAnalytics.metrics(s)
        assertEquals(60, m.restingPulse); assertEquals(150, m.peakPulse)
    }
}
