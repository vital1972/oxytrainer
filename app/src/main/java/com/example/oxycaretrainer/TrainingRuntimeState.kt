package com.example.oxycaretrainer

enum class SensorStatus {
    SEARCHING,
    CONNECTING,
    CONNECTED,
    TIMER_ONLY,
    DISCONNECTED,
    SEARCH_FAILED
}

data class RecoveryInfo(
    val startTime: Long,
    val activeElapsedSeconds: Long,
    val sampleCount: Int
)

data class TrainingRuntimeState(
    val training: Boolean = false,
    val sensorStatus: SensorStatus = SensorStatus.SEARCHING,
    val spo2: Int? = null,
    val pulse: Int? = null,
    val fingerPresent: Boolean = true,
    val lowSpo2Alert: Boolean = false,
    val activeElapsedSeconds: Long = 0L,
    val phaseRemainingSeconds: Long = 0L,
    val plannedMinutes: Int = 60,
    val phaseMinutes: Int = 5,
    val currentHypoxic: Boolean = true,
    val awaitingPhaseConfirmation: Boolean = false,
    val recoverableSession: RecoveryInfo? = null
) {
    val connected: Boolean get() = sensorStatus == SensorStatus.CONNECTED
    val canStart: Boolean get() = connected || sensorStatus == SensorStatus.TIMER_ONLY
}

interface TrainingServiceListener {
    fun onTrainingStateChanged(state: TrainingRuntimeState)
    fun onTrainingSessionSaved(sessionId: String)
}
