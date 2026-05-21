package com.example.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.example.common.BleConstants
import com.example.domain.ble.BleConnectionState
import com.example.domain.ble.BleRepository
import com.example.entity.SensorStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

@SuppressLint("MissingPermission")
class BleRepositoryImpl(
    context: Context
) : BleRepository {

    private val tag = "BLE"
    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _connectionState =
        MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)
    override val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _sensorData =
        MutableSharedFlow<SensorStatus>(replay = 1, extraBufferCapacity = 8)
    override val sensorData: SharedFlow<SensorStatus> = _sensorData.asSharedFlow()

    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private val rxBuffer = StringBuilder()

    private fun setState(next: BleConnectionState) {
        Log.d(tag, "state ▶ $next")
        _connectionState.value = next
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            Log.d(tag, "scan ▶ FOUND name=${device.name} addr=${device.address} rssi=${result.rssi}")
            stopScanInternal()
            setState(BleConnectionState.Connecting(device.name, device.address))
            Log.d(tag, "connectGatt → ${device.address}")
            gatt = device.connectGatt(appContext, false, gattCallback)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(tag, "scan ▶ FAILED code=$errorCode")
            scanning = false
            setState(BleConnectionState.Error("Scan failed: $errorCode"))
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(tag, "gatt ▶ onConnectionStateChange status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(tag, "gatt ▶ CONNECTED, discoverServices()")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(tag, "gatt ▶ DISCONNECTED, closing")
                    g.close()
                    gatt = null
                    setState(BleConnectionState.Disconnected)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            Log.d(tag, "gatt ▶ onServicesDiscovered status=$status services=${g.services.size}")
            g.services.forEach { svc ->
                Log.d(tag, "  • service ${svc.uuid} chars=${svc.characteristics.size}")
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setState(BleConnectionState.Error("Service discover failed: $status"))
                return
            }
            val service = g.getService(BleConstants.HM10_SERVICE_UUID)
            val characteristic = service?.getCharacteristic(BleConstants.HM10_CHARACTERISTIC_UUID)
            if (characteristic == null) {
                Log.e(tag, "HM-10 characteristic ${BleConstants.HM10_CHARACTERISTIC_UUID} NOT FOUND")
                setState(BleConnectionState.Error("HM-10 characteristic not found"))
                return
            }
            Log.d(tag, "characteristic OK → enabling notifications")
            g.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(BleConstants.CCCD_UUID)
            if (descriptor != null) {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                val written = g.writeDescriptor(descriptor)
                Log.d(tag, "CCCD write → $written")
            } else {
                Log.w(tag, "CCCD descriptor missing")
            }
            setState(BleConnectionState.Connected(g.device.name, g.device.address))
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            Log.d(tag, "gatt ▶ onDescriptorWrite uuid=${descriptor.uuid} status=$status")
        }

        @Deprecated("Used for API < 33 compatibility")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            handleIncoming(characteristic.value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncoming(value)
        }
    }

    private fun handleIncoming(bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) {
            Log.v(tag, "rx ▶ empty chunk")
            return
        }
        val asString = String(bytes, Charsets.UTF_8)
        Log.d(tag, "rx ▶ ${bytes.size}B \"${asString.replace("\n", "\\n").replace("\r", "\\r")}\"")
        rxBuffer.append(asString)
        while (true) {
            val endIdx = rxBuffer.indexOf('\n')
            if (endIdx < 0) break
            val line = rxBuffer.substring(0, endIdx).trim().trimEnd('\r')
            rxBuffer.delete(0, endIdx + 1)
            if (line.isNotEmpty()) {
                Log.d(tag, "line ▶ \"$line\"")
                val parsed = SensorStatusParser.parse(line)
                if (parsed != null) {
                    Log.d(tag, "parsed ▶ $parsed")
                    val emitted = _sensorData.tryEmit(parsed)
                    if (!emitted) Log.w(tag, "tryEmit dropped (buffer full)")
                } else {
                    Log.w(tag, "parse failed for \"$line\"")
                }
            }
        }
    }

    override fun startScanAndConnect() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        Log.d(tag, "startScanAndConnect() adapter=${bluetoothAdapter != null} enabled=${bluetoothAdapter?.isEnabled} scanner=${scanner != null}")
        if (bluetoothAdapter?.isEnabled != true || scanner == null) {
            Log.e(tag, "Bluetooth not available/enabled")
            setState(BleConnectionState.Error("Bluetooth disabled"))
            return
        }
        if (scanning) {
            Log.w(tag, "already scanning, ignoring")
            return
        }
        setState(BleConnectionState.Scanning)
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleConstants.HM10_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        Log.d(tag, "startScan filter=${BleConstants.HM10_SERVICE_UUID}")
        scanner.startScan(listOf(filter), settings, scanCallback)
        scanning = true

        mainHandler.postDelayed({
            if (scanning) {
                Log.w(tag, "scan timeout after ${BleConstants.SCAN_TIMEOUT_MS}ms")
                stopScanInternal()
                if (_connectionState.value is BleConnectionState.Scanning) {
                    setState(BleConnectionState.Error("Scan timeout"))
                }
            }
        }, BleConstants.SCAN_TIMEOUT_MS)
    }

    private fun stopScanInternal() {
        if (!scanning) return
        Log.d(tag, "stopScan")
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
    }

    override fun disconnect() {
        Log.d(tag, "disconnect() called")
        stopScanInternal()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        setState(BleConnectionState.Idle)
    }
}
