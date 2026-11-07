package com.example.oxycaretrainer.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import java.util.UUID

class BluetoothLeService(private val context: Context) {
    companion object {
        private val SERVICE_UUIDS = listOf(
            UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"),
            UUID.fromString("49535343-FE7D-4AE5-8FA9-9FAFD205E455"))
        private val RX_UUIDS = setOf(
            UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"),
            UUID.fromString("49535343-1E4D-4BD9-BA61-23C647249616"))
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }

    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var ready = false
    private val assembler = BciPacketAssembler { onDataReceived?.invoke(it) }
    var onDeviceFound: ((BluetoothDevice, Int) -> Unit)? = null
    var onDataReceived: ((ByteArray) -> Unit)? = null
    var onConnectionStatus: ((Boolean, String) -> Unit)? = null

    fun isBluetoothEnabled() = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!isBluetoothEnabled()) return status(false, "Включите Bluetooth")
        if (!canScan()) return status(false, "Нет разрешения Bluetooth")
        if (scanning) return
        scanning = true
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        adapter?.bluetoothLeScanner?.startScan(emptyList<ScanFilter>(), settings, scanCallback)
        handler.postDelayed(scanTimeout, 10_000)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!scanning) return
        if (canScan()) adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
        handler.removeCallbacks(scanTimeout)
    }

    private val scanTimeout = Runnable { stopScan(); if (gatt == null) status(false, "Пульсоксиметр не найден") }
    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(type: Int, result: ScanResult) {
            val advertised = result.scanRecord?.serviceUuids.orEmpty().map { it.uuid }
            val name = result.device.name.orEmpty()
            if (advertised.any { it in SERVICE_UUIDS } || listOf("OxyCare", "Berry", "BM1000", "Oximeter", "SpO2").any { name.contains(it, true) }) {
                stopScan(); onDeviceFound?.invoke(result.device, result.rssi)
            }
        }
        override fun onScanFailed(errorCode: Int) { scanning = false; status(false, "Ошибка поиска Bluetooth: $errorCode") }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (!canConnect()) return status(false, "Нет разрешения на подключение")
        closeGatt()
        status(false, "Подключение к ${device.name ?: "пульсоксиметру"}…")
        gatt = if (Build.VERSION.SDK_INT >= 23) device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        else device.connectGatt(context, false, callback)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() { handler.removeCallbacksAndMessages(null); stopScan(); gatt?.disconnect(); closeGatt() }

    @SuppressLint("MissingPermission")
    private fun closeGatt() { gatt?.close(); gatt = null; ready = false; assembler.reset() }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(current: BluetoothGatt, code: Int, state: Int) {
            if (code != BluetoothGatt.GATT_SUCCESS) {
                current.close(); if (gatt === current) gatt = null
                return status(false, "Ошибка Bluetooth GATT: $code")
            }
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> { status(false, "Подключено, настройка датчика…"); if (!current.discoverServices()) status(false, "Не удалось получить сервисы") }
                BluetoothProfile.STATE_DISCONNECTED -> { current.close(); if (gatt === current) gatt = null; ready = false; assembler.reset(); status(false, "Пульсоксиметр отключён") }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(current: BluetoothGatt, code: Int) {
            if (code != BluetoothGatt.GATT_SUCCESS) return status(false, "Ошибка поиска сервисов: $code")
            val characteristic = current.services.asSequence().filter { it.uuid in SERVICE_UUIDS }
                .flatMap { it.characteristics.asSequence() }
                .firstOrNull { it.uuid in RX_UUIDS && it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 }
                ?: return status(false, "Характеристика данных пульсоксиметра не найдена")
            if (!current.setCharacteristicNotification(characteristic, true)) return status(false, "Не удалось включить передачу данных")
            val descriptor = characteristic.getDescriptor(CCCD) ?: return status(false, "CCCD-дескриптор не найден")
            if (Build.VERSION.SDK_INT >= 33) current.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                current.writeDescriptor(descriptor)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, code: Int) {
            ready = code == BluetoothGatt.GATT_SUCCESS
            status(ready, if (ready) "Пульсоксиметр готов" else "Ошибка включения данных: $code")
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val value = characteristic.value
            if (ready && characteristic.uuid in RX_UUIDS) assembler.accept(value)
        }
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (ready && characteristic.uuid in RX_UUIDS) assembler.accept(value)
        }
    }

    private fun canScan() = Build.VERSION.SDK_INT < 31 || ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    private fun canConnect() = Build.VERSION.SDK_INT < 31 || ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    private fun status(connected: Boolean, message: String) {
        onConnectionStatus?.invoke(connected, message)
    }
}
