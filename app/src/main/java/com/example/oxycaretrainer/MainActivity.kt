package com.example.oxycaretrainer

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity(), TrainingServiceListener {
    private lateinit var spo2Card: MaterialCardView
    private lateinit var pulseCard: MaterialCardView
    private lateinit var elapsedCard: MaterialCardView
    private lateinit var phaseCard: MaterialCardView
    private lateinit var spo2View: TextView
    private lateinit var pulseView: TextView
    private lateinit var spo2Note: TextView
    private lateinit var pulseNote: TextView
    private lateinit var elapsedView: TextView
    private lateinit var elapsedNote: TextView
    private lateinit var phaseView: TextView
    private lateinit var phaseTimer: TextView
    private lateinit var elapsedIcon: ImageView
    private lateinit var navigationBar: View
    private lateinit var hintView: TextView

    private var service: TrainingService? = null
    private var bound = false
    private var phaseBlink: ObjectAnimator? = null
    private var sensorDialog: AlertDialog? = null
    private var recoveryDialogShown = false
    private var lastStatus: SensorStatus? = null
    private var pendingTrainingStart = false
    private var currentState = TrainingRuntimeState()

    private val bluetoothPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private val enableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        service?.startSensorSearch()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as TrainingService.LocalBinder).service()
            bound = true
            service?.addListener(this@MainActivity)
            DiagnosticLogger.info("main_service_bound")
            requestPermissionsAndSearch()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            DiagnosticLogger.warn("main_service_disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupClicks()
        setupBackNavigation()
        val settings = AppSettings.load(this)
        render(TrainingRuntimeState(plannedMinutes = settings.totalMinutes, phaseMinutes = settings.phaseMinutes))
        DiagnosticLogger.info("main_create", "savedState=${savedInstanceState != null}")
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, TrainingService::class.java), connection, Context.BIND_AUTO_CREATE)
        DiagnosticLogger.info("main_start")
    }

    override fun onStop() {
        if (bound) {
            service?.removeListener(this)
            unbindService(connection)
            bound = false
            service = null
        }
        DiagnosticLogger.info("main_stop", "training=${currentState.training}")
        super.onStop()
    }

    private fun bindViews() {
        spo2Card = findViewById(R.id.spo2_card); pulseCard = findViewById(R.id.pulse_card)
        elapsedCard = findViewById(R.id.elapsed_card); phaseCard = findViewById(R.id.phase_card)
        spo2View = findViewById(R.id.tv_spo2); pulseView = findViewById(R.id.tv_pulse)
        spo2Note = findViewById(R.id.tv_spo2_note); pulseNote = findViewById(R.id.tv_pulse_note)
        elapsedView = findViewById(R.id.tv_total_timer); elapsedNote = findViewById(R.id.tv_total_note)
        phaseView = findViewById(R.id.tv_phase); phaseTimer = findViewById(R.id.tv_phase_timer)
        elapsedIcon = findViewById(R.id.iv_elapsed_icon); navigationBar = findViewById(R.id.navigation_bar)
        hintView = findViewById(R.id.tv_accessibility_hint)
    }

    private fun setupClicks() {
        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<ImageButton>(R.id.btn_history).setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        spo2Card.setOnClickListener { reconnectSensor() }; pulseCard.setOnClickListener { reconnectSensor() }
        elapsedCard.setOnClickListener {
            if (!currentState.training) {
                if (currentState.canStart) requestNotificationAndStart()
                else Toast.makeText(this, "Дождитесь датчика или выберите режим без датчика", Toast.LENGTH_LONG).show()
            }
        }
        elapsedCard.setOnLongClickListener {
            if (currentState.training) { showStopConfirmation(); true } else false
        }
        phaseCard.setOnClickListener {
            if (currentState.training && currentState.awaitingPhaseConfirmation) service?.confirmPhase()
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentState.training) showStopConfirmation() else {
                    isEnabled = false; onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun requestPermissionsAndSearch() {
        val state = service?.currentState() ?: return
        if (state.training || state.recoverableSession != null || state.connected) return
        if (hasBluetoothPermissions()) service?.startSensorSearch()
        else ActivityCompat.requestPermissions(this, bluetoothPermissions, REQUEST_BLUETOOTH)
    }

    private fun reconnectSensor() {
        if (currentState.connected || currentState.sensorStatus == SensorStatus.SEARCHING || currentState.sensorStatus == SensorStatus.CONNECTING) return
        if (!hasBluetoothPermissions()) ActivityCompat.requestPermissions(this, bluetoothPermissions, REQUEST_BLUETOOTH)
        else if (service?.isBluetoothEnabled() == false) enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        else service?.startSensorSearch()
    }

    private fun requestNotificationAndStart() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingTrainingStart = true
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        } else startTrainingService(TrainingService.ACTION_START_TRAINING)
    }

    private fun startTrainingService(action: String) {
        ContextCompat.startForegroundService(this, Intent(this, TrainingService::class.java).setAction(action))
    }

    override fun onTrainingStateChanged(state: TrainingRuntimeState) {
        runOnUiThread {
            currentState = state
            render(state)
            state.recoverableSession?.let { showRecoveryDialog(it) }
            if (state.sensorStatus == SensorStatus.SEARCH_FAILED && lastStatus != SensorStatus.SEARCH_FAILED) showSensorChoiceDialog()
            lastStatus = state.sensorStatus
        }
    }

    override fun onTrainingSessionSaved(sessionId: String) {
        runOnUiThread {
            Toast.makeText(this, "Сеанс сохранён", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SessionDetailActivity::class.java).putExtra("id", sessionId))
        }
    }

    private fun showRecoveryDialog(info: RecoveryInfo) {
        if (recoveryDialogShown || isFinishing) return
        recoveryDialogShown = true
        val started = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(info.startTime))
        AlertDialog.Builder(this)
            .setTitle("Найдена незавершённая тренировка")
            .setMessage("Начало: $started\nСохранено времени: ${formatDuration(info.activeElapsedSeconds)}\nИзмерений: ${info.sampleCount}")
            .setPositiveButton("Продолжить") { _, _ -> startTrainingService(TrainingService.ACTION_RESUME_TRAINING) }
            .setNegativeButton("Завершить и сохранить") { _, _ -> service?.finishRecoveredSession() }
            .setCancelable(false).show()
    }

    private fun showSensorChoiceDialog() {
        if (isFinishing || sensorDialog?.isShowing == true) return
        sensorDialog = AlertDialog.Builder(this)
            .setTitle("Пульсоксиметр не подключён")
            .setMessage(if (currentState.training) "Тренировка продолжается. Повторите подключение или работайте дальше только с таймером."
                else "Проверьте датчик и Bluetooth. Приложение также можно использовать только как таймер.")
            .setPositiveButton("Повторить поиск") { _, _ -> reconnectSensor() }
            .setNegativeButton("Без датчика") { _, _ -> service?.useTimerOnly() }
            .create()
        sensorDialog?.show()
    }

    private fun showStopConfirmation() {
        AlertDialog.Builder(this).setTitle("Завершить тренировку?")
            .setMessage("Текущий сеанс и уже полученные измерения будут сохранены.")
            .setNegativeButton("Нет", null)
            .setPositiveButton("Да") { _, _ -> service?.stopTraining(completed = false) }.show()
    }

    private fun render(state: TrainingRuntimeState) {
        navigationBar.visibility = if (state.training) View.GONE else View.VISIBLE
        hintView.visibility = if (state.training) View.GONE else View.VISIBLE
        if (state.training) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        renderSensor(state)
        if (state.training) {
            elapsedIcon.setImageResource(R.drawable.ic_timer)
            elapsedView.text = formatDuration(state.activeElapsedSeconds)
            elapsedNote.text = "из ${state.plannedMinutes} минут · удерживайте для завершения"
            if (state.awaitingPhaseConfirmation) {
                phaseView.text = if (state.currentHypoxic) "НАДЕНЬТЕ\nМАСКУ" else "СНИМИТЕ\nМАСКУ"
                phaseTimer.text = "нажмите для подтверждения"; startPhaseBlink()
            } else {
                stopPhaseBlink(); phaseView.text = formatDuration(state.phaseRemainingSeconds)
                phaseTimer.text = if (state.currentHypoxic) "маска надета" else "маска снята"
            }
        } else {
            stopPhaseBlink()
            val settings = AppSettings.load(this)
            elapsedIcon.setImageResource(R.drawable.ic_play); elapsedView.text = "НАЧАТЬ"
            elapsedNote.text = "${settings.totalMinutes} минут"; phaseView.text = "${settings.phaseMinutes} мин"
            phaseTimer.text = "первая фаза — с маской"
        }
        phaseCard.setCardBackgroundColor(color(if (state.currentHypoxic) R.color.card_mask_on else R.color.card_mask_off))
    }

    private fun renderSensor(state: TrainingRuntimeState) {
        when (state.sensorStatus) {
            SensorStatus.CONNECTED -> {
                spo2Card.setCardBackgroundColor(color(if (state.lowSpo2Alert) R.color.card_low_spo2 else R.color.card_spo2))
                pulseCard.setCardBackgroundColor(color(R.color.card_pulse))
                spo2View.text = state.spo2?.let { "$it%" } ?: "—"; pulseView.text = state.pulse?.toString() ?: "—"
                spo2Note.text = when { state.lowSpo2Alert -> "Слишком низкая!"; !state.fingerPresent -> "нет пальца"; else -> "SpO₂" }
                pulseNote.text = if (state.pulse == null) "ожидание данных" else "уд/мин"
            }
            SensorStatus.SEARCHING, SensorStatus.CONNECTING -> {
                setSensorColors(R.color.card_searching); spo2View.text = "Поиск…"; pulseView.text = "Поиск…"
                spo2Note.text = if (state.sensorStatus == SensorStatus.CONNECTING) "подключение" else "пульсоксиметра"
                pulseNote.text = spo2Note.text
            }
            SensorStatus.TIMER_ONLY -> {
                setSensorColors(R.color.card_unavailable); spo2View.text = "—"; pulseView.text = "—"
                spo2Note.text = "без датчика"; pulseNote.text = "без датчика"
            }
            SensorStatus.DISCONNECTED, SensorStatus.SEARCH_FAILED -> {
                setSensorColors(R.color.card_disconnected); spo2View.text = "—"; pulseView.text = "—"
                spo2Note.text = "нажмите для поиска"; pulseNote.text = "нажмите для поиска"
            }
        }
    }

    private fun setSensorColors(colorRes: Int) {
        spo2Card.setCardBackgroundColor(color(colorRes)); pulseCard.setCardBackgroundColor(color(colorRes))
    }

    private fun startPhaseBlink() {
        if (phaseBlink?.isRunning == true) return
        phaseBlink = ObjectAnimator.ofFloat(phaseCard, "alpha", 1f, 0.42f, 1f).apply {
            duration = 900L; repeatCount = ValueAnimator.INFINITE; start()
        }
    }
    private fun stopPhaseBlink() { phaseBlink?.cancel(); phaseBlink = null; phaseCard.alpha = 1f }
    private fun hasBluetoothPermissions() = bluetoothPermissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        when (requestCode) {
            REQUEST_BLUETOOTH -> {
                if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) {
                    service?.startSensorSearch()
                } else {
                    service?.useTimerOnly()
                    Toast.makeText(this, "Bluetooth недоступен. Можно использовать таймер без датчика.", Toast.LENGTH_LONG).show()
                }
            }
            REQUEST_NOTIFICATIONS -> {
                if (pendingTrainingStart) {
                    pendingTrainingStart = false
                    startTrainingService(TrainingService.ACTION_START_TRAINING)
                }
            }
        }
    }

    private fun color(colorRes: Int) = ContextCompat.getColor(this, colorRes)
    private fun formatDuration(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0L)
        return if (safe >= 3_600L) "%02d:%02d:%02d".format(safe / 3_600L, safe / 60L % 60L, safe % 60L)
        else "%02d:%02d".format(safe / 60L, safe % 60L)
    }

    override fun onDestroy() {
        stopPhaseBlink()
        DiagnosticLogger.info("main_destroy", "changingConfig=$isChangingConfigurations training=${currentState.training}")
        super.onDestroy()
    }

    companion object { private const val REQUEST_BLUETOOTH = 10; private const val REQUEST_NOTIFICATIONS = 11 }
}
