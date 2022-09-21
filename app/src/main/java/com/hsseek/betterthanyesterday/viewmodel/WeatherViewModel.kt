package com.hsseek.betterthanyesterday.viewmodel

import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hsseek.betterthanyesterday.network.ForecastResponse
import com.hsseek.betterthanyesterday.network.WeatherApi
import com.hsseek.betterthanyesterday.util.*
import com.hsseek.betterthanyesterday.util.KmaHourRoundOff.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val TAG = "WeatherViewModel"
private const val TEMPERATURE_TAG = "TMP"
private const val LOW_TEMPERATURE_TAG = "TMN"
private const val HIGH_TEMPERATURE_TAG = "TMX"
private const val HOURLY_TEMPERATURE_TAG = "T1H"
private const val RAIN_TAG = "PTY"

class WeatherViewModel: ViewModel() {
    // The lowest temperatures of yesterday through the day after tomorrow
    private val _lowestTemps = mutableStateOf(IntArray(4))
    val lowestTemps: IntArray
        get() = _lowestTemps.value

    // The highest temperatures of yesterday through the day after tomorrow
    private val _highestTemps = mutableStateOf(IntArray(4))
    val highestTemps: IntArray
        get() = _highestTemps.value

    // The hourly temperature
    private val _hourlyTempToday = mutableStateOf("")
    val hourlyTempToday: String
        get() = _hourlyTempToday.value

    // The corresponding temperature of yesterday
    private val _hourlyTempYesterday = mutableStateOf("")
    val hourlyTempYesterday: String
        get() = _hourlyTempYesterday.value

    private val _rainfallStatus: StateFlow<RainfallStatus> = MutableStateFlow(
        RainfallStatus(RainfallType.None, null, null)
    )
    val rainfallStatus: StateFlow<RainfallStatus>
        get() = _rainfallStatus

    init {
        // The lowest temperatures of yesterday through the day after tomorrow.
        enumValues<CharacteristicTempType>().forEach { type ->
            enumValues<DayOfInterest>().forEach { date ->
                updateCharacteristicTemp(date, type)
            }
        }
        updateTodayShortTermStatus()
    }

    private fun updateCharacteristicTemp(
        date: DayOfInterest = DayOfInterest.TODAY,
        type: CharacteristicTempType,
    ) {
        viewModelScope.launch {
            val baseTime = getBaseTime(date)
            val page = getPage(date, type, baseTime)
            val items: List<ForecastResponse.Item>
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
                Log.e(TAG, "$e: Error retrieving ${type.descriptor}")
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

    private fun getCharacteristicTemp(items: List<ForecastResponse.Item>, type: CharacteristicTempType): Int? {
        var characteristicTemp: Int? = null
        val tag = when (type) {
            CharacteristicTempType.LOWEST -> LOW_TEMPERATURE_TAG
            CharacteristicTempType.HIGHEST -> HIGH_TEMPERATURE_TAG
        }
        for (i in items) {
            // Log.d(TAG, "$i")
            // Often, values larger / smaller than the TMX / TMN are recorded.
            if (i.category == TEMPERATURE_TAG || i.category == tag) {
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

    /**
     * Update the short-term future temperature (< 1h) and
     * rainfall status of today.
     * */
    private fun updateTodayShortTermStatus() {
        viewModelScope.launch {
            try {
                val shortTermBaseTime = getKmaBaseTime(
                    roundOff = HOUR,
                    isQuickPublish = false,
                )
                // Retrieve the short-term data
                val stFetchingStartTime = System.currentTimeMillis() // for test
                val shortTermResponse = WeatherApi.service.getShortTermWeather(
                    baseDate = shortTermBaseTime.date,
                    baseTime = shortTermBaseTime.hour,
                    numOfRows = 30,  // [LGT -> PTY -> RN1 -> SKY -> TH1] -> REH -> UUU -> VVV -> ...
                    pageNo = 1,
                )

                val shortTermItems = shortTermResponse.body()!!.response.body.items.item
                logElapsedTime(TAG, "${shortTermItems.size} items", stFetchingStartTime)

                updateTodayTemp(shortTermItems)

                /*
                * As the short-term data span only 6 hours,
                * additional data must be retrieve for the rest of the day (while less accurate).
                */
                val longTermBaseTime = getKmaBaseTime(
                    roundOff = VILLAGE,
                    isQuickPublish = true,
                )
                val numOfRows: Int = if (longTermBaseTime.hour == "2300") {
                    12 * 24  // 23:00 of the previous day: whole day's data should be examined.
                } else {
                    // Otherwise, only the later hours should be examined.
                    (23 - longTermBaseTime.hour.toInt() / 100) * 12
                }
                val ltFetchingStartTime = System.currentTimeMillis() // for test
                val longTermResponse = WeatherApi.service.getVillageWeather(
                    baseDate = longTermBaseTime.date,
                    baseTime = longTermBaseTime.hour,
                    numOfRows = numOfRows,
                    pageNo = 1,
                )

                val longTermItems = longTermResponse.body()!!.response.body.items.item
                logElapsedTime(TAG, "${longTermItems.size} items", ltFetchingStartTime)

                updateRainfall(shortTermItems, longTermItems)
            } catch (e: Exception) {
                Log.e(TAG, "$e: Cannot short-term forecast.")
            }
        }
    }

    private fun updateTodayTemp(items: List<ForecastResponse.Item>) {
        try {
            for (i in items) {
                if (i.category == HOURLY_TEMPERATURE_TAG) {
                    _hourlyTempToday.value = i.fcstValue  // The first item with TH1 category
                    Log.d(TAG, "T1H: ${i.fcstValue}")
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "$e: Cannot retrieve the short-term hourly temp(T1H).")
        }
    }

    private fun updateRainfall(
        primaryItems: List<ForecastResponse.Item>,
        secondaryItems: List<ForecastResponse.Item>
    ) {
        val rainingHours = arrayListOf<Int>()
        val snowingHours = arrayListOf<Int>()
        try {
            val primaryRainfallData = primaryItems.filter { it.category == RAIN_TAG }
            val primaryCoveredHours = IntArray(primaryRainfallData.size)
            for (i in primaryRainfallData.indices) {
                primaryCoveredHours[i] = primaryRainfallData[i].fcstTime
            }
            // Data from primary items are the source of truth
            val secondaryRainfallData = secondaryItems.filter {
                (it.category == RAIN_TAG) and (it.fcstTime !in primaryCoveredHours)
            }

            for (i in primaryRainfallData + secondaryRainfallData) {
                val status = i.fcstValue.toInt()  // Must be integers of 0 through 7
                if (
                    status == RainfallType.Raining.code ||
                    status == RainfallType.Mixed.code ||
                    status == RainfallType.Shower.code
                ) {
                    rainingHours.add(i.fcstTime)
                } else if (
                    status == RainfallType.Snowing.code
                ) {
                    snowingHours.add(i.fcstTime)
                }
            }

            // Finally, update the variable.
            _rainfallStatus.value.let {
                // Merge the hours as they need to be dealt with umbrellas anyway.
                it.startHour = (rainingHours + snowingHours).minOrNull()
                it.endHour = (rainingHours + snowingHours).maxOrNull()
                // Classify the rainfall(snowfall) types
                if (rainingHours.size > 0) {
                    if (snowingHours.size > 0) {
                        it.type = RainfallType.Mixed
                    } else {
                        it.type = RainfallType.Raining
                    }
                } else if (snowingHours.size > 0) {
                    it.type = RainfallType.Snowing
                } else {
                    it.type = RainfallType.None
                }

                Log.d(TAG, "PTY: ${it.type}\t(${it.startHour} ~ ${it.endHour})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "$e: Cannot retrieve the short-term rainfall status(PTY).")
        }
    }
}

enum class CharacteristicTempType(val descriptor: String) {
    HIGHEST("T_H"), LOWEST("T_L")
}

enum class DayOfInterest(val dayOffset: Int) {
    YESTERDAY(-1), TODAY(0), DAY2(1), DAY3(2)
}

enum class RainfallType(val code: Int) {
    None(0), Raining(1), Mixed(2), Snowing(3), Shower(4),
    // LightRain(5), LightRainAndSnow(6), LightSnow(7)
}

data class RainfallStatus(
    var type: RainfallType,
    var startHour: Int?,
    var endHour: Int?,
)