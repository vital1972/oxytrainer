package com.example.oxycaretrainer

import com.example.oxycaretrainer.ble.OximeterDataParser
import org.junit.Assert.*
import org.junit.Test

class OximeterDataParserTest {
    @Test fun parsesValidReading() {
        val r = OximeterDataParser().parse(byteArrayOf(0x83.toByte(), 50, 8, 70, 98))
        assertTrue(r.isValid); assertEquals(98, r.spo2); assertEquals(70, r.pulseRate)
    }
    @Test fun rejectsNoFinger() {
        val r = OximeterDataParser().parse(byteArrayOf(0x83.toByte(), 50, 0x18, 70, 98.toByte()))
        assertFalse(r.fingerPresent); assertFalse(r.isValid); assertNull(r.spo2)
    }
    @Test fun rejectsImpossibleValues() {
        val r = OximeterDataParser().parse(byteArrayOf(0x83.toByte(), 50, 8, 1, 20))
        assertFalse(r.isValid); assertNull(r.spo2); assertNull(r.pulseRate)
    }
}
