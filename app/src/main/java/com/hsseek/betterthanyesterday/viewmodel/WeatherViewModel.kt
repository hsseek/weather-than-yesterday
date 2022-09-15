package com.hsseek.betterthanyesterday.viewmodel

import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hsseek.betterthanyesterday.network.KmaResponse
import com.hsseek.betterthanyesterday.network.WeatherApi
import kotlinx.coroutines.launch
import retrofit2.Response

private const val TAG = "WeatherViewModel"

class WeatherViewModel: ViewModel() {
    private val _weatherData = MutableLiveData<Response<KmaResponse>>()
    val weatherData
        get() = _weatherData

    init {
        getWeatherData()
    }

    private fun getWeatherData() {
        viewModelScope.launch{
            try {
                val kmaResponse = WeatherApi.service.getWeather()
                _weatherData.value = kmaResponse
                for(i in kmaResponse.body()?.response!!.body.items.item){
                    Log.d(TAG, "$i")
                }
            } catch (e: Exception) {
                // TODO: Deal with the failure object(referring e.message)
            }
        }
    }
}