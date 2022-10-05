package com.hsseek.betterthanyesterday

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import com.hsseek.betterthanyesterday.viewmodel.WeatherViewModel
import org.koin.android.ext.koin.androidApplication
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module


class YesterdayApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidContext(this@YesterdayApplication)
            modules(appModule)
        }
    }
}

val appModule = module {
    viewModel { WeatherViewModel(androidApplication()) }
}
