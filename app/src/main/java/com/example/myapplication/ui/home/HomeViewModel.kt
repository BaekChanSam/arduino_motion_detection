package com.example.myapplication.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import com.example.domain.ble.BleConnectionState
import com.example.domain.ble.BleRepository
import com.example.entity.SensorStatus
import timber.log.Timber

class HomeViewModel(
    private val bleRepository: BleRepository
) : ViewModel() {

    init {
        Timber.tag("VM").d("HomeViewModel created")
    }

    val connectionState: LiveData<BleConnectionState> =
        bleRepository.connectionState.asLiveData()

    val sensorStatus: LiveData<SensorStatus> =
        bleRepository.sensorData.asLiveData()

    fun connect() {
        Timber.tag("VM").d("connect() called")
        bleRepository.startScanAndConnect()
    }

    fun disconnect() {
        Timber.tag("VM").d("disconnect() called")
        bleRepository.disconnect()
    }

    override fun onCleared() {
        Timber.tag("VM").d("HomeViewModel cleared")
        super.onCleared()
    }
}
