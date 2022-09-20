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
import kotlin.math.roundToInt

private const val TAG = "WeatherViewModel"
private const val CURRENT_TEMPERATURE_TAG = "TMP"
private const val LOW_TEMPERATURE_TAG = "TMN"
private const val HIGH_TEMPERATURE_TAG = "TMX"

class WeatherViewModel: ViewModel() {
    // The lowest temperatures of yesterday through the day after tomorrow
    private val _lowestTemps = mutableStateOf(IntArray(4))
    val lowestTemps: IntArray
        get() = _lowestTemps.value

    // The highest temperatures of yesterday through the day after tomorrow
    private val _highestTemps = mutableStateOf(IntArray(4))
    val highestTemps: IntArray
        get() = _highestTemps.value

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
        enumValues<CharacteristicTempType>().forEach { type ->
            enumValues<DayOfInterest>().forEach { date ->
                updateCharacteristicTemp(date, type)
            }
        }
    }

    private fun updateCharacteristicTemp(
        date: DayOfInterest = DayOfInterest.TODAY,
        type: CharacteristicTempType,
    ) {
        viewModelScope.launch {
            val baseTime = getBaseTime(date)
            val page = getPage(date, type, baseTime)
            val items: List<KmaResponse.Item>
            val characteristicTemp: Int?

            try {
                val fetchingStartTime = System.currentTimeMillis() // for test
                val kmaResponse = WeatherApi.service.getVillageWeather(
                    baseDate = baseTime.date,
                    baseTime = baseTime.hour,
                    numOfRows = 48,  // = (4 h) * (12 rows / h)
                    pageNo = page,
                )

                items = kmaResponse.body()!!.response.body.items.item
                logElapsedTime(TAG, "${items.size} items", fetchingStartTime)
                characteristicTemp = getCharacteristicTemp(items, type)

                characteristicTemp?.let {
                    when (type) {
                        CharacteristicTempType.LOWEST -> {
                            when (date) {
                                DayOfInterest.YESTERDAY -> _lowestTemps.value[0] = it
                                DayOfInterest.TODAY -> _lowestTemps.value[1] = it
                                DayOfInterest.DAY2 -> _lowestTemps.value[2] = it
                                DayOfInterest.DAY3 -> _lowestTemps.value[3] = it
                            }
                        }
                        CharacteristicTempType.HIGHEST -> {
                            when (date) {
                                DayOfInterest.YESTERDAY -> _highestTemps.value[0] = it
                                DayOfInterest.TODAY -> _highestTemps.value[1] = it
                                DayOfInterest.DAY2 -> _highestTemps.value[2] = it
                                DayOfInterest.DAY3 -> _highestTemps.value[3] = it
                            }
                        }
                    }
                }
                Log.d(TAG, "${type.descriptor}\tof D${date.dayOffset}\t: $characteristicTemp")
            } catch (e: Exception) {
                Log.e(TAG, "Error retrieving ${type.descriptor}: $e")
            }
        }
    }

    /**
     * Returns the baseTime to query.
     * */
    private fun getBaseTime(date: DayOfInterest): KmaTime {
        val baseTime: KmaTime = if (date.dayOffset <= 0) {
            getKmaBaseTime(dayOffset = date.dayOffset, roundOff = DAY)
        } else {  // Tomorrow or the day after tomorrow
            getKmaBaseTime(
                dayOffset = 0,  // baseTime can't be future.
                roundOff = NOON  // Renew the Views twice a day.
            )
        }
        return baseTime
    }

    /**
     * Returns the page to query.
     * To utilize minimal resources, the number of rows per page was limited
     * and the right page should be picked.
     * */
    private fun getPage(
        date: DayOfInterest = DayOfInterest.TODAY,
        type: CharacteristicTempType,
        baseTime: KmaTime
    ): Int {
        val page: Int
        if (date.dayOffset <= 0) {
            page = when (type) {
                CharacteristicTempType.LOWEST -> 2  // Data of 04:00, 05:00, 06:00 and 07:00
                CharacteristicTempType.HIGHEST -> 4  // Data of 12:00, 13:00, 14:00 and 15:00
            }
        } else {  // Tomorrow or the day after tomorrow
            page = if (baseTime.hour.toInt() == 2300) {
                // Note that today's 00:00 ~ 03:00 on the first page
                when (type) {
                    CharacteristicTempType.LOWEST -> 2 + date.dayOffset * 6
                    CharacteristicTempType.HIGHEST -> 4 + date.dayOffset * 6
                }
            } else {  // Today's 12:00 ~ 15:00 on the first page
                when (type) {
                    CharacteristicTempType.LOWEST -> -1 + date.dayOffset * 6
                    CharacteristicTempType.HIGHEST -> 1 + date.dayOffset * 6
                }
            }
        }
        return page
    }

    private fun getCharacteristicTemp(items: List<KmaResponse.Item>, type: CharacteristicTempType): Int? {
        var characteristicTemp: Int? = null
        val tag = when (type) {
            CharacteristicTempType.LOWEST -> LOW_TEMPERATURE_TAG
            CharacteristicTempType.HIGHEST -> HIGH_TEMPERATURE_TAG
        }
        for (i in items) {
            // Log.d(TAG, "$i")
            // Often, values larger / smaller than the TMX / TMN are recorded.
            if (i.category == CURRENT_TEMPERATURE_TAG || i.category == tag) {
                val temp = i.fcstValue.toFloat().roundToInt()  // The temperature at the time
                characteristicTemp?.let { lt ->
                    when (type) {
                        CharacteristicTempType.LOWEST -> if (temp < lt) characteristicTemp = temp
                        CharacteristicTempType.HIGHEST -> if (temp > lt) characteristicTemp = temp
                    }
                }
                if (characteristicTemp == null) characteristicTemp = temp
            } else {
                // Irrelevant data such as humidity, etc.
                continue
            }
        }
        return characteristicTemp
    }
}

enum class CharacteristicTempType(val descriptor: String) {
    HIGHEST("T_H"), LOWEST("T_L")
}

enum class DayOfInterest(val dayOffset: Int) {
    YESTERDAY(-1), TODAY(0), DAY2(1), DAY3(2)
}