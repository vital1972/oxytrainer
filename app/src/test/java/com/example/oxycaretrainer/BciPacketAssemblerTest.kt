package com.example.oxycaretrainer

import com.example.oxycaretrainer.ble.BciPacketAssembler
import org.junit.Assert.*
import org.junit.Test

class BciPacketAssemblerTest {
    @Test fun reassemblesSplitFrame() {
        val result = mutableListOf<ByteArray>(); val a = BciPacketAssembler { result += it }
        a.accept(byteArrayOf(0x80.toByte(), 10, 2)); a.accept(byteArrayOf(70, 98))
        assertEquals(1, result.size); assertArrayEquals(byteArrayOf(0x80.toByte(), 10, 2, 70, 98), result[0])
    }
    @Test fun extractsMultipleFramesAndIgnoresGarbage() {
        val result = mutableListOf<ByteArray>(); val a = BciPacketAssembler { result += it }
        a.accept(byteArrayOf(1, 2, 0x80.toByte(), 10, 2, 70, 98, 0x81.toByte(), 11, 3, 71, 97))
        assertEquals(2, result.size)
    }
    @Test fun newHeaderResynchronizesIncompleteFrame() {
        val result = mutableListOf<ByteArray>(); val a = BciPacketAssembler { result += it }
        a.accept(byteArrayOf(0x80.toByte(), 10, 0x81.toByte(), 11, 3, 71, 97))
        assertEquals(1, result.size); assertEquals(11, result[0][1].toInt())
    }
}
