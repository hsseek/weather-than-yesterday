package com.hsseek.betterthanyesterday.viewmodel

import android.location.Location
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hsseek.betterthanyesterday.R
import com.hsseek.betterthanyesterday.location.CoordinatesLatLon
import com.hsseek.betterthanyesterday.location.CoordinatesXy
import com.hsseek.betterthanyesterday.location.convertToXy
import com.hsseek.betterthanyesterday.network.ForecastResponse
import com.hsseek.betterthanyesterday.network.OneShotEvent
import com.hsseek.betterthanyesterday.network.WeatherApi
import com.hsseek.betterthanyesterday.util.*
import com.hsseek.betterthanyesterday.util.KmaHourRoundOff.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val TAG = "WeatherViewModel"
private const val KMA_RAW_ITEM_TAG = "KmaItems"
private const val TEMPERATURE_TAG = "TMP"
private const val LOW_TEMPERATURE_TAG = "TMN"
private const val HIGH_TEMPERATURE_TAG = "TMX"
private const val HOURLY_TEMPERATURE_TAG = "T1H"
private const val RAIN_TAG = "PTY"

class WeatherViewModel: ViewModel() {
    private val _baseCoordinatesXy = MutableStateFlow(CoordinatesXy(60, 127))

    private val _baseCityName = mutableStateOf("")
    val cityName: String
        get() = _baseCityName.value

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

    private val _rainfallStatus: MutableStateFlow<Sky> = MutableStateFlow(Sky.Good())
    val rainfallStatus = _rainfallStatus.asStateFlow()

    private var charTempJob: Job? = null
    private var todayShortTermJob: Job? = null
    private var yesterdayJob: Job? = null

    private val _toastMessage = MutableStateFlow(OneShotEvent(0))
    val toastMessage = _toastMessage.asStateFlow()

    fun updateLocation(location: Location, cityName: String, isCurrentLocation: Boolean) {
        if (cityName != _baseCityName.value) {  // i.e. A new base location
            val xy = convertToXy(CoordinatesLatLon(location.latitude, location.longitude))
            Log.d(TAG, "A new location\t: $cityName(${xy.nx}, ${xy.ny})")

            _baseCoordinatesXy.value = xy
            _baseCityName.value = cityName
            cancelAllWeatherRequest()
            refreshAll()

            if (isCurrentLocation) {// Let the user know by a Toast.
                _toastMessage.value = OneShotEvent(R.string.toast_location_found)
            }
        } else {
            Log.                                                                                                                                                                                                    d(TAG, "The same location, no need to renew.")
        }
    }

    private fun cancelAllWeatherRequest() {
        charTempJob?.cancel()
        todayShortTermJob?.cancel()
        yesterdayJob?.cancel()
    }

    private fun refreshAll() {
        enumValues<CharacteristicTempType>().forEach { type ->
            enumValues<DayOfInterest>().forEach { date ->
                requestCharacteristicTemp(date, type)
            }
        }
        requestTodayShortTermStatus()
        requestYesterdayTemp()
    }

    private fun requestCharacteristicTemp(
        date: DayOfInterest = DayOfInterest.TODAY,
        type: CharacteristicTempType,
    ) {
        charTempJob = viewModelScope.launch {
            val baseTime = getBaseTime(date.dayOffset)
            val page = getPage(date.dayOffset, type, baseTime)
            val items: List<ForecastResponse.Item>
            val characteristicTemp: Int?
            Log.d(TAG, "D${date.dayOffset}\t${type.descriptor} baseTime\t: ${baseTime.date}-${baseTime.hour}")

            try {
                val fetchingStartTime = System.currentTimeMillis() // for test
                val kmaResponse = WeatherApi.service.getVillageWeather(
                    baseDate = baseTime.date,
                    baseTime = baseTime.hour,
                    numOfRows = 48,  // = (4 h) * (12 rows / h)
                    pageNo = page,
                    nx = _baseCoordinatesXy.value.nx,
                    ny = _baseCoordinatesXy.value.ny,
                )

                items = kmaResponse.body()!!.response.body.items.item
                logElapsedTime(TAG, "${items.size} items", fetchingStartTime)
                characteristicTemp = extractCharacteristicTemp(items, type)

                characteristicTemp?.let {
                    when (type) {
                        CharacteristicTempType.LOWEST -> _lowestTemps.value[date.dayOffset + 1] = it
                        CharacteristicTempType.HIGHEST -> _highestTemps.value[date.dayOffset + 1] = it
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
     * Always called from a ViewModelScope.
     * */
    private suspend fun getBaseTime(dayOffset: Int): KmaTime {
        val baseTime: KmaTime = if (dayOffset <= 0) {
            getKmaBaseTime(dayOffset = dayOffset, roundOff = DAY)
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
     * Always called from a ViewModelScope.
     * */
    private suspend fun getPage(
        dayOffset: Int = DayOfInterest.TODAY.dayOffset,
        type: CharacteristicTempType,
        baseTime: KmaTime
    ): Int {
        val page: Int
        if (dayOffset <= 0) {
            page = when (type) {
                CharacteristicTempType.LOWEST -> 2  // Data of 04:00, 05:00, 06:00 and 07:00
                CharacteristicTempType.HIGHEST -> 4  // Data of 12:00, 13:00, 14:00 and 15:00
            }
        } else {  // Tomorrow or the day after tomorrow
            page = if (baseTime.hour.toInt() == 2300) {
                // Note that today's 00:00 ~ 03:00 on the first page
                when (type) {
                    CharacteristicTempType.LOWEST -> 2 + dayOffset * 6
                    CharacteristicTempType.HIGHEST -> 4 + dayOffset * 6
                }
            } else {  // Today's 12:00 ~ 15:00 on the first page
                when (type) {
                    CharacteristicTempType.LOWEST -> -1 + dayOffset * 6
                    CharacteristicTempType.HIGHEST -> 1 + dayOffset * 6
                }
            }
        }
        return page
    }

    /**
     * Returns the highest and the lowest temperature from items of a KmaResponse.
     * Always called from a ViewModelScope.
     */
    private suspend fun extractCharacteristicTemp(
        items: List<ForecastResponse.Item>,
        type: CharacteristicTempType
    ): Int?
    {
        var characteristicTemp: Int? = null
        val tag = when (type) {
            CharacteristicTempType.LOWEST -> LOW_TEMPERATURE_TAG
            CharacteristicTempType.HIGHEST -> HIGH_TEMPERATURE_TAG
        }
        for (i in items) {
            Log.d(KMA_RAW_ITEM_TAG, "$i")
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
    private fun requestTodayShortTermStatus() {
        todayShortTermJob = viewModelScope.launch {
            try {
                val shortTermItems = requestShortTermData(DayOfInterest.TODAY)
                updateShortTermTemp(shortTermItems, DayOfInterest.TODAY)

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
                Log.d(TAG, "Today's long-term conditions baseTime\t:${longTermBaseTime.date}-${longTermBaseTime.hour}")

                val ltFetchingStartTime = System.currentTimeMillis() // for test
                val longTermResponse = WeatherApi.service.getVillageWeather(
                    baseDate = longTermBaseTime.date,
                    baseTime = longTermBaseTime.hour,
                    numOfRows = numOfRows,
                    pageNo = 1,
                    nx = _baseCoordinatesXy.value.nx,
                    ny = _baseCoordinatesXy.value.ny,
                )

                val longTermItems = longTermResponse.body()!!.response.body.items.item
                logElapsedTime(TAG, "${longTermItems.size} items", ltFetchingStartTime)

                updateRainfall(shortTermItems, longTermItems)
            } catch (e: Exception) {
                Log.e(TAG, "$e: Error retrieving today's short-term forecast.")
            }
        }
    }

    private suspend fun requestShortTermData(date: DayOfInterest): List<ForecastResponse.Item> {
        val baseTime = getKmaBaseTime(
            dayOffset = date.dayOffset,
            roundOff = HOUR,
            isQuickPublish = false,
        )
        Log.d(TAG, "D${date.dayOffset} short-term T baseTime\t:${baseTime.date}-${baseTime.hour}")
        // Retrieve the short-term data
        val fetchingStartTime = System.currentTimeMillis() // for test
        val kmaResponse = WeatherApi.service.getShortTermWeather(
            baseDate = baseTime.date,
            baseTime = baseTime.hour,
            numOfRows = 30,  // [LGT -> PTY -> RN1 -> SKY -> TH1] -> REH -> UUU -> VVV -> ...
            pageNo = 1,
            nx = _baseCoordinatesXy.value.nx,
            ny = _baseCoordinatesXy.value.ny,
        )

        val items = kmaResponse.body()!!.response.body.items.item
        logElapsedTime(TAG, "${items.size} items", fetchingStartTime)
        for (i in items) {
            Log.d(KMA_RAW_ITEM_TAG, "$i")
        }
        return items
    }

    /**
     * Update the short-term temperatures of [date],
     * based on data from [items].
     * Always called from a ViewModelScope.
     */
    private suspend fun updateShortTermTemp(items: List<ForecastResponse.Item>, date: DayOfInterest) {
        try {
            for (i in items) {
                if (i.category == HOURLY_TEMPERATURE_TAG) {
                    val tem = i.fcstValue
                    Log.d(TAG, "T1H of D${date.dayOffset}\t: $tem")

                    if (date == DayOfInterest.TODAY) {
                        _hourlyTempToday.value = tem  // The first item with TH1 category
                    } else if (date == DayOfInterest.YESTERDAY) {
                        _hourlyTempYesterday.value = tem
                    }

                    if (charTempJob?.isCompleted == true && charTempJob?.isCancelled == false) {
                        adjustCharTemp(tem.toInt(), date)
                    }
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "$e: Error retrieving the short-term hourly temp(T1H).")
        }
    }

    /**
     * Often, TMX / TMN values are lower / higher than hourly value.
     * If the shown hourly value is higher than TMX than the user might doubt the reliability: adjust.
     */
    private suspend fun adjustCharTemp(hourlyTemp: Int, date: DayOfInterest) {
        val index = date.dayOffset + 1  // Yesterday's data on [0]
        if (hourlyTemp > _highestTemps.value[index]) {
            _highestTemps.value[index] = hourlyTemp
        }
        if (hourlyTemp < _lowestTemps.value[index]) {
            _lowestTemps.value[index] = hourlyTemp
        }
    }

    /**
     * Update the rainfall status of today,
     * based on [primaryItems] and [secondaryItems].
     * if there are data from the same base time, [primaryItems] take the priority.
     * Always called from a ViewModelScope.
     * */
    private suspend fun updateRainfall(
        primaryItems: List<ForecastResponse.Item>,
        secondaryItems: List<ForecastResponse.Item>
    ) {
        val rainingHours = arrayListOf<Int>()
        val snowingHours = arrayListOf<Int>()
        try {
            val primaryRainfallData = primaryItems.filter { it.category == RAIN_TAG }

            // Data from primary items are the source of truth. Discard data of secondary items for hours before.
            val primaryCoveredHourMax = primaryRainfallData.maxOf { it.fcstTime }
            val secondaryRainfallData = secondaryItems.filter {
                (it.category == RAIN_TAG) and (it.fcstTime > primaryCoveredHourMax)
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
        val hours = rainingHours + snowingHours
        if (rainingHours.size == 0) {
            if (snowingHours.size == 0) {
                // No raining, no snowing
                _rainfallStatus.value = Sky.Good()
            } else {
                // No raining, but snowing
                _rainfallStatus.value = Sky.Bad.Snowy(snowingHours.min(), snowingHours.max())
            }
        } else {  // Raining
            if (snowingHours.size == 0) {
                _rainfallStatus.value = Sky.Bad.Rainy(rainingHours.min(), rainingHours.max())
            } else {  // Raining + Snowing
                _rainfallStatus.value = Sky.Bad.Mixed(hours.min(), hours.max())
            }
        }
        Log.d(TAG, "PTY: ${_rainfallStatus.value::class.simpleName}\t(${hours.minOrNull()} ~ ${hours.maxOrNull()})")
        } catch (e: Exception) {
            Log.e(TAG, "$e: Cannot retrieve the short-term rainfall status(PTY).")
        }
    }

    private fun requestYesterdayTemp() {
        yesterdayJob = viewModelScope.launch {
            try {
                val items = requestShortTermData(DayOfInterest.YESTERDAY)
                updateShortTermTemp(items, DayOfInterest.YESTERDAY)
            } catch (e: Exception) {
                Log.e(TAG, "$e: Cannot retrieve the yesterday's hourly temp(T1H).")
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                // TODO: Connect to Repository
//                val savedStateHandle = createSavedStateHandle()
//                val myRepository = (this[APPLICATION_KEY] as MyApplication).myRepository
//                MyViewModel(
//                    myRepository = myRepository,
//                    savedStateHandle = savedStateHandle
//                )
                WeatherViewModel()
            }
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

sealed class Sky(type: RainfallType) {
    class Good: Sky(RainfallType.None)
    sealed class Bad(type: RainfallType, startingHour: Int, endingHour: Int): Sky(type) {
        class Mixed(startingHour: Int, endingHour: Int): Bad(RainfallType.Mixed, startingHour, endingHour)
        class Rainy(startingHour: Int, endingHour: Int): Bad(RainfallType.Raining, startingHour, endingHour)
        class Snowy(startingHour: Int, endingHour: Int): Bad(RainfallType.Snowing, startingHour, endingHour)
    }
}