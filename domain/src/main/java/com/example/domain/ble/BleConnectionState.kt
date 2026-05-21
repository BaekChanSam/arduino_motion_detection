package com.example.domain.ble

sealed class BleConnectionState {
    object Idle : BleConnectionState()
    object Scanning : BleConnectionState()
    data class Connecting(val deviceName: String?, val address: String) : BleConnectionState()
    data class Connected(val deviceName: String?, val address: String) : BleConnectionState()
    object Disconnected : BleConnectionState()
    data class Error(val message: String) : BleConnectionState()
}
