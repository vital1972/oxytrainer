package com.example.oxycaretrainer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.oxycaretrainer.ble.BluetoothLeService
import com.example.oxycaretrainer.ble.OximeterDataParser
import com.example.oxycaretrainer.ble.OximeterReading
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

class TrainingService : Service() {
    inner class LocalBinder : Binder() {
        fun service(): TrainingService = this@TrainingService
    }

    private val binder = LocalBinder()
    private val listeners = CopyOnWriteArraySet<TrainingServiceListener>()
    private val handler = Handler(Looper.getMainLooper())
    private val parser = OximeterDataParser()
    private val tone by lazy { ToneGenerator(AudioManager.STREAM_ALARM, 100) }
    private lateinit var ble: BluetoothLeService
    private lateinit var activeStore: ActiveSessionStore

    private var state = TrainingRuntimeState()
    private var sessionId = ""
    private var sessionStartWall = 0L
    private var activeElapsedMs = 0L
    private var phaseElapsedMs = 0L
    private var lastTickRealtime = 0L
    private var lastSavedSecond = -1L
    private var lowSince = 0L
    private var lastLowAlarm = 0L
    private var samples = mutableListOf<TrainingSample>()
    private var foregroundStarted = false
    private var searching = false
    private var hadConnectedSensor = false
    private var currentLowSpo2 = 85

    override fun onCreate() {
        super.onCreate()
        DiagnosticLogger.info("training_service_create")
        activeStore = ActiveSessionStore(this)
        ble = BluetoothLeService(this)
        setupBle()
        createNotificationChannel()
        val checkpoint = activeStore.load()
        if (checkpoint != null) {
            state = state.copy(
                recoverableSession = RecoveryInfo(
                    checkpoint.startTime,
                    checkpoint.activeElapsedMs / 1_000L,
                    checkpoint.samples.size
                ),
                plannedMinutes = checkpoint.plannedMinutes,
                phaseMinutes = checkpoint.phaseMinutes
            )
            DiagnosticLogger.warn(
                "checkpoint_available",
                "id=${checkpoint.id} elapsed=${checkpoint.activeElapsedMs} samples=${checkpoint.samples.size}"
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TRAINING -> {
                startForegroundIfNeeded()
                startNewTraining()
            }
            ACTION_RESUME_TRAINING -> {
                startForegroundIfNeeded()
                resumeCheckpoint()
            }
            ACTION_STOP_TRAINING -> stopTraining(completed = false)
            null -> {
                if (!state.training && activeStore.load() != null) {
                    startForegroundIfNeeded()
                    resumeCheckpoint()
                }
            }
        }
        return START_STICKY
    }

    fun addListener(listener: TrainingServiceListener) {
        listeners += listener
        listener.onTrainingStateChanged(state)
    }

    fun removeListener(listener: TrainingServiceListener) {
        listeners -= listener
    }

    fun currentState(): TrainingRuntimeState = state

    fun isBluetoothEnabled(): Boolean = ble.isBluetoothEnabled()

    fun startSensorSearch() {
        if (searching || state.connected || !hasBluetoothPermissions()) return
        if (!ble.isBluetoothEnabled()) {
            updateState(state.copy(sensorStatus = SensorStatus.SEARCH_FAILED, spo2 = null, pulse = null))
            return
        }
        searching = true
        updateState(state.copy(sensorStatus = SensorStatus.SEARCHING, spo2 = null, pulse = null))
        DiagnosticLogger.info("ble_scan_start", "training=${state.training}")
        ble.startScan()
    }

    fun useTimerOnly() {
        searching = false
        ble.stopScan()
        updateState(state.copy(sensorStatus = SensorStatus.TIMER_ONLY, spo2 = null, pulse = null))
        checkpoint("timer_only_selected")
    }

    fun confirmPhase() {
        if (!state.training || !state.awaitingPhaseConfirmation) return
        handler.removeCallbacks(phaseAlarm)
        phaseElapsedMs = 0L
        lastTickRealtime = SystemClock.elapsedRealtime()
        updateState(state.copy(awaitingPhaseConfirmation = false))
        DiagnosticLogger.info("phase_confirmed", "hypoxic=${state.currentHypoxic}")
        checkpoint("phase_confirmed")
    }

    fun stopTraining(completed: Boolean = false) {
        if (!state.training) return
        handler.removeCallbacks(tick)
        handler.removeCallbacks(phaseAlarm)
        handler.removeCallbacks(connectionAlarm)
        handler.removeCallbacks(autoReconnect)
        saveSession(completed)
    }

    fun finishRecoveredSession() {
        val checkpoint = activeStore.load() ?: return
        val session = checkpoint.toSession(System.currentTimeMillis())
        SessionRepository(this).save(session)
        activeStore.clear()
        updateState(state.copy(recoverableSession = null))
        listeners.forEach { it.onTrainingSessionSaved(session.id) }
        DiagnosticLogger.info("recovered_session_finished", "id=${session.id}")
    }

    private fun setupBle() {
        ble.onDeviceFound = { device, rssi ->
            handler.post {
                searching = false
                updateState(state.copy(sensorStatus = SensorStatus.CONNECTING))
                DiagnosticLogger.info("ble_device_found", "address=${device.address} rssi=$rssi")
                ble.connect(device)
            }
        }
        ble.onConnectionStatus = { connected, message ->
            handler.post { handleConnectionStatus(connected, message) }
        }
        ble.onDataReceived = { bytes ->
            val reading = parser.parse(bytes)
            handler.post { handleReading(reading) }
        }
    }

    private fun handleConnectionStatus(connected: Boolean, message: String) {
        DiagnosticLogger.info("ble_status", "connected=$connected message=$message")
        if (connected) {
            searching = false
            hadConnectedSensor = true
            handler.removeCallbacks(connectionAlarm)
            updateState(state.copy(sensorStatus = SensorStatus.CONNECTED, spo2 = null, pulse = null))
            checkpoint("sensor_connected")
            return
        }
        when {
            message.contains("Подключение", true) || message.contains("настройка", true) ->
                updateState(state.copy(sensorStatus = SensorStatus.CONNECTING))
            message.contains("не найден", true) || message.contains("Ошибка поиска", true) -> {
                searching = false
                updateState(state.copy(sensorStatus = SensorStatus.SEARCH_FAILED, spo2 = null, pulse = null))
            }
            state.training && hadConnectedSensor -> onUnexpectedDisconnect(message)
            else -> updateState(state.copy(sensorStatus = SensorStatus.SEARCH_FAILED, spo2 = null, pulse = null))
        }
    }

    private fun onUnexpectedDisconnect(message: String) {
        searching = false
        updateState(state.copy(sensorStatus = SensorStatus.DISCONNECTED, spo2 = null, pulse = null))
        DiagnosticLogger.warn("sensor_disconnected_during_training", message)
        checkpoint("sensor_disconnected")
        handler.removeCallbacks(connectionAlarm)
        handler.post(connectionAlarm)
        handler.removeCallbacks(autoReconnect)
        handler.postDelayed(autoReconnect, 1_500L)
    }

    private fun handleReading(reading: OximeterReading) {
        if (!state.connected) return
        if (!reading.isValid) {
            updateState(state.copy(spo2 = null, pulse = null, fingerPresent = reading.fingerPresent))
            return
        }
        val spo2 = reading.spo2 ?: return
        val pulse = reading.pulseRate ?: return
        updateState(state.copy(spo2 = spo2, pulse = pulse, fingerPresent = true))
        if (!state.training) return

        val elapsedSecond = activeElapsedMs / 1_000L
        if (elapsedSecond != lastSavedSecond) {
            val sample = TrainingSample(elapsedSecond, spo2, pulse)
            samples += sample
            lastSavedSecond = elapsedSecond
            runCatching { activeStore.appendSample(sample) }
                .onFailure { DiagnosticLogger.error("sample_checkpoint_failed", it) }
        }
        val now = SystemClock.elapsedRealtime()
        if (spo2 < currentLowSpo2) {
            if (lowSince == 0L) lowSince = now
        } else {
            lowSince = 0L
            updateState(state.copy(lowSpo2Alert = false))
        }
        if (lowSince > 0L && now - lowSince >= 3_000L) {
            updateState(state.copy(lowSpo2Alert = true))
            if (now - lastLowAlarm >= 10_000L) {
                lastLowAlarm = now
                tone.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1_200)
                DiagnosticLogger.warn("low_spo2_alarm", "spo2=$spo2 threshold=$currentLowSpo2")
            }
        }
    }

    private fun startNewTraining() {
        if (state.training) return
        val settings = AppSettings.load(this)
        currentLowSpo2 = settings.lowSpo2
        sessionId = UUID.randomUUID().toString()
        sessionStartWall = System.currentTimeMillis()
        activeElapsedMs = 0L
        phaseElapsedMs = 0L
        lastSavedSecond = -1L
        lowSince = 0L
        lastLowAlarm = 0L
        samples = mutableListOf()
        state = state.copy(
            training = true,
            lowSpo2Alert = false,
            activeElapsedSeconds = 0L,
            phaseRemainingSeconds = settings.phaseMinutes * 60L,
            plannedMinutes = settings.totalMinutes,
            phaseMinutes = settings.phaseMinutes,
            currentHypoxic = true,
            awaitingPhaseConfirmation = true,
            recoverableSession = null
        )
        activeStore.begin(buildCheckpoint())
        lastTickRealtime = SystemClock.elapsedRealtime()
        DiagnosticLogger.info("training_started", "id=$sessionId timerOnly=${state.sensorStatus == SensorStatus.TIMER_ONLY}")
        notifyState()
        enterPhaseConfirmation(true)
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun resumeCheckpoint() {
        if (state.training) return
        val saved = activeStore.load() ?: run {
            stopForegroundAndSelf()
            return
        }
        sessionId = saved.id
        currentLowSpo2 = saved.lowSpo2
        sessionStartWall = saved.startTime
        activeElapsedMs = saved.activeElapsedMs
        phaseElapsedMs = saved.phaseElapsedMs
        samples = saved.samples.toMutableList()
        lastSavedSecond = samples.lastOrNull()?.elapsedSeconds ?: -1L

        if (!saved.awaitingPhaseConfirmation) {
            val downtime = (System.currentTimeMillis() - saved.lastCheckpointWall).coerceIn(0L, 60_000L)
            activeElapsedMs += downtime
            phaseElapsedMs += downtime
        }
        val restoredSensor = if (saved.timerOnlyMode) SensorStatus.TIMER_ONLY else SensorStatus.DISCONNECTED
        state = state.copy(
            training = true,
            sensorStatus = restoredSensor,
            spo2 = null,
            pulse = null,
            lowSpo2Alert = false,
            activeElapsedSeconds = activeElapsedMs / 1_000L,
            phaseRemainingSeconds = phaseRemaining(saved.phaseMinutes),
            plannedMinutes = saved.plannedMinutes,
            phaseMinutes = saved.phaseMinutes,
            currentHypoxic = saved.currentHypoxic,
            awaitingPhaseConfirmation = saved.awaitingPhaseConfirmation,
            recoverableSession = null
        )
        lastTickRealtime = SystemClock.elapsedRealtime()
        DiagnosticLogger.warn("training_resumed", "id=$sessionId elapsed=$activeElapsedMs samples=${samples.size}")
        notifyState()
        if (state.awaitingPhaseConfirmation) handler.post(phaseAlarm)
        if (state.sensorStatus != SensorStatus.TIMER_ONLY) handler.postDelayed(autoReconnect, 500L)
        handler.post(tick)
    }

    private fun enterPhaseConfirmation(hypoxic: Boolean) {
        phaseElapsedMs = 0L
        updateState(state.copy(currentHypoxic = hypoxic, awaitingPhaseConfirmation = true))
        DiagnosticLogger.info("phase_confirmation_requested", "hypoxic=$hypoxic")
        checkpoint("phase_change")
        handler.removeCallbacks(phaseAlarm)
        handler.post(phaseAlarm)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!state.training) return
            val now = SystemClock.elapsedRealtime()
            val delta = (now - lastTickRealtime).coerceAtLeast(0L)
            lastTickRealtime = now
            if (!state.awaitingPhaseConfirmation) {
                val totalMs = state.plannedMinutes * 60_000L
                val phaseMs = state.phaseMinutes * 60_000L
                val allowed = minOf(delta, totalMs - activeElapsedMs, phaseMs - phaseElapsedMs).coerceAtLeast(0L)
                activeElapsedMs += allowed
                phaseElapsedMs += allowed
                if (activeElapsedMs >= totalMs) {
                    stopTraining(completed = true)
                    return
                }
                if (phaseElapsedMs >= phaseMs) enterPhaseConfirmation(!state.currentHypoxic)
            }
            updateState(state.copy(
                activeElapsedSeconds = activeElapsedMs / 1_000L,
                phaseRemainingSeconds = phaseRemaining(state.phaseMinutes)
            ))
            checkpoint("tick")
            updateNotification()
            handler.postDelayed(this, 1_000L)
        }
    }

    private val phaseAlarm = object : Runnable {
        override fun run() {
            if (!state.training || !state.awaitingPhaseConfirmation) return
            tone.startTone(if (state.currentHypoxic) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_ACK, 800)
            handler.postDelayed(this, 2_500L)
        }
    }

    private val connectionAlarm = object : Runnable {
        override fun run() {
            if (!state.training || state.sensorStatus != SensorStatus.DISCONNECTED) return
            tone.startTone(ToneGenerator.TONE_SUP_ERROR, 900)
            handler.postDelayed(this, 4_000L)
        }
    }

    private val autoReconnect = Runnable {
        if (state.training && state.sensorStatus != SensorStatus.CONNECTED && state.sensorStatus != SensorStatus.TIMER_ONLY) {
            startSensorSearch()
        }
    }

    private fun saveSession(completed: Boolean) {
        val session = TrainingSession(
            id = sessionId,
            startTime = sessionStartWall,
            endTime = System.currentTimeMillis(),
            plannedMinutes = state.plannedMinutes,
            phaseMinutes = state.phaseMinutes,
            lowSpo2 = currentLowSpo2,
            samples = samples.toList(),
            activeDurationSeconds = activeElapsedMs / 1_000L
        )
        SessionRepository(this).save(session)
        activeStore.clear()
        DiagnosticLogger.info("training_saved", "id=${session.id} completed=$completed samples=${samples.size}")
        state = state.copy(
            training = false,
            lowSpo2Alert = false,
            activeElapsedSeconds = 0L,
            phaseRemainingSeconds = state.phaseMinutes * 60L,
            awaitingPhaseConfirmation = false,
            recoverableSession = null
        )
        notifyState()
        listeners.forEach { it.onTrainingSessionSaved(session.id) }
        tone.startTone(ToneGenerator.TONE_PROP_ACK, 1_200)
        stopForegroundAndSelf()
    }

    private fun checkpoint(reason: String) {
        if (!state.training || sessionId.isBlank()) return
        activeStore.checkpoint(buildCheckpoint())
        if (reason != "tick") DiagnosticLogger.info("checkpoint_saved", "reason=$reason elapsed=$activeElapsedMs")
    }

    private fun buildCheckpoint() = ActiveTrainingCheckpoint(
        id = sessionId,
        startTime = sessionStartWall,
        lastCheckpointWall = System.currentTimeMillis(),
        plannedMinutes = state.plannedMinutes,
        phaseMinutes = state.phaseMinutes,
        lowSpo2 = currentLowSpo2,
        activeElapsedMs = activeElapsedMs,
        phaseElapsedMs = phaseElapsedMs,
        currentHypoxic = state.currentHypoxic,
        awaitingPhaseConfirmation = state.awaitingPhaseConfirmation,
        timerOnlyMode = state.sensorStatus == SensorStatus.TIMER_ONLY,
        sensorDisconnected = state.sensorStatus == SensorStatus.DISCONNECTED,
        samples = emptyList()
    )

    private fun ActiveTrainingCheckpoint.toSession(endTime: Long) = TrainingSession(
        id = id,
        startTime = startTime,
        endTime = endTime,
        plannedMinutes = plannedMinutes,
        phaseMinutes = phaseMinutes,
        lowSpo2 = lowSpo2,
        samples = samples,
        activeDurationSeconds = activeElapsedMs / 1_000L
    )

    private fun phaseRemaining(phaseMinutes: Int): Long {
        val totalRemaining = state.plannedMinutes * 60_000L - activeElapsedMs
        val phaseRemaining = phaseMinutes * 60_000L - phaseElapsedMs
        return minOf(totalRemaining, phaseRemaining).coerceAtLeast(0L) / 1_000L
    }

    private fun updateState(newState: TrainingRuntimeState) {
        state = newState
        notifyState()
    }

    private fun notifyState() {
        listeners.forEach { it.onTrainingStateChanged(state) }
    }

    private fun startForegroundIfNeeded() {
        if (foregroundStarted) return
        startForeground(NOTIFICATION_ID, buildNotification())
        foregroundStarted = true
    }

    private fun updateNotification() {
        if (foregroundStarted) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_timer)
        .setContentTitle("Гипоксическая тренировка")
        .setContentText(if (state.training) "Прошло ${formatDuration(activeElapsedMs / 1_000L)}" else "Подготовка тренировки")
        .setContentIntent(PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ))
        .setOngoing(state.training)
        .setOnlyAlertOnce(true)
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Активная тренировка", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun stopForegroundAndSelf() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        foregroundStarted = false
        stopSelf()
    }

    private fun hasBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun formatDuration(seconds: Long): String = if (seconds >= 3_600L) {
        "%02d:%02d:%02d".format(seconds / 3_600L, seconds / 60L % 60L, seconds % 60L)
    } else {
        "%02d:%02d".format(seconds / 60L, seconds % 60L)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        checkpoint("task_removed")
        DiagnosticLogger.warn("task_removed", "training=${state.training}")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        checkpoint("service_destroy")
        handler.removeCallbacksAndMessages(null)
        ble.disconnect()
        tone.release()
        DiagnosticLogger.warn("training_service_destroy", "training=${state.training}")
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_TRAINING = "com.example.oxycaretrainer.START_TRAINING"
        const val ACTION_RESUME_TRAINING = "com.example.oxycaretrainer.RESUME_TRAINING"
        const val ACTION_STOP_TRAINING = "com.example.oxycaretrainer.STOP_TRAINING"
        private const val CHANNEL_ID = "active_training"
        private const val NOTIFICATION_ID = 41
    }
}
