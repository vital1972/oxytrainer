package com.example.oxycaretrainer

import org.junit.Assert.*
import org.junit.Test

class TrainingProgramTest {
    private val day = 24L * 3600 * 1000

    private fun session(start: Long, durationSec: Long = 1800, planned: Int = 30) =
        TrainingSession("s$start", start, start + durationSec * 1000, planned, 5, 85, emptyList(), durationSec)

    @Test fun catalogIsValidAndUnique() {
        assertTrue(TrainingProgram.catalog.isNotEmpty())
        TrainingProgram.catalog.forEach { assertTrue("${it.id} invalid", it.isValid) }
        assertEquals(TrainingProgram.catalog.size, TrainingProgram.catalog.map { it.id }.toSet().size)
    }

    @Test fun cyclesFromSettings() {
        assertEquals(4, TrainingProgram.byId("health")!!.cycles)
        assertEquals(5, TrainingProgram.byId("cardio")!!.cycles)
    }

    @Test fun shortSessionsAreNotCounted() {
        assertTrue(ProgramProgress.counts(session(0, 1800, 30)))
        assertTrue(ProgramProgress.counts(session(0, 900, 30)))
        assertFalse(ProgramProgress.counts(session(0, 800, 30)))
    }

    @Test fun progressCountsWeekAndTotal() {
        val p = TrainingProgram.byId("health")!!
        val start = 1000L * day
        val sessions = listOf(session(start + day), session(start + 2 * day), session(start + 8 * day), session(start - day), session(start + 9 * day, 100))
        val s = ProgramProgress.status(p, start, sessions, start + 9 * day)
        assertEquals(2, s.week); assertEquals(1, s.doneThisWeek); assertEquals(3, s.doneTotal)
        assertFalse(s.finished)
    }

    @Test fun finishedAfterLastWeek() {
        val p = TrainingProgram.byId("altitude")!!
        val start = 1000L * day
        val s = ProgramProgress.status(p, start, emptyList(), start + 8 * day)
        assertTrue(s.finished); assertEquals(1, s.week)
    }
}
