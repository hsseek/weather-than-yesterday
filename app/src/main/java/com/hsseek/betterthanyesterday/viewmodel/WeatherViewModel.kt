package com.hsseek.betterthanyesterday.viewmodel

import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hsseek.betterthanyesterday.network.KmaResponse
import com.hsseek.betterthanyesterday.network.WeatherApi
import com.hsseek.betterthanyesterday.util.*
import com.hsseek.betterthanyesterday.util.KmaHourRoundOff.*
import kotlinx.coroutines.launch

private const val TAG = "WeatherViewModel"
private const val CURRENT_TEMPERATURE_TAG = "TMP"
private const val LOW_TEMPERATURE_TAG = "TMN"
private const val HIGH_TEMPERATURE_TAG = "TMX"

class WeatherViewModel: ViewModel() {
    // The lowest and the highest temperatures of yesterday through the day after tomorrow
    private val _lowestTempYesterday = mutableStateOf("")  // Do not expose a mutable variable
    val lowestTempYesterday: String
        get() = _lowestTempYesterday.value

    private val _highestTempYesterday = mutableStateOf("")
    val highestTempYesterday: String
        get() = _highestTempYesterday.value

    private val _lowestTemp0 = mutableStateOf("")
    val lowestTemp0: String
        get() = _lowestTemp0.value

    private val _highestTemp0 = mutableStateOf("")
    val highestTemp0: String
        get() = _highestTemp0.value

    private val _lowestTemp1 = mutableStateOf("")
    val lowestTemp1: String
        get() = _lowestTemp1.value

    private val _highestTemp1 = mutableStateOf("")
    val highestTemp1: String
        get() = _highestTemp1.value

    private val _lowestTemp2 = mutableStateOf("")
    val lowestTemp2: String
        get() = _lowestTemp2.value

    private val _highestTemp2 = mutableStateOf("")
    val highestTemp2: String
        get() = _highestTemp2.value

    // The current temperature
    private val _currentTempToday = mutableStateOf("")
    val currentTempToday: String
        get() = _currentTempToday.value

    // The corresponding temperature of yesterday
    private val _currentTempYesterday = mutableStateOf("")
    val currentTempYesterday: String
        get() = _currentTempYesterday.value

    init {
        // The lowest temperatures of yesterday through the day after tomorrow.
        for (i in -1..2) {
            getLowestTemp(i)
        }
    }

    /**
     * Returns the lowest temperature of the day.
     * [dayOffset] must be -1, 0, 1, or 2,
     * representing the yesterday through the day after tomorrow.
     * */
    private fun getLowestTemp(dayOffset: Int = 0) {
        viewModelScope.launch {
            var lowestTemp: Float? = null
            val baseTime: KmaTime
            val page: Int
            val items: List<KmaResponse.Item>

            if (dayOffset <= 0) {
                baseTime = getKmaBaseTime(dayOffset = dayOffset, roundOff = DAY)
                page = 2  // Data of 04:00, 05:00, 06:00 and 07:00
            } else {  // Tomorrow or the day after tomorrow
                baseTime = getKmaBaseTime(
                    dayOffset = 0,  // baseTime can't be future
                    roundOff = NOON  // Renew the Views twice a day
                )
                page = if (baseTime.hour.toInt() == 2300) {  // Today's 00:00 ~ 03:00 on the first page
                    2 + dayOffset * 6
                } else {  // Today's 12:00 ~ 15:00 on the first page
                    -1 + dayOffset * 6
                }
            }

            try {
                val fetchingStartTime = System.currentTimeMillis() // for test
                val kmaResponse = WeatherApi.service.getVillageWeather(
                    baseDate = baseTime.date,
                    baseTime = baseTime.hour,
                    numOfRows = 48,  // = (4 h) * (12 rows / h)
                    pageNo = page,
                )

                items = kmaResponse.body()!!.response.body.items.item
                logElapsedTime(TAG, "${items.size}", fetchingStartTime)

                for (i in items) {
                    // Log.d(TAG, "$i")
                    if (i.category == LOW_TEMPERATURE_TAG) {
                        val tmn = i.fcstValue.toFloat()
                        Log.d(TAG, "Lowest temperature of D$dayOffset: $tmn (at ${i.fcstTime})")
                        when (dayOffset) {
                            -1 -> _lowestTempYesterday.value = formatTemperature(tmn)
                            0 -> _lowestTemp0.value = formatTemperature(tmn)
                            1 -> _lowestTemp1.value = formatTemperature(tmn)
                            2 -> _lowestTemp2.value = formatTemperature(tmn)
                        }
                        // The definitive minimum value found. Don't need to search further.
                        return@launch
                    }
                }
                for (i in items) {
                    if (i.category == CURRENT_TEMPERATURE_TAG) {
                        val temp = i.fcstValue.toFloat()  // The temperature at the time
                        lowestTemp?.let { lt ->
                            if (temp < lt) lowestTemp = temp  // Update the lowest temperature
                        }
                        if (lowestTemp == null) lowestTemp = temp
                    } else {
                        // Irrelevant data such as humidity, etc.
                        continue
                    }
                }
                lowestTemp?.let {
                    when (dayOffset) {
                        -1 -> _lowestTempYesterday.value = formatTemperature(it)
                        0 -> _lowestTemp0.value = formatTemperature(it)
                        1 -> _lowestTemp1.value = formatTemperature(it)
                        2 -> _lowestTemp2.value = formatTemperature(it)
                    }
                }
                Log.d(TAG, "Lowest temperature of D$dayOffset: $lowestTemp (estimated)")
            } catch (e: Exception) {
                Log.e(TAG, "Error while retrieving the lowest temp: $e")
            }
        }
    }
}