package com.hsseek.betterthanyesterday.viewmodel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.ActivityCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.hsseek.betterthanyesterday.R
import com.hsseek.betterthanyesterday.data.UserPreferencesRepository
import com.hsseek.betterthanyesterday.location.CoordinatesLatLon
import com.hsseek.betterthanyesterday.location.CoordinatesXy
import com.hsseek.betterthanyesterday.location.KoreanGeocoder
import com.hsseek.betterthanyesterday.location.convertToXy
import com.hsseek.betterthanyesterday.network.ForecastResponse
import com.hsseek.betterthanyesterday.network.WeatherApi
import com.hsseek.betterthanyesterday.util.*
import com.hsseek.betterthanyesterday.util.KmaHourRoundOff.HOUR
import com.hsseek.betterthanyesterday.util.KmaHourRoundOff.VILLAGE
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.Response
import java.net.UnknownHostException
import java.util.*
import kotlin.math.roundToInt

private const val TAG = "WeatherViewModel"
private const val LOCATION_TAG = "Location"
private const val VILLAGE_TEMPERATURE_TAG = "TMP"
private const val LOW_TEMPERATURE_TAG = "TMN"
private const val HIGH_TEMPERATURE_TAG = "TMX"
private const val HOURLY_TEMPERATURE_TAG = "T1H"
private const val RAIN_TAG = "PTY"
private const val NETWORK_TIMEOUT = 5000L
private const val NETWORK_MAX_RETRY = 2

class WeatherViewModel(
    application: Application,
    private val userPreferencesRepository: UserPreferencesRepository,
) : AndroidViewModel(application) {
    private val context = application
    private val stringForNull = context.getString(R.string.null_value)

    private val retrofitDispatcher = Dispatchers.IO
    private val defaultDispatcher = Dispatchers.Default
    private var kmaJob: Job? = null
    private var isDataValid: Boolean = true
    private val _isLoading = mutableStateOf(true)
    val isLoading: Boolean
        get() = _isLoading.value

    private var lastHourBaseTime: KmaTime = getKmaBaseTime(roundOff = HOUR)

    private var baseCoordinatesXy = CoordinatesXy(60, 127)

    // Variables regarding location.
    private val locationClient = LocationServices.getFusedLocationProviderClient(context)
    var toShowLocatingDialog = mutableStateOf(false)
        private set
    // The forecast location is an input from UI.
    var locatingMethod: LocatingMethod? = null
        private set

    // TODO: Default to ""
    private val _cityName: MutableState<String> = mutableStateOf(stringForNull)
    val cityName: String
        get() = _cityName.value

    private val _districtName: MutableState<String> = mutableStateOf(stringForNull)
    val districtName: String
        get() = _districtName.value

    // The highest/lowest temperatures of yesterday through the day after tomorrow
    private val temperatureCount: Int = enumValues<DayOfInterest>().size
    private val lowestTemps: Array<Int?> = arrayOfNulls(temperatureCount)
    private val highestTemps: Array<Int?> = arrayOfNulls(temperatureCount)
    private val dailyTempHeader = DailyTemperature(false, "",
        context.getString(R.string.daily_highest),
        context.getString(R.string.daily_lowest)
    )
    private val _dailyTemps: MutableState<List<DailyTemperature>> = mutableStateOf(getDefaultDailyTemps())
    val dailyTemps: List<DailyTemperature>
        get() = _dailyTemps.value

    // The hourly temperature
    private val _hourlyTempDiff: MutableState<Int?> = mutableStateOf(null)
    val hourlyTempDiff: Int?
        get() = _hourlyTempDiff.value

    private val _hourlyTempToday: MutableState<Int?> = mutableStateOf(null)  // (getUltraSrtFcst) fcstValue: "12"
    val hourlyTempToday: Int?
        get() = _hourlyTempToday.value
    private var hourlyTempYesterday: Int? = null

    private val _rainfallStatus: MutableStateFlow<Sky> = MutableStateFlow(Sky.Undetermined)
    val rainfallStatus = _rainfallStatus.asStateFlow()

    private val _toastMessage = MutableStateFlow(OneShotEvent(0))
    val toastMessage = _toastMessage.asStateFlow()

    /**
     * Process the selection of LocatingMethod from the dialog.
     *
     * auto?	permitted?	changed?
    V			V			V			update
    V			-			V			request permission (Dealt with Activity)
    V			-			-			request permission (Dealt with Activity)
    V			V			-			none
    -			-			-			none
    -			V			-			none
    -			V			V			update
    -			-			V			update
     * */
    fun updateLocatingMethod(
        selectedLocatingMethod: LocatingMethod,
        isSelectionValid: Boolean = true
    ) {
        // Store the selected LocatingMethod.
        viewModelScope.launch {
            userPreferencesRepository.updateLocatingMethod(selectedLocatingMethod)
        }

        if (selectedLocatingMethod != locatingMethod) {  // Changed
            /* auto?	permitted?	CHANGED?
            V			V			V			update
            V			-			V			request permission (INVALID SELECTION)
            -			V			V			update
            -			-			V			update
            * */
            locatingMethod = selectedLocatingMethod

            if (isSelectionValid) {
                if (selectedLocatingMethod == LocatingMethod.Auto) {
                    startLocationUpdate()
                } else {  // LocatingMethod for fixed locations
                    updateFixedLocation(selectedLocatingMethod)
                    stopLocationUpdate()
                }
            }
        } else {  // Forecast location not changed
            /* Nothing To do.
             auto?	    permitted?	CHANGED?
             V			-			-			request permission (INVALID SELECTION)
             V			V			-			none
             -			-			-			none
             -			V			-			none
            * */
        }
    }

    /**
     * Update location information determined by fixed LocatingMethod,
     * and triggers retrieving weather data eventually.
     * */
    private fun updateFixedLocation(lm: LocatingMethod) {
        val cityName = context.getString(lm.regionId)
        _districtName.value = context.getString(R.string.location_manually)
        updateLocationAndWeather(lm.coordinates!!, cityName)
    }

    private fun nullifyWeatherInfo() {
        _hourlyTempToday.value = null
        _hourlyTempDiff.value = null
        _dailyTemps.value = getDefaultDailyTemps()
        _rainfallStatus.value = Sky.Undetermined
    }

    private fun requestAllWeatherData() {
        nullifyWeatherInfo()
        viewModelScope.launch(defaultDispatcher) {
            kmaJob?.cancel()
            kmaJob?.join()
            var trialCount = 0

            while (true) {
                try {
                    withTimeout(NETWORK_TIMEOUT) {
                        kmaJob = launch(defaultDispatcher) {
                            isDataValid = false
                            _isLoading.value = true

                            val today: String = formatToKmaDate(getCurrentKoreanDateTime())
                            val latestVillageBaseTime = getKmaBaseTime(roundOff = VILLAGE)
                            val latestHourlyBaseTime = getKmaBaseTime(roundOff = HOUR)

                            val cal = getCurrentKoreanDateTime()
                            cal.add(Calendar.DAY_OF_YEAR, -1)
                            val yesterday: String = formatToKmaDate(cal)
                            val yesterdayHourlyBaseTime = getKmaBaseTime(dayOffset = -1, roundOff = HOUR)

                            val t1hPageNo = 5
                            val rowCountShort = 6
                            val rowCount3h = 37
                            val rowCount5h = 61
                            val lowTempBaseTime = "0200"
                            val highTempBaseTime = "1100"


                            // Conditional tasks
                            var todayLowTempResponse: Deferred<Response<ForecastResponse>>? = null
                            var todayHighTempResponse: Deferred<Response<ForecastResponse>>? = null

                            val fetchingStartTime = System.currentTimeMillis()


                            // The largest chunk, at most 290 + 290 + (290 - 72) + 2 * 3, which is about 800.
                            val futureDaysResponse: Deferred<Response<ForecastResponse>> = async(retrofitDispatcher) {
                                val maxDaySpan = 3  // For today, tomorrow and D+2
                                val numOfRow = (
                                        (24 + VILLAGE_EXTRA_ROWS) * maxDaySpan
                                                // No past forecasts
                                                // +1 because the earliest fcstTime = baseTime + 1
                                                // % 24 for 23 -> 24 -> 0
                                                - ((latestVillageBaseTime.hour.toInt() / 100) + 1 ) % 24
                                                - 6  // Only up to D+2 17:00 (not including data for 18:00)
                                        ) * VILLAGE_ROWS_PER_HOUR
                                + VILLAGE_EXTRA_ROWS * maxDaySpan  // TMN, TMX for 3 days
                                WeatherApi.service.getVillageWeather(
                                    baseDate = latestVillageBaseTime.date,  // Yesterday(23:00 only) or today
                                    baseTime = latestVillageBaseTime.hour,
                                    numOfRows = numOfRow,
                                    nx = baseCoordinatesXy.nx,
                                    ny = baseCoordinatesXy.ny,
                                )
                            }

                            if (latestVillageBaseTime.hour.toInt() > 200) {
                                val numOfRows = if (latestVillageBaseTime.hour.toInt() == 500) {
                                    rowCount3h  // 3:00, 4:00, 5:00
                                } else {
                                    rowCount5h  // 3:00, ..., 7:00
                                }
                                todayLowTempResponse = async(retrofitDispatcher) {
                                    WeatherApi.service.getVillageWeather(
                                        baseDate = today,
                                        baseTime = lowTempBaseTime,  // fsctTime starts from 03:00 AM
                                        numOfRows = numOfRows,
                                        nx = baseCoordinatesXy.nx,
                                        ny = baseCoordinatesXy.ny,
                                    )
                                }
                            }

                            if (latestVillageBaseTime.hour.toInt() > 1100) {
                                val numOfRows = if (latestVillageBaseTime.hour.toInt() == 1400) {
                                    rowCount3h  // 12:00, 13:00, 14:00
                                } else {
                                    rowCount5h  // 12:00, ..., 16:00
                                }
                                todayHighTempResponse = async(retrofitDispatcher) {
                                    WeatherApi.service.getVillageWeather(
                                        baseDate = today,
                                        baseTime = highTempBaseTime,  // fsctTime starts from the noon
                                        numOfRows = numOfRows,
                                        nx = baseCoordinatesXy.nx,
                                        ny = baseCoordinatesXy.ny,
                                    )
                                }
                            }

                            val todayHourlyTempResponse = async(retrofitDispatcher) {
                                WeatherApi.service.getShortTermWeather(
                                    baseDate = latestHourlyBaseTime.date,
                                    baseTime = latestHourlyBaseTime.hour,  // 14:50 -> 13:00, 15:00 -> 14:00
                                    numOfRows = 30,  // [LGT -> PTY -> RN1 -> SKY -> TH1] -> REH -> UUU -> VVV -> ...
                                    nx = baseCoordinatesXy.nx,
                                    ny = baseCoordinatesXy.ny,
                                )
                            }

                            val yesterdayHourlyTempResponse = async(retrofitDispatcher) {
                                WeatherApi.service.getShortTermWeather(
                                    baseDate = yesterdayHourlyBaseTime.date,
                                    baseTime = yesterdayHourlyBaseTime.hour,  // 14:50 -> 13:00, 15:00 -> 14:00
                                    numOfRows = rowCountShort,
                                    pageNo = t1hPageNo,  // LGT -> PTY -> RN1 -> SKY -> [TH1] -> REH -> UUU -> VVV -> ...
                                    nx = baseCoordinatesXy.nx,
                                    ny = baseCoordinatesXy.ny,
                                )

                                /* KMA often emits the data earlier, at which expires the oldest observed data which is the following lines try to retrieve.
                                delay(briefDelayMilliSec)
                                val observedCal = getCurrentKoreanDateTime()
                                observedCal.add(Calendar.DAY_OF_YEAR, -1)
                                observedCal.add(Calendar.HOUR_OF_DAY, 1)
                                val observedBaseTime = getKmaBaseTime(
                                    cal = observedCal,
                                    roundOff = HOUR,
                                    isQuickPublish = false
                                )

                                WeatherApi.service.getObservedWeather(
                                    baseDate = observedBaseTime.date,
                                    baseTime = observedBaseTime.hour,
                                    nx = baseCoordinatesXy.nx,
                                    ny = baseCoordinatesXy.ny,
                                )
                                */
                            }

                            val yesterdayLowTempResponse = async(retrofitDispatcher) {
                                WeatherApi.service.getShortTermWeather(
                                    baseDate = yesterday,
                                    baseTime = lowTempBaseTime,  // fsctTime starts from 03:00 AM
                                    numOfRows = rowCountShort,
                                    pageNo = t1hPageNo,  // LGT -> PTY -> RN1 -> SKY -> [TH1] -> REH -> UUU -> VVV -> ...
                                    nx = baseCoordinatesXy.nx,
                                    ny = baseCoordinatesXy.ny,
                                )
                            }

                            val yesterdayHighTempResponse = async(retrofitDispatcher) {
                                WeatherApi.service.getShortTermWeather(
                                    baseDate = yesterday,
                                    baseTime = highTempBaseTime,  // fsctTime starts from the noon
                                    numOfRows = rowCountShort,
                                    pageNo = t1hPageNo,  // LGT -> PTY -> RN1 -> SKY -> [TH1] -> REH -> UUU -> VVV -> ...
                                    nx = baseCoordinatesXy.nx,
                                    ny = baseCoordinatesXy.ny,
                                )
                            }

                            // Gather the data.
                            val yesterdayHighTempData: List<ForecastResponse.Item> = yesterdayHighTempResponse.await().body()?.response?.body?.items?.item ?: emptyList()
                            val yesterdayLowTempData: List<ForecastResponse.Item> = yesterdayLowTempResponse.await().body()?.response?.body?.items?.item ?: emptyList()
                            val yesterdayHourlyTempData: List<ForecastResponse.Item> = yesterdayHourlyTempResponse.await().body()?.response?.body?.items?.item ?: emptyList()
                            val todayHourlyData: List<ForecastResponse.Item> = todayHourlyTempResponse.await().body()?.response?.body?.items?.item?.filter { it.fcstDate == today.toInt() } ?: emptyList()
                            val todayHighTempData: List<ForecastResponse.Item> = todayHighTempResponse?.await()?.body()?.response?.body?.items?.item ?: emptyList()
                            val todayLowTempData: List<ForecastResponse.Item> = todayLowTempResponse?.await()?.body()?.response?.body?.items?.item ?: emptyList()
                            val futureData: List<ForecastResponse.Item> = futureDaysResponse.await().body()?.response?.body?.items?.item ?: emptyList()  // To be filtered

                            // Log for debugging.
                            for (i in yesterdayHighTempData) Log.d("D${DayOfInterest.Yesterday.dayOffset}-${CharacteristicTempType.Highest.descriptor}", "$i")
                            for (i in yesterdayLowTempData) Log.d("D${DayOfInterest.Yesterday.dayOffset}-${CharacteristicTempType.Lowest.descriptor}", "$i")
                            for (i in yesterdayHourlyTempData) Log.d("D${DayOfInterest.Yesterday.dayOffset}-$HOURLY_TEMPERATURE_TAG", "$i")
                            for (i in todayHourlyData) Log.d("D${DayOfInterest.Today.dayOffset}-$HOURLY_TEMPERATURE_TAG", "$i")
                            for (i in todayHighTempData) Log.d("D${DayOfInterest.Today.dayOffset}-${CharacteristicTempType.Highest.descriptor}", "$i")
                            for (i in todayLowTempData) Log.d("D${DayOfInterest.Today.dayOffset}-${CharacteristicTempType.Lowest.descriptor}", "$i")
                            for (i in futureData) Log.d("D${DayOfInterest.Tomorrow.dayOffset}-${CharacteristicTempType.Highest.descriptor}+", "$i")

                            logElapsedTime(TAG, "${futureData.size} items and more", fetchingStartTime)

                            launch { refreshHourlyTemp(todayHourlyData, yesterdayHourlyTempData) }
                            launch { refreshAllDailyTemp(yesterdayHighTempData, yesterdayLowTempData, todayHighTempData, todayLowTempData, futureData) }
                            launch { refreshRainfall(todayHourlyData, futureData) }
                        }
                        kmaJob?.join()
                        adjustCharTemp()
                        isDataValid = true
                    }
                    break
                } catch (e: Exception) {
                    if (++trialCount >= NETWORK_MAX_RETRY) {  // Maximum count of trials has been reached.
                        Log.e(TAG, "Cannot retrieve weather data.\n$e")
                        if (e is UnknownHostException) {
                            _toastMessage.value = OneShotEvent(R.string.weather_retrieving_failure_network)
                        } else {
                            _toastMessage.value = OneShotEvent(R.string.weather_retrieving_failure_general)
                        }
                        break
                    } else {
                        Log.w(TAG, "Failed to retrieve weather data.\n$e")
                        runBlocking { delay(200L) }
                    }
                } finally {
                    _isLoading.value = false
                }
            }
        }
    }

    /**
     * Update the rainfall status of today,
     * based on [todayHourlyData] and [futureData].
     * if there are data from the same base time, [todayHourlyData] take the priority.
     * Always called from a ViewModelScope.
     * */
    private fun refreshRainfall(
        todayHourlyData: List<ForecastResponse.Item>,
        futureData: List<ForecastResponse.Item>,
    ) {
        val cal = getCurrentKoreanDateTime()
        val today = formatToKmaDate(cal).toInt()
        val primaryCoveredHourMax: Int? = todayHourlyData.maxOfOrNull { it.fcstTime }

        // Remove duplicate data according to the priority (More recent data is preferred.)
        val hourlyRainfallData = todayHourlyData.filter { it.category == RAIN_TAG }
        val futureTodayRainfallData: List<ForecastResponse.Item> = futureData.filter {
            (it.category == RAIN_TAG) and (it.fcstDate == today) and (it.fcstTime > (primaryCoveredHourMax ?: 0))
        }
        val rainfallData: List<ForecastResponse.Item> = hourlyRainfallData + futureTodayRainfallData

        // Process the organized data.
        val rainingHours = arrayListOf<Int>()
        val snowingHours = arrayListOf<Int>()
        for (i in rainfallData) {
            Log.d(RAIN_TAG, "$i")
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
                _rainfallStatus.value = Sky.Good
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
    }

    /**
     * Request characteristic temperatures(e.g. the highest/lowest temperatures of the day) of all DayOfInterest.
     * All the jobs are parallel.
     * */
    private suspend fun refreshAllDailyTemp(
        yesterdayHighTempData: List<ForecastResponse.Item>,
        yesterdayLowTempData: List<ForecastResponse.Item>,
        todayHighTempData: List<ForecastResponse.Item>,
        todayLowTempData: List<ForecastResponse.Item>,
        futureData: List<ForecastResponse.Item>
    ) {
        coroutineScope {
            val todayData: ArrayList<ForecastResponse.Item> = arrayListOf()
            val d1Data: ArrayList<ForecastResponse.Item> = arrayListOf()
            val d2Data: ArrayList<ForecastResponse.Item> = arrayListOf()

            val cal = getCurrentKoreanDateTime()
            val today = formatToKmaDate(cal).toInt()
            cal.add(Calendar.DAY_OF_YEAR, 1)
            val d1 = formatToKmaDate(cal).toInt()
            cal.add(Calendar.DAY_OF_YEAR, 1)
            val d2 = formatToKmaDate(cal).toInt()

            for (item in futureData + todayHighTempData + todayLowTempData) {
                if (item.category == VILLAGE_TEMPERATURE_TAG
                    || item.category == HIGH_TEMPERATURE_TAG
                    || item.category == LOW_TEMPERATURE_TAG
                ) {
                    Log.d("KMA", "$item")
                    when (item.fcstDate) {
                        today -> todayData.add(item)
                        d1 -> d1Data.add(item)
                        d2 -> d2Data.add(item)
                    }
                }
            }

            launch { refreshYesterdayCharacteristicTemp(yesterdayHighTempData, yesterdayLowTempData) }
            launch { refreshDailyTemp(today, DayOfInterest.Today, todayData) }
            launch { refreshDailyTemp(d1, DayOfInterest.Tomorrow, d1Data) }
            launch { refreshDailyTemp(d2, DayOfInterest.Day2, d2Data) }
        }
        buildDailyTemps()
    }

    private fun buildDailyTemps() {
        val cal = getCurrentKoreanDateTime().also {
            it.add(Calendar.DAY_OF_YEAR, -2)
        }
        val locale = if (Locale.getDefault() == Locale.KOREA) {
            Locale.KOREA
        } else {
            Locale.US
        }

        val dailyTempsBuilder: ArrayList<DailyTemperature> = arrayListOf()
        dailyTempsBuilder.add(dailyTempHeader)
        for (i in highestTemps.indices) {
            val isToday = i == 1

            cal.add(Calendar.DAY_OF_YEAR, 1)
            val day = cal.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT_FORMAT, locale)
            val highest = if (highestTemps[i] != null) {
                "${highestTemps[i].toString()}\u00B0"
            } else {
                stringForNull
            }
            val lowest = if (lowestTemps[i] != null) {
                "${lowestTemps[i].toString()}\u00B0"
            } else {
                stringForNull
            }
            dailyTempsBuilder.add(DailyTemperature(isToday, day ?: "", highest, lowest))
        }
        _dailyTemps.value = dailyTempsBuilder.toList()
    }

    private fun refreshYesterdayCharacteristicTemp(
        yesterdayHighTempData: List<ForecastResponse.Item>?,
        yesterdayLowTempData: List<ForecastResponse.Item>?
    ) {
        highestTemps[0] = yesterdayHighTempData?.filter {
            it.category == HOURLY_TEMPERATURE_TAG
        }?.maxByOrNull { it.fcstValue.toInt() }?.fcstValue?.toInt()

        lowestTemps[0] = yesterdayLowTempData?.filter {
            it.category == HOURLY_TEMPERATURE_TAG
        }?.minByOrNull { it.fcstValue.toInt() }?.fcstValue?.toInt()
    }

    /**
     * Refresh [highestTemps] and [lowestTemps] with [temperatureData]
     * of which [category] is [VILLAGE_TEMPERATURE_TAG], etc.
     * */
    private fun refreshDailyTemp(
        fcstDate: Int,
        day: DayOfInterest,
        temperatureData: List<ForecastResponse.Item>
    ) {
        for (item in temperatureData) {
            if (item.fcstDate == fcstDate) {
                // fcstValues of TMX, TMN are Float, so round to Integer.
                compareAndUpdateFormerDailyTemp(day, CharacteristicTempType.Highest, item.fcstValue.toFloat().roundToInt())
                compareAndUpdateFormerDailyTemp(day, CharacteristicTempType.Lowest, item.fcstValue.toFloat().roundToInt())
            }
        }
    }

    private fun compareAndUpdateFormerDailyTemp(
        day: DayOfInterest,
        type: CharacteristicTempType,
        newValue: Int,
    ) {
        val index = day.dayOffset + 1  // Yesterday -> 0
        val tempList = when (type) {
            CharacteristicTempType.Highest -> highestTemps
            CharacteristicTempType.Lowest -> lowestTemps
        }
        val oldValue = tempList[index]

        if (oldValue == null) {
            tempList[index] = newValue
        } else {
            when (type) {
                CharacteristicTempType.Highest -> {
                    if (newValue > oldValue) tempList[index] = newValue
                }
                CharacteristicTempType.Lowest -> {
                    if (newValue < oldValue) tempList[index] = newValue
                }
            }
        }
    }

    private fun refreshHourlyTemp(
        todayHourlyData: List<ForecastResponse.Item>,
        yesterdayHourlyTempData: List<ForecastResponse.Item>
    ) {
        val closestHour = todayHourlyData.minOf { it.fcstTime }
        var todayTemp: Int? = null
        var yesterdayTemp: Int? = null

        for (i in todayHourlyData) {
            if (i.fcstTime == closestHour && i.category == HOURLY_TEMPERATURE_TAG) {
                todayTemp = i.fcstValue.toInt()
                break
            }
        }

        for (i in yesterdayHourlyTempData) {
            if (i.fcstTime == closestHour && i.category == HOURLY_TEMPERATURE_TAG) {
                yesterdayTemp = i.fcstValue.toInt()
                break
            }
        }

        Log.d(TAG, "T1H(at ${closestHour / 100}): $yesterdayTemp -> $todayTemp")

        if (todayTemp != null) {
            _hourlyTempToday.value = todayTemp
            if (yesterdayTemp != null) {
                _hourlyTempDiff.value = todayTemp - yesterdayTemp
            }
        }
    }

    /**
     * Often, TMX / TMN values are lower / higher than hourly value.
     * If the shown hourly value is higher than TMX than the user might doubt the reliability: adjust.
     */
    private fun adjustCharTemp() {
        hourlyTempToday?.let { tt ->
            highestTemps[1]?.let {
                if (tt > it) highestTemps[1] = tt
            }

            lowestTemps[1]?.let {
                if (tt < it) lowestTemps[1] = tt
            }
        }

        hourlyTempYesterday?.let { ty ->
            highestTemps[0]?.let {
                if (ty > it) highestTemps[0] = ty
            }
            lowestTemps[0]?.let {
                if (ty < it) lowestTemps[0] = ty
            }
        }
    }

    /**
     * Start location update and eventually retrieve new weather data based on the location.
     * */
    fun startLocationUpdate() {
        if (ActivityCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            locationClient.lastLocation.addOnSuccessListener {
                updateLocationAndWeather(CoordinatesLatLon(lat = it.latitude, lon = it.longitude))
            }

            // A costly process to update the current location. Might take about 10 seconds.
            locationClient.requestLocationUpdates(currentLocationRequest, locationCallback, Looper.getMainLooper())
        }
    }

    fun stopLocationUpdate() {
        locationClient.removeLocationUpdates(locationCallback)
    }

    /**
     * Update [_cityName] and [baseCoordinatesXy], then request new weather data based on [baseCoordinatesXy].
     * */
    private fun updateLocationAndWeather(coordinates: CoordinatesXy, locatedCityName: String) {
        if (locatedCityName != _cityName.value) {  // i.e. A new base location
            Log.d(LOCATION_TAG, "A new city\t: $locatedCityName")
            _cityName.value = locatedCityName

            // Request new data for the location.
            baseCoordinatesXy = coordinates
            requestAllWeatherData()
        } else {
            Log.d(LOCATION_TAG, "The same city, no need to renew.")
        }
    }

    fun updateLocationAndWeather(coordinates: CoordinatesLatLon) {
        baseCoordinatesXy = convertToXy(coordinates)

        val geocoder = KoreanGeocoder(context)
        val address = geocoder.getAddress(coordinates)
        val locatedCityName = getCityName(address)
        getDistrictName(address)?.let {
            _districtName.value = it
            Log.d(LOCATION_TAG, "A new district: $it")
        }

        if (locatedCityName == null) {
            Log.e(LOCATION_TAG, "Error retrieving a city name(${coordinates.lat}, ${coordinates.lon}).")
        } else {
            val xy = convertToXy(coordinates)
            Log.d(LOCATION_TAG, "A new location: (${xy.nx}, ${xy.ny})")
            updateLocationAndWeather(xy, locatedCityName)
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            super.onLocationResult(locationResult)
            locationResult.lastLocation?.let {
                Log.d(LOCATION_TAG, "Lat: ${it.latitude}\nLon: ${it.longitude}")
                updateLocationAndWeather(CoordinatesLatLon(it.latitude, it.longitude))
            }
        }
    }

    /**
     * Return true if it refreshes(i.e. refresh request is valid),
     * return false if it won't refresh(e.g. The current data are up-to-date).
     * */
    fun onRefreshClicked() {
        Log.d(TAG, "onRefreshClicked")
        val kmaTime = getKmaBaseTime(roundOff = HOUR)
        if (kmaTime.isLaterThan(lastHourBaseTime) || !isDataValid) {
            requestAllWeatherData()
        }
    }

    fun showLoading(milliSec: Long) {
        viewModelScope.launch {
            _isLoading.value = true
            delay(milliSec)
            _isLoading.value = false
            _toastMessage.value = OneShotEvent(R.string.refresh_up_to_date)
        }
    }

    private fun getDefaultDailyTemps(): List<DailyTemperature> {
        val dailyTempPlaceholder = DailyTemperature(
            false, stringForNull, stringForNull, stringForNull
        )

        val builder: ArrayList<DailyTemperature> = arrayListOf(dailyTempHeader)
        for (i in 0 until temperatureCount) {
            builder.add(dailyTempPlaceholder)
        }

        return builder.toList()
    }

    companion object {
        private const val ONE_MINUTE: Long = 60000
        val currentLocationRequest: LocationRequest = LocationRequest.create().apply {
            interval = ONE_MINUTE
            fastestInterval = ONE_MINUTE / 4
        }
    }
}

enum class CharacteristicTempType(val descriptor: String) {
    Highest("T_H"), Lowest("T_L")
}

enum class DayOfInterest(val dayOffset: Int) {
    Yesterday(-1), Today(0), Tomorrow(1), Day2(2)
}

enum class RainfallType(val code: Int) {
    Raining(1), Mixed(2), Snowing(3), Shower(4),
    // None(0), LightRain(5), LightRainAndSnow(6), LightSnow(7)
}

sealed class Sky {
    object Undetermined: Sky()
    object Good : Sky()
    sealed class Bad(val startingHour: Int, val endingHour: Int): Sky() {
        class Mixed(startingHour: Int, endingHour: Int): Bad(startingHour, endingHour)
        class Rainy(startingHour: Int, endingHour: Int): Bad(startingHour, endingHour)
        class Snowy(startingHour: Int, endingHour: Int): Bad(startingHour, endingHour)
    }
}

class DailyTemperature(val isToday: Boolean, val day: String, val highest: String, val lowest: String)

class WeatherViewModelFactory(
    private val application: Application,
    private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WeatherViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return WeatherViewModel(application, userPreferencesRepository
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
