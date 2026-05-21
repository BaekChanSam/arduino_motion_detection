package com.example.domain.ble

import com.example.entity.SensorStatus
import kotlinx.coroutines.flow.Flow

interface BleRepository {
    val connectionState: Flow<BleConnectionState>
    val sensorData: Flow<SensorStatus>

    fun startScanAndConnect()
    fun disconnect()
}
