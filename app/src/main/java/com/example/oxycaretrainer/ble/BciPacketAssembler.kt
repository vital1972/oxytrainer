package com.example.oxycaretrainer.ble

/** Reassembles five-byte BCI v1.2 frames from arbitrarily split BLE notifications. */
class BciPacketAssembler(private val onPacket: (ByteArray) -> Unit) {
    private val frame = ArrayList<Byte>(5)

    @Synchronized
    fun accept(chunk: ByteArray) {
        chunk.forEach { byte ->
            val isHeader = (byte.toInt() and 0x80) != 0
            if (isHeader) {
                frame.clear()
                frame.add(byte)
            } else if (frame.isNotEmpty()) {
                frame.add(byte)
                if (frame.size == 5) {
                    onPacket(frame.toByteArray())
                    frame.clear()
                }
            }
        }
    }

    @Synchronized fun reset() = frame.clear()
}
