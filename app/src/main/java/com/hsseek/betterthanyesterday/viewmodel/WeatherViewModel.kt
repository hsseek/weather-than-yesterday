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
private const val VILLAGE_TEMPERATURE_TAG = "TMP"
private const val LOW_TEMPERATURE_TAG = "TMN"
private const val HIGH_TEMPERATURE_TAG = "TMX"
private const val HOURLY_TEMPERATURE_TAG = "T1H"
private const val RAIN_TAG = "PTY"
private const val NETWORK_TIMEOUT = 1200L
private const val NETWORK_ADDITIONAL_TIMEOUT = 400L
private const val NETWORK_PAUSE = 150L
private const val NETWORK_MAX_RETRY = 3

class WeatherViewModel(
    application: Application,
    private val userPreferencesRepository: UserPreferencesRepository,
) : AndroidViewModel(application) {
    private val context = application

    private val stringForNull = context.getString(R.string.null_value)

    private val retrofitDispatcher = Dispatchers.IO
    private val defaultDispatcher = Dispatchers.Default
    private var kmaJob: Job? = null
    var isDataValid: Boolean = true
        private set
    private val _isLoading = mutableStateOf(true)
    val isLoading: Boolean
        get() = _isLoading.value
    private val _showLandingScreen = mutableStateOf(true)
    val showLandingScreen: Boolean
        get() = _showLandingScreen.value

    private var lastHourBaseTime: KmaTime = getKmaBaseTime(roundOff = HOUR)

    private var baseCoordinatesXy = CoordinatesXy(60, 127)

    // Variables regarding location.
    private val locationClient = LocationServices.getFusedLocationProviderClient(context)
    var toShowLocatingDialog = mutableStateOf(false)
        private set
    var isUpdatingLocation: Boolean = false

    // The forecast location is an input from UI.
    var locatingMethod: LocatingMethod? = null
        private set

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
            logCoroutineContext("requestAllWeatherData()")

            // Cancel previous job if active.
            kmaJob?.cancel()
            kmaJob?.join()
            var trialCount = 0

            while (trialCount < NETWORK_MAX_RETRY) {
                try {
                    withTimeout(NETWORK_TIMEOUT + trialCount * NETWORK_ADDITIONAL_TIMEOUT) {
                        kmaJob = launch(defaultDispatcher) {
                            isDataValid = false
                            _isLoading.value = true

                            val today: String = formatToKmaDate(getCurrentKoreanDateTime())
                            val latestVillageBaseTime = getKmaBaseTime(roundOff = VILLAGE)  // 2:00 for 2:11 ~ 5:10
                            val latestHourlyBaseTime = getKmaBaseTime(roundOff = HOUR)  // 2:00 for 3:00 ~ 3:59

                            val cal = getCurrentKoreanDateTime()
                            cal.add(Calendar.DAY_OF_YEAR, -1)
                            val yesterday: String = formatToKmaDate(cal)
                            val yesterdayHourlyBaseTime = getKmaBaseTime(dayOffset = -1, roundOff = HOUR)

                            val t1hPageNo = 5
                            val rowCountShort = 6
                            val lowTempBaseTime = "0200"  // From 3:00
                            val highTempBaseTime = "1100"  // From 12:00
                            val characteristicTempHourSpan = 5  // Monitors 5 hours(3:00 ~ 8:00 and 12:00 ~ 17:00)

                            // Conditional tasks, only if the current time passed baseTime
                            var todayLowTempResponse: Deferred<Response<ForecastResponse>>? = null
                            var todayHighTempResponse: Deferred<Response<ForecastResponse>>? = null

                            val fetchingStartTime = System.currentTimeMillis()

                            // The largest chunk, at most 290 + 290 + (290 - 72) + 2 * 3, which is about 800.
                            /* val futureDaysResponse: Deferred<Response<ForecastResponse>> = async(retrofitDispatcher) {
                                val maxDaySpan = 3  // For today, tomorrow and D+2
                                val numOfRow = (
                                        24 * maxDaySpan
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
                            } */

                            // The response includes duplicate data of earlier hours of hourly data.
                            // However, only cutting tails is allowed, retrieve the whole data.
                            val futureTodayResponse: Deferred<Response<ForecastResponse>> = async(retrofitDispatcher) {
                                val remainingHours = (2400 - getEarliestFcstHour(latestVillageBaseTime)) / 100
                                WeatherApi.service.getVillageWeather(
                                    baseDate = latestVillageBaseTime.date,  // Yesterday(23:00 only) or today
                                    baseTime = latestVillageBaseTime.hour,
                                    numOfRows = remainingHours * VILLAGE_ROWS_PER_HOUR,
                                    nx = baseCoordinatesXy.nx,
                                    ny = baseCoordinatesXy.ny,
                                )
                            }

                            // Responses to extract the highest/lowest temperatures of tomorrow and D2
                            val futureDayHourSpan = 4  // 300 ~ 700, 1200 ~ 1600
                            val futureDayRowCount = futureDayHourSpan * VILLAGE_ROWS_PER_HOUR + VILLAGE_EXTRA_ROWS
                            val baseTime1 = "1100"
                            val baseTime2 = "2300"  // of yesterday
                            val pagesPerDay: Int = 24 / futureDayHourSpan

                            val futureDayBaseTime: String
                            val futureDayBaseDate: String
                            val tomorrowHighTempPageNo: Int
                            val tomorrowLowTempPageNo: Int
                            if (latestVillageBaseTime.hour.toInt() in baseTime1.toInt() until baseTime2.toInt()) {
                                // fcstTIme starts from today 12:00.
                                futureDayBaseDate = today
                                futureDayBaseTime = baseTime1
                                tomorrowLowTempPageNo = 5
                                tomorrowHighTempPageNo = 7
                            } else {
                                // fcstTIme starts from today 00:00.
                                futureDayBaseDate = yesterday
                                futureDayBaseTime = baseTime2
                                tomorrowLowTempPageNo = 8
                                tomorrowHighTempPageNo = 10
                            }

                            val tomorrowHighTempResponse = async(retrofitDispatcher) {
                                WeatherApi.service.getVillageWeather(
                                    baseDate = futureDayBaseDate,  // Yesterday(23:00 only) or today
                                    baseTime = futureDayBaseTime,
                                    numOfRows = futureDayRowCount,
                                    pageNo = tomorrowHighTempPageNo,
                                    nx = baseCoordinatesXy.nx,
                                    ny = baseCoordinatesXy.ny,
                                )
                            }

                            val tomorrowLowTempResponse = async(retrofitDispatcher) {
                                WeatherApi.service.getVillageWeather(
                                    baseDate = futureDayBaseDate,  // Yesterday(23:00 only) or today
                                    baseTime = futureDayBaseTime,
                                    numOfRows = futureDayRowCount,
                                    pageNo = tomorrowLowTempPageNo,
                                    nx = baseCoordinatesXy.nx,
                                    ny = baseCoordinatesXy.ny,
                                )
                            }

                            val d2HighTempResponse = async(retrofitDispatcher) {
                                WeatherApi.service.getVillageWeather(
                                    baseDate = futureDayBaseDate,  // Yesterday(23:00 only) or today
                                    baseTime = futureDayBaseTime,
                                    numOfRows = futureDayRowCount,
                                    pageNo = tomorrowHighTempPageNo + pagesPerDay,
                                    nx = baseCoordinatesXy.nx,
                                    ny = baseCoordinatesXy.ny,
                                )
                            }

                            val d2LowTempResponse = async(retrofitDispatcher) {
                                WeatherApi.service.getVillageWeather(
                                    baseDate = futureDayBaseDate,  // Yesterday(23:00 only) or today
                                    baseTime = futureDayBaseTime,
                                    numOfRows = futureDayRowCount,
                                    pageNo = tomorrowLowTempPageNo + pagesPerDay,
                                    nx = baseCoordinatesXy.nx,
                                    ny = baseCoordinatesXy.ny,
                                )
                            }

                            if (latestVillageBaseTime.hour.toInt() >= lowTempBaseTime.toInt()) {  // As soon as the latest lowest temperature span available
                                val numOfRows = if (latestVillageBaseTime.hour.toInt() == lowTempBaseTime.toInt() + VILLAGE_HOUR_INTERVAL * 100) {  // During baseTime = 500
                                    getRowCount(VILLAGE_HOUR_INTERVAL)  // Missing hours span only an interval: 3:00, 4:00, 5:00
                                } else {  // After baseTime became 800, the lowest temperature won't be renewed.
                                    getRowCount(characteristicTempHourSpan)  // Full span: 3:00, ..., 7:00
                                }
                                Log.d("D${DayOfInterest.Today.dayOffset}-${CharacteristicTempType.Lowest.descriptor}", "$numOfRows rows")

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

                            if (latestVillageBaseTime.hour.toInt() >= highTempBaseTime.toInt()) {  // As soon as the latest highest temperature span available
                                val numOfRows = if (latestVillageBaseTime.hour.toInt() == highTempBaseTime.toInt() + VILLAGE_HOUR_INTERVAL * 100) {  // During baseTime = 1400
                                    getRowCount(VILLAGE_HOUR_INTERVAL)  // Missing hours span only an interval: 12:00, 13:00, 14:00
                                } else {  // After baseTime became 1700, the highest temperature won't be renewed.
                                    getRowCount(characteristicTempHourSpan)  // Full span: 12:00, ..., 16:00
                                }
                                Log.d("D${DayOfInterest.Today.dayOffset}-${CharacteristicTempType.Highest.descriptor}", "$numOfRows rows")
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
                            val futureTodayData: List<ForecastResponse.Item> = futureTodayResponse.await().body()?.response?.body?.items?.item ?: emptyList()
                            val tomorrowHighTempData = tomorrowHighTempResponse.await().body()?.response?.body?.items?.item ?: emptyList()
                            val tomorrowLowTempData = tomorrowLowTempResponse.await().body()?.response?.body?.items?.item ?: emptyList()
                            val d2HighTempData = d2HighTempResponse.await().body()?.response?.body?.items?.item ?: emptyList()
                            val d2LowTempData = d2LowTempResponse.await().body()?.response?.body?.items?.item ?: emptyList()
                            val yesterdayHighTempData = yesterdayHighTempResponse.await().body()?.response?.body?.items?.item ?: emptyList()
                            val yesterdayLowTempData = yesterdayLowTempResponse.await().body()?.response?.body?.items?.item ?: emptyList()
                            val yesterdayHourlyTempData = yesterdayHourlyTempResponse.await().body()?.response?.body?.items?.item ?: emptyList()
                            val todayHourlyData = todayHourlyTempResponse.await().body()?.response?.body?.items?.item?.filter { it.fcstDate == today.toInt() } ?: emptyList()
                            val todayHighTempData = todayHighTempResponse?.await()?.body()?.response?.body?.items?.item ?: emptyList()
                            val todayLowTempData = todayLowTempResponse?.await()?.body()?.response?.body?.items?.item ?: emptyList()

                            // Log for debugging.
                            var size = 0
                            for (i in yesterdayHighTempData) {
                                size += 1
                                Log.d("D${DayOfInterest.Yesterday.dayOffset}-${CharacteristicTempType.Highest.descriptor}", "$i")
                            }
                            for (i in yesterdayLowTempData) {
                                size += 1
                                Log.d("D${DayOfInterest.Yesterday.dayOffset}-${CharacteristicTempType.Lowest.descriptor}", "$i")
                            }
                            for (i in yesterdayHourlyTempData) {
                                size += 1
                                Log.d("D${DayOfInterest.Yesterday.dayOffset}-$HOURLY_TEMPERATURE_TAG", "$i")
                            }
                            for (i in todayHourlyData) {
                                size += 1
                                Log.d("D${DayOfInterest.Today.dayOffset}-$HOURLY_TEMPERATURE_TAG", "$i")
                            }
                            for (i in todayHighTempData) {
                                size += 1
                                if (i.category == VILLAGE_TEMPERATURE_TAG || i.category == HIGH_TEMPERATURE_TAG) {
                                    Log.d("D${DayOfInterest.Today.dayOffset}-${CharacteristicTempType.Highest.descriptor}", "$i")
                                }
                            }
                            for (i in todayLowTempData) {
                                size += 1
                                if (i.category == VILLAGE_TEMPERATURE_TAG || i.category == LOW_TEMPERATURE_TAG) {
                                    Log.d("D${DayOfInterest.Today.dayOffset}-${CharacteristicTempType.Lowest.descriptor}", "$i")
                                }
                            }
                            for (i in futureTodayData) {
                                size += 1
                                if (i.category == VILLAGE_TEMPERATURE_TAG) {
                                    Log.d("D${DayOfInterest.Today.dayOffset}", "$i")
                                }
                            }
                            for (i in tomorrowHighTempData) {
                                size += 1
                                if (i.category == VILLAGE_TEMPERATURE_TAG || i.category == HIGH_TEMPERATURE_TAG) {
                                    Log.d("D${DayOfInterest.Tomorrow.dayOffset}-${CharacteristicTempType.Highest.descriptor}", "$i")
                                }
                            }
                            for (i in tomorrowLowTempData) {
                                size += 1
                                if (i.category == VILLAGE_TEMPERATURE_TAG || i.category == LOW_TEMPERATURE_TAG) {
                                    Log.d("D${DayOfInterest.Tomorrow.dayOffset}-${CharacteristicTempType.Lowest.descriptor}", "$i")
                                }
                            }
                            for (i in d2HighTempData) {
                                size += 1
                                if (i.category == VILLAGE_TEMPERATURE_TAG || i.category == HIGH_TEMPERATURE_TAG) {
                                    Log.d("D${DayOfInterest.Day2.dayOffset}-${CharacteristicTempType.Highest.descriptor}", "$i")
                                }
                            }
                            for (i in d2LowTempData) {
                                size += 1
                                if (i.category == VILLAGE_TEMPERATURE_TAG || i.category == LOW_TEMPERATURE_TAG) {
                                    Log.d("D${DayOfInterest.Day2.dayOffset}-${CharacteristicTempType.Lowest.descriptor}", "$i")
                                }
                            }

                            logElapsedTime(TAG, "$size items", fetchingStartTime)

                            // Refresh the hourly temperature and the difference.
                            launch { refreshHourlyTemp(todayHourlyData, yesterdayHourlyTempData) }

                            // Refresh the highest/lowest temperatures
                            launch { refreshCharacteristicTemp(DayOfInterest.Yesterday, yesterdayHighTempData, yesterdayLowTempData) }
                            launch { refreshTodayCharacteristicTemp(todayHighTempData, todayLowTempData, todayHourlyData, futureTodayData) }
                            launch { refreshCharacteristicTemp(DayOfInterest.Tomorrow, tomorrowHighTempData, tomorrowLowTempData) }
                            launch { refreshCharacteristicTemp(DayOfInterest.Day2, d2HighTempData, d2LowTempData) }

                            // Refresh the rainfall status.
                            launch { refreshRainfall(todayHourlyData, futureTodayData) }
                        }
                        kmaJob?.join()
                        adjustCharTemp()
                        buildDailyTemps()
                        isDataValid = true
                    }
                    _showLandingScreen.value = false
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
                        kmaJob?.cancel()
                        kmaJob?.join()
                        runBlocking { delay(NETWORK_PAUSE) }
                    }
                } finally {
                    kmaJob?.cancel()
                    _isLoading.value = false
                }
            }
        }
    }

    private fun getRowCount(hourSpan: Int): Int = hourSpan * VILLAGE_ROWS_PER_HOUR + 1

    /**
     * baseTime of 23:00 -> 0
     * baseTime of 3:00 -> 400
     * */
    private fun getEarliestFcstHour(baseTime: KmaTime): Int = ((baseTime.hour.toInt() / 100 + 1) % 24) * 100

    /**
     * Update the rainfall status of today,
     * based on [todayHourlyData] and [futureTodayData].
     * if there are data from the same base time, [todayHourlyData] take the priority.
     * Always called from a ViewModelScope.
     * */
    private fun refreshRainfall(
        todayHourlyData: List<ForecastResponse.Item>,
        futureTodayData: List<ForecastResponse.Item>,
    ) {
        val primaryCoveredHourMax: Int? = todayHourlyData.maxOfOrNull { it.fcstTime }

        // Remove duplicate data according to the priority (More recent data is preferred.)
        val hourlyRainfallData = todayHourlyData.filter { it.category == RAIN_TAG }
        val futureTodayRainfallData: List<ForecastResponse.Item> = futureTodayData.filter {
            (it.category == RAIN_TAG) and (it.fcstTime > (primaryCoveredHourMax ?: 0))
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

    private fun refreshCharacteristicTemp(
        day: DayOfInterest,
        yesterdayHighTempData: List<ForecastResponse.Item>?,
        yesterdayLowTempData: List<ForecastResponse.Item>?,
    ) {
        val index = day.dayOffset + 1  // Yesterday's data go to [0].
        highestTemps[index] = yesterdayHighTempData?.filter {
            it.category == HOURLY_TEMPERATURE_TAG || it.category == HIGH_TEMPERATURE_TAG
        }?.maxByOrNull { it.fcstValue.toFloat() }?.fcstValue?.toFloat()?.roundToInt()  // TMN, TMX values are Floats.

        lowestTemps[index] = yesterdayLowTempData?.filter {
            it.category == HOURLY_TEMPERATURE_TAG || it.category == LOW_TEMPERATURE_TAG
        }?.minByOrNull { it.fcstValue.toFloat() }?.fcstValue?.toFloat()?.roundToInt()
    }

    private fun refreshTodayCharacteristicTemp(
        todayHighTempData: List<ForecastResponse.Item>,
        todayLowTempData: List<ForecastResponse.Item>,
        todayHourlyData: List<ForecastResponse.Item>,
        futureTodayData: List<ForecastResponse.Item>,
    ) {
        val data = todayHighTempData + todayLowTempData + todayHourlyData + futureTodayData

        for (item in data) {
            if (item.category == VILLAGE_TEMPERATURE_TAG
                || item.category == HOURLY_TEMPERATURE_TAG
                || item.category == HIGH_TEMPERATURE_TAG
                || item.category == LOW_TEMPERATURE_TAG
            ) {
                // fcstValues of TMX, TMN are Float, so round to Integer.
                compareAndUpdateFormerDailyTemp(DayOfInterest.Today, CharacteristicTempType.Highest, item.fcstValue.toFloat().roundToInt())
                compareAndUpdateFormerDailyTemp(DayOfInterest.Today, CharacteristicTempType.Lowest, item.fcstValue.toFloat().roundToInt())
            }
        }
    }

    private fun compareAndUpdateFormerDailyTemp(
        @Suppress("SameParameterValue") day: DayOfInterest,
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
            if (isUpdatingLocation) stopLocationUpdate()

            locationClient.lastLocation.addOnSuccessListener {
                if (it != null) {
                    updateLocationAndWeather(CoordinatesLatLon(lat = it.latitude, lon = it.longitude))
                } else {
                    // e.g. On the very first boot of the device
                    Log.w(LOCATION_TAG, "FusedLocationProviderClient.lastLocation is null.")
                }
            }

            try {// A costly process to update the current location. Might take about 10 seconds.
                locationClient.requestLocationUpdates(currentLocationRequest, locationCallback, Looper.getMainLooper())
                isUpdatingLocation = true
            } catch (e: Exception) {
                Log.e(LOCATION_TAG, "Location update failed.", e)
            }
        }
    }

    fun stopLocationUpdate() {
        locationClient.removeLocationUpdates(locationCallback)
        isUpdatingLocation = false
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
        val addresses = geocoder.getAddresses(coordinates)
        val locatedCityName = getCityName(addresses)
        getDistrictName(addresses)?.let {
            _districtName.value = it
            Log.d(LOCATION_TAG, "District: $it")
        }

        if (locatedCityName == null) {
            Log.e(LOCATION_TAG, "Error retrieving a city name(${coordinates.lat}, ${coordinates.lon}).")
        } else {
            val xy = convertToXy(coordinates)
            Log.d(LOCATION_TAG, "Location: (${xy.nx}, ${xy.ny})")
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
    fun onRefreshClicked(isManual: Boolean = false) {
        Log.d(TAG, "onRefreshClicked")

        // Refresh weather data only if new data are available.
        val kmaTime = getKmaBaseTime(roundOff = HOUR)
        if (kmaTime.isLaterThan(lastHourBaseTime) || !isDataValid) {
            requestAllWeatherData()
        } else {
            if (isManual) {
                showLoading((210..420).random().toLong())
            }
        }

        // Refresh location on user's demand.
        if (locatingMethod == LocatingMethod.Auto) {
            startLocationUpdate()
        }
    }

    private fun showLoading(milliSec: Long) {
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

    fun onLandingScreenTimeout() {
        _showLandingScreen.value = false
    }

    companion object {
        private const val REQUEST_INTERVAL: Long = 60 * 60 * 1000
        val currentLocationRequest: LocationRequest = LocationRequest.create().apply {
            interval = REQUEST_INTERVAL
            fastestInterval = REQUEST_INTERVAL / 2
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
