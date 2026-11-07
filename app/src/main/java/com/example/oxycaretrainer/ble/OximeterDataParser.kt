package com.example.oxycaretrainer.ble

import android.util.Log

data class OximeterReading(
    val timestamp: Long = System.currentTimeMillis(),
    val spo2: Int?,
    val pulseRate: Int?,
    val pleth: Int?,           // Плетизмограмма (сила пульсации)
    val bargraph: Int?,        // Качество сигнала (0-15)
    val signalStrength: Int?,  // Сила сигнала (0-8)
    val fingerPresent: Boolean,
    val isValid: Boolean,
    val pi: Double?,           // Рассчитанный PI
    val flags: Int = 0,
    val rawBytes: String = "",
    val allPackets: String = ""
)

class OximeterDataParser {

    companion object {
        private const val TAG = "📊Парсер"

        // Флаги из протокола
        private const val MASK_PROBE_UNPLUGGED = 0x10  // Бит 4
        private const val MASK_NO_SIGNAL = 0x20        // Бит 5
        private const val MASK_NO_FINGER = 0x10        // Бит 4 в Byte 2

        // Константы для валидации
        private const val INVALID_SPO2 = 0x7F
        private const val INVALID_PULSE = 0xFF
    }

    // Буферы для расчёта PI (анализ сигнала за несколько секунд)
    private val plethBuffer = mutableListOf<Int>()
    private val BUFFER_SIZE = 200

    private fun addSample(pleth: Int) {
        if (pleth >= 0) {
            plethBuffer.add(pleth)
            while (plethBuffer.size > BUFFER_SIZE) plethBuffer.removeAt(0)
        }
    }

    private fun resetBuffers() {
        if (plethBuffer.isNotEmpty()) {
            plethBuffer.clear()
            Log.d(TAG, "🔄 Буферы сброшены")
        }
    }

    // Оценка AC/DC по нормализованной плетизмограмме. Без исходной DC-составляющей
    // прибора это оценочный, а не калиброванный производителем PI.
    private fun calculatePi(): Double? {
        if (plethBuffer.size < BUFFER_SIZE) return null
        val dc = plethBuffer.average()
        if (dc <= 0.0) return null
        val ac = (plethBuffer.maxOrNull()!! - plethBuffer.minOrNull()!!) / 2.0
        return (ac / dc * 100.0).coerceIn(0.0, 100.0)
    }

    fun parse(data: ByteArray): OximeterReading {
        val hexAll = data.joinToString(" ") { "%02X".format(it) }

        if (data.size < 5) {
            return OximeterReading(
                spo2 = null, pulseRate = null, pleth = null, bargraph = null,
                signalStrength = null, fingerPresent = false, isValid = false, pi = null,
                rawBytes = hexAll.take(25), allPackets = hexAll
            )
        }

        // Распаковка по протоколу
        val byte0 = data[0].toInt() and 0xFF
        val byte1 = data[1].toInt() and 0xFF  // Pleth (0-100)
        val byte2 = data[2].toInt() and 0xFF
        val byte3 = data[3].toInt() and 0xFF
        val byte4 = data[4].toInt() and 0xFF

        // Byte 0: Signal strength (биты 0-3)
        val signalStrength = byte0 and 0x0F

        // Byte 0: Проверка датчика
        val probeUnplugged = (byte0 and MASK_PROBE_UNPLUGGED) != 0
        val noSignal = (byte0 and MASK_NO_SIGNAL) != 0

        // Byte 1: Pleth (плетизмограмма)
        val pleth = if (byte1 in 0..100) byte1 else null

        // Byte 2: Bargraph (биты 0-3) и No finger (бит 4)
        val bargraph = byte2 and 0x0F
        val noFinger = (byte2 and MASK_NO_FINGER) != 0

        // Byte 2 бит 6 + Byte 3 биты 0-6 = Pulse Rate
        val pulseBit7 = if ((byte2 and 0x40) != 0) 0x80 else 0
        val pulseLow7 = byte3 and 0x7F
        val pulseRateRaw = pulseBit7 or pulseLow7
        val pulseRate = if (pulseRateRaw != INVALID_PULSE && pulseRateRaw in 25..250) pulseRateRaw else null

        // Byte 4: SpO₂
        val spo2Raw = byte4 and 0x7F
        val spo2 = if (spo2Raw != INVALID_SPO2 && spo2Raw in 50..100) spo2Raw else null

        // Определяем наличие пальца
        val fingerPresent = !noFinger && !probeUnplugged && !noSignal && pleth != null

        // Добавляем образцы в буфер для расчёта PI
        if (fingerPresent && pleth != null) {
            addSample(pleth)
        } else {
            resetBuffers()
        }

        // Рассчитываем PI
        val pi = calculatePi()

        // Формируем строки для лога (без проблемных конструкций)
        val fingerStatus = if (noFinger) "НЕТ" else "ЕСТЬ"
        val probeStatus = if (probeUnplugged) "ОТКЛЮЧЕН" else "OK"
        val signalStatus = if (noSignal) "НЕТ СИГНАЛА" else "OK"

        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.d(TAG, "📊 ПРОТОКОЛ BCI v1.2:")
        Log.d(TAG, "   Byte0: $byte0 (Signal: $signalStrength, Probe: $probeStatus, Signal: $signalStatus)")
        Log.d(TAG, "   Byte1 (Pleth): $pleth")
        Log.d(TAG, "   Byte2: $byte2 (Bargraph: $bargraph, Палец: $fingerStatus)")
        Log.d(TAG, "   Пульс: $pulseRate BPM")
        Log.d(TAG, "   SpO₂: $spo2%")
        Log.d(TAG, "   PI (расчётный): ${pi?.let { String.format("%.1f", it) } ?: "--"}%")
        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        return OximeterReading(
            spo2 = if (fingerPresent) spo2 else null,
            pulseRate = if (fingerPresent) pulseRate else null,
            pleth = pleth,
            bargraph = bargraph,
            signalStrength = signalStrength,
            fingerPresent = fingerPresent,
            isValid = fingerPresent && spo2 != null && pulseRate != null,
            pi = pi,
            flags = byte0,
            rawBytes = data.take(5).joinToString(" ") { "%02X".format(it) },
            allPackets = hexAll
        )
    }
}
