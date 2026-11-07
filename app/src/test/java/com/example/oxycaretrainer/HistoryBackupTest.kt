package com.example.oxycaretrainer

import org.junit.Assert.*
import org.junit.Test

class HistoryBackupTest {
    private fun session(id: String) = TrainingSession(id, 1000, 61000, 10, 5, 85, listOf(TrainingSample(0, 95, 70), TrainingSample(1, 94, 71)), 60)

    @Test fun roundTrip() {
        val r = HistoryBackup.parse(HistoryBackup.toJson(listOf(session("a-1"), session("b_2"))))
        assertEquals(listOf("a-1", "b_2"), r.sessions.map { it.id })
        assertEquals(0, r.skipped)
        assertEquals(2, r.sessions[0].samples.size)
        assertEquals(60L, r.sessions[0].activeDurationSeconds)
    }

    @Test fun rejectsForeignJson() {
        assertThrows(IllegalArgumentException::class.java) { HistoryBackup.parse("{\"app\":\"other\"}") }
        assertThrows(IllegalArgumentException::class.java) { HistoryBackup.parse("not json") }
    }

    @Test fun skipsUnsafeIds() {
        val r = HistoryBackup.parse(HistoryBackup.toJson(listOf(session("../evil"), session("ok"))))
        assertEquals(listOf("ok"), r.sessions.map { it.id })
        assertEquals(1, r.skipped)
    }
}
