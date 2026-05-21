package com.example.myapplication.di

import com.example.data.ble.BleRepositoryImpl
import com.example.domain.ble.BleRepository
import com.example.myapplication.ui.home.HomeViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule = module {

    // BLE
    single<BleRepository> { BleRepositoryImpl(androidContext()) }

    // ViewModels
    viewModel { HomeViewModel(get()) }
}
