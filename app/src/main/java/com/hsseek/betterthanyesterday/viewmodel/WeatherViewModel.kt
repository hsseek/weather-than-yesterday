package com.hsseek.betterthanyesterday.viewmodel

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.pm.PackageManager
import android.location.Address
import android.net.Uri
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.gson.JsonSyntaxException
import com.google.gson.stream.MalformedJsonException
import com.hsseek.betterthanyesterday.*
import com.hsseek.betterthanyesterday.data.ForecastRegion
import com.hsseek.betterthanyesterday.data.Language
import com.hsseek.betterthanyesterday.data.PresetRegion
import com.hsseek.betterthanyesterday.data.UserPreferencesRepository
import com.hsseek.betterthanyesterday.location.*
import com.hsseek.betterthanyesterday.location.convertToXy
import com.hsseek.betterthanyesterday.network.ForecastResponse
import com.hsseek.betterthanyesterday.network.WeatherApi
import com.hsseek.betterthanyesterday.util.*
import com.hsseek.betterthanyesterday.util.KmaHourRoundOff.Hour
import com.hsseek.betterthanyesterday.util.KmaHourRoundOff.Village
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.Response
import java.io.IOException
import java.net.UnknownHostException
import java.util.*
import kotlin.collections.HashSet
import kotlin.math.roundToInt

private const val TAG = "WeatherViewModel"
private const val VILLAGE_TEMPERATURE_TAG = "TMP"
private const val LOW_TEMPERATURE_TAG = "TMN"
private const val HIGH_TEMPERATURE_TAG = "TMX"
private const val HOURLY_TEMPERATURE_TAG = "T1H"
private const val RAIN_TAG = "PTY"
private const val NETWORK_TIMEOUT_MIN = 1_200L
private const val NETWORK_ADDITIONAL_TIMEOUT = 400L
private const val NETWORK_TIMEOUT_MAX = 6_400L
private const val NETWORK_PAUSE = 150L
private const val NETWORK_MAX_RETRY = 12

private const val CODED_SNACK_BAR_ID = 0
private const val HIGHLIGHTED_SETTING_ROW = 1  // If out of index, none will be highlighted.

class WeatherViewModel(
    application: Application,
    private val userPrefsRepo: UserPreferencesRepository,
) : AndroidViewModel(application) {
    val autoRegionCoordinate: CoordinatesXy = PresetRegion.Auto.xy

    private val context = application
    private val stringForNull = context.getString(R.string.null_value)

    private val retrofitDispatcher = Dispatchers.IO
    private val defaultDispatcher = Dispatchers.Default
    private var kmaJob: Job? = null
    private var searchRegionJob: Job? = null

    private val _isRefreshing = mutableStateOf(false)
    val isRefreshing: Boolean
        get() = _isRefreshing.value

    private val _showLandingScreen = mutableStateOf(true)
    val showLandingScreen: Boolean
        get() = _showLandingScreen.value

    private var lastSuccessfulTime: Calendar? = null
        private set(value) {
            field = value
            if (DEBUG_FLAG) Log.d(TAG, "last successful data retrieving hour: ${value?.get(Calendar.HOUR_OF_DAY)}")
        }

    var lastImplicitlyCheckedTime: Calendar? = null
        private set

    // Preferences
    private var languageCode: Int = Language.System.code
    var isLanguageChanged = false

    private val _isSimplified = mutableStateOf(false)
    val isSimplified: Boolean
        get() = _isSimplified.value

    var isAutoRefresh: Boolean = false
        private set

    private val _isDaybreakMode = mutableStateOf(false)
    val isDaybreakMode: Boolean
        get() = _isDaybreakMode.value

    private val _isPresetRegion = mutableStateOf(false)
    val isPresetRegion: Boolean
        get() = _isPresetRegion.value

    // Variables regarding location.
    private val locationClient = LocationServices.getFusedLocationProviderClient(context)
    private var isUpdatingLocation: Boolean = false

    // Information of the ForecastRegion
    var isForecastRegionAuto: Boolean = true
        private set
    lateinit var forecastRegion: ForecastRegion
        private set
    private lateinit var defaultRegionCandidates: Array<ForecastRegion>

    // Variables regarding ForecastRegion Dialog
    private val _toShowSearchRegionDialog = mutableStateOf(false)
    val toShowSearchRegionDialog: Boolean
        get() = _toShowSearchRegionDialog.value
    private val _toShowSearchRegionDialogLoading = mutableStateOf(false)
    val toShowSearchRegionDialogLoading: Boolean
        get() = _toShowSearchRegionDialogLoading.value

    // Entries displayed as RadioItems
    private val _forecastRegionCandidates: MutableState<List<ForecastRegion>> = mutableStateOf(emptyList())
    val forecastRegionCandidates: List<ForecastRegion>
        get() = _forecastRegionCandidates.value
    // The selected index of candidates
    private val _selectedForecastRegionIndex = mutableStateOf(if (isForecastRegionAuto) 0 else 1)
    val selectedForecastRegionIndex: Int
        get() = _selectedForecastRegionIndex.value

    // Displayed location names
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

    private val _rainfallStatus: MutableStateFlow<Sky> = MutableStateFlow(Sky.Undetermined)
    val rainfallStatus = _rainfallStatus.asStateFlow()

    val adNumber = (1..100).random()

    private val _toastMessage = MutableStateFlow(ToastEvent(0))
    val toastMessage = _toastMessage.asStateFlow()

    private val _exceptionSnackBarEvent = MutableStateFlow(SnackBarEvent(SnackBarContent(null, null) {} ))
    val exceptionSnackBarEvent = _exceptionSnackBarEvent.asStateFlow()

    private val _noticeSnackBarEvent = MutableStateFlow(SnackBarEvent(SnackBarContent(null, null) {} ))
    val noticeSnackBarEvent = _noticeSnackBarEvent.asStateFlow()

    /**
     * Update [forecastRegion], either retrieved from [startLocationUpdate] or directly from a fixed location.
     * [isSecondary] is true when it was a (confirming) result from the [locationCallback].
     * */
    private fun updateForecastRegion(region: ForecastRegion, isSecondary: Boolean = false) {
        if (DEBUG_FLAG) Log.d(TAG, "updateForecastRegion(...) called.")
        // Store the selection.
        viewModelScope.launch {
            userPrefsRepo.updateForecastRegion(region)
            defaultRegionCandidates[1] = region
        }

        // Update the city name.
        updateRepresentedCityName(region)

        // Check if the location changed before reassigning.
        val isSameCoordinate = region.xy == forecastRegion.xy
        forecastRegion = region
        if (DEBUG_FLAG) Log.d(TAG, "forecastRegion: ${forecastRegion.toRegionString()}")

        if (isSameCoordinate) {
            if (DEBUG_FLAG) Log.d(TAG, "The same coordinates.")
            checkTimeThenRequest(isSecondary)
        } else {
            if (DEBUG_FLAG) Log.d(LOCATION_TAG, "A new coordinates\t: (${region.xy.nx}, ${region.xy.ny})")

            // No need to check time, request the new data for the location.
            requestAllWeatherData()
        }
    }

    private fun isNewDataReleasedAfter(lastCheckedCal: Calendar?): Boolean {
        if (DEBUG_FLAG) Log.d(TAG, "isNewDataReleased(Calendar) called.")
        return if (lastCheckedCal == null) {
            if (DEBUG_FLAG) Log.d(TAG, "ViewModel doesn't hold any data.")
            true
        } else {
            val lastBaseTime = getKmaBaseTime(cal = lastCheckedCal, roundOff = Hour)
            val currentBaseTime = getKmaBaseTime(cal = getCurrentKoreanDateTime(), roundOff = Hour)

            if (currentBaseTime.isLaterThan(lastBaseTime)) {
                if (DEBUG_FLAG) Log.d(TAG, "New data are available.(${lastBaseTime.hour} -> ${currentBaseTime.hour})")
                true
            } else {
                if (DEBUG_FLAG) Log.d(TAG, "No new data available.")
                false
            }
        }
    }

    private fun updateRepresentedCityName(region: ForecastRegion) {
        if (DEBUG_FLAG) Log.d(TAG, "updateRepresentedCityName(...) called.")
        val address = region.address
        _cityName.value = getSi(address, true).removeSpecialCitySuffix()
            ?: getGeneralCityName(address)
        _districtName.value = getDong(address, true) ?: getGu(address, true) ?: ""
    }

    private fun nullifyWeatherInfo() {
        _hourlyTempToday.value = null
        _hourlyTempDiff.value = null
        _dailyTemps.value = getDefaultDailyTemps()
        _rainfallStatus.value = Sky.Undetermined
    }

    private fun isNullInfoIncluded(): Boolean {
        if (
            (_hourlyTempDiff.value == null) ||
            (_hourlyTempDiff.value == null) ||
            (_rainfallStatus.value == Sky.Undetermined)
        ) return true

        for (dailyTemp in _dailyTemps.value) {
            if ((dailyTemp.highest == stringForNull) || (dailyTemp.lowest == stringForNull)) return true
        }
        return false
    }

    private fun requestAllWeatherData(cal: Calendar = getCurrentKoreanDateTime()) {
        if (DEBUG_FLAG) Log.d(TAG, "requestAllWeatherData() called.")
        nullifyWeatherInfo()
        val calValue = cal.clone() as Calendar

        viewModelScope.launch(defaultDispatcher) {
            kmaJob?.cancelAndJoin()
            var trialCount = 0
            var isCalModified = false

            val separator = "\n────────────────\n"
            val reportHeader = separator +
                    "App version: ${BuildConfig.VERSION_CODE}\n" +
                    "SDK version:${android.os.Build.VERSION.SDK_INT}" +
                    separator +
                    "Weather data to compose the main screen\n"
            val dataReport = StringBuilder()

            _isRefreshing.value = true
            kmaJob = launch(defaultDispatcher) {
                if (DEBUG_FLAG) Log.d(TAG, "kmaJob launched.")
                lastSuccessfulTime = getCurrentKoreanDateTime()
                while (trialCount < NETWORK_MAX_RETRY) {
                    dataReport.append(reportHeader)
                    try {
                        // Clear the former response so that it always hold the last one.
                        WeatherApi.clearResponseString()
                        withTimeout(minOf(NETWORK_TIMEOUT_MIN + trialCount * NETWORK_ADDITIONAL_TIMEOUT, NETWORK_TIMEOUT_MAX)) {
                            val networkJob = launch(defaultDispatcher) {
                                val today: String = formatToKmaDate(getCurrentKoreanDateTime())
                                val latestVillageBaseTime = getKmaBaseTime(cal = calValue, roundOff = Village)  // 2:00 for 2:11 ~ 5:10
                                val latestHourlyBaseTime = getKmaBaseTime(cal = calValue, roundOff = Hour)  // 2:00 for 3:00 ~ 3:59
                                dataReport.append("${latestHourlyBaseTime.toTimeString()}." +
                                        "${latestVillageBaseTime.hour.toInt()/100}." +
                                        "${forecastRegion.xy.nx}.${forecastRegion.xy.ny}\n")

                                val yesterdayCal = calValue.clone() as Calendar
                                yesterdayCal.add(Calendar.DAY_OF_YEAR, -1)
                                val yesterday: String = formatToKmaDate(yesterdayCal)

                                val t1hPageNo = 5
                                val rowCountShort = 6
                                val lowTempBaseTime = "0200"  // From 3:00
                                val highTempBaseTime = "1100"  // From 12:00
                                val characteristicTempHourSpan = 6  // Monitors 6 hours(3 AM..8 AM and 12 PM..17 PM)

                                // Conditional tasks, only if the current time passed baseTime
                                var todayLowTempResponse: Deferred<Response<ForecastResponse>>? = null
                                var todayHighTempResponse: Deferred<Response<ForecastResponse>>? = null

                                val fetchingStartTime = System.currentTimeMillis()

                                // Useful for causing timeout(with multiple requests and awaits.
                                // The largest chunk, at most 290 + 290 + (290 - 72) + 2 * 3, which is about 800.
                                /*val futureDaysResponse: Deferred<Response<ForecastResponse>> = async(retrofitDispatcher) {
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
                                }*/

                                // The response includes duplicate data of earlier hours of hourly data.
                                // However, only cutting tails is allowed, retrieve the whole data.
                                val futureTodayResponse: Deferred<Response<ForecastResponse>> = async(retrofitDispatcher) {
                                    val remainingHours = (2400 - getEarliestFcstHour(latestVillageBaseTime)) / 100
                                    WeatherApi.service.getVillageWeather(
                                        baseDate = latestVillageBaseTime.date,  // Yesterday(23:00 only) or today
                                        baseTime = latestVillageBaseTime.hour,
                                        numOfRows = remainingHours * VILLAGE_ROWS_PER_HOUR,
                                        nx = forecastRegion.xy.nx,
                                        ny = forecastRegion.xy.ny,
                                    )
                                }

                                // Responses to extract the highest/lowest temperatures of tomorrow and D2
                                val futureDayHourSpan = 4  // 300 ~ 700, 1200 ~ 1600
                                val futureDayRowCount = futureDayHourSpan * VILLAGE_ROWS_PER_HOUR + VILLAGE_EXTRA_ROWS
                                val baseTime1 = 1100
                                val baseTime2 = 2300  // of yesterday
                                val pagesPerDay: Int = 24 / futureDayHourSpan

                                val futureDayBaseTime: String
                                val futureDayBaseDate: String
                                val tomorrowHighTempPageNo: Int
                                val tomorrowLowTempPageNo: Int
                                if (latestVillageBaseTime.hour.toInt() in baseTime1 until baseTime2) {
                                    // fcstTIme starts from today 12:00.
                                    futureDayBaseDate = today
                                    futureDayBaseTime = baseTime1.toString()
                                    tomorrowLowTempPageNo = 5
                                    tomorrowHighTempPageNo = 7
                                } else {
                                    // fcstTIme starts from today 00:00.
                                    futureDayBaseDate = yesterday
                                    futureDayBaseTime = baseTime2.toString()
                                    tomorrowLowTempPageNo = 8
                                    tomorrowHighTempPageNo = 10
                                }

                                val tomorrowHighTempResponse = async(retrofitDispatcher) {
                                    WeatherApi.service.getVillageWeather(
                                        baseDate = futureDayBaseDate,  // Yesterday(23:00 only) or today
                                        baseTime = futureDayBaseTime,
                                        numOfRows = futureDayRowCount,
                                        pageNo = tomorrowHighTempPageNo,
                                        nx = forecastRegion.xy.nx,
                                        ny = forecastRegion.xy.ny,
                                    )
                                }

                                val tomorrowLowTempResponse = async(retrofitDispatcher) {
                                    WeatherApi.service.getVillageWeather(
                                        baseDate = futureDayBaseDate,  // Yesterday(23:00 only) or today
                                        baseTime = futureDayBaseTime,
                                        numOfRows = futureDayRowCount,
                                        pageNo = tomorrowLowTempPageNo,
                                        nx = forecastRegion.xy.nx,
                                        ny = forecastRegion.xy.ny,
                                    )
                                }

                                val d2HighTempResponse = async(retrofitDispatcher) {
                                    WeatherApi.service.getVillageWeather(
                                        baseDate = futureDayBaseDate,  // Yesterday(23:00 only) or today
                                        baseTime = futureDayBaseTime,
                                        numOfRows = futureDayRowCount,
                                        pageNo = tomorrowHighTempPageNo + pagesPerDay,
                                        nx = forecastRegion.xy.nx,
                                        ny = forecastRegion.xy.ny,
                                    )
                                }

                                val d2LowTempResponse = async(retrofitDispatcher) {
                                    WeatherApi.service.getVillageWeather(
                                        baseDate = futureDayBaseDate,  // Yesterday(23:00 only) or today
                                        baseTime = futureDayBaseTime,
                                        numOfRows = futureDayRowCount,
                                        pageNo = tomorrowLowTempPageNo + pagesPerDay,
                                        nx = forecastRegion.xy.nx,
                                        ny = forecastRegion.xy.ny,
                                    )
                                }

                                val latestVillageHour = latestVillageBaseTime.hour.toInt()
                                if (isCoveredByFutureTodayData(latestVillageHour, lowTempBaseTime.toInt())) {
                                    val numOfRows = if (latestVillageHour == lowTempBaseTime.toInt() + VILLAGE_HOUR_INTERVAL * 100) {  // During baseTime = 500
                                        getRowCount(VILLAGE_HOUR_INTERVAL)  // Missing hours span only an interval: 3:00, 4:00, 5:00
                                    } else {  // After baseTime became 800, the lowest temperature won't be renewed.
                                        getRowCount(characteristicTempHourSpan)  // Full span: 3:00, ..., 7:00
                                    }
                                    if (DEBUG_FLAG) Log.d("D${DayOfInterest.Today.dayOffset}-${CharacteristicTempType.Lowest.descriptor}", "$numOfRows rows")

                                    todayLowTempResponse = async(retrofitDispatcher) {
                                        WeatherApi.service.getVillageWeather(
                                            baseDate = today,
                                            baseTime = lowTempBaseTime,  // fsctTime starts from 03:00 AM
                                            numOfRows = numOfRows,
                                            nx = forecastRegion.xy.nx,
                                            ny = forecastRegion.xy.ny,
                                        )
                                    }
                                }
                                if (isCoveredByFutureTodayData(latestVillageHour, highTempBaseTime.toInt())) {  // As soon as the latest highest temperature span available
                                    val numOfRows = if (latestVillageHour == highTempBaseTime.toInt() + VILLAGE_HOUR_INTERVAL * 100) {  // During baseTime = 1400
                                        getRowCount(VILLAGE_HOUR_INTERVAL)  // Missing hours span only an interval: 12:00, 13:00, 14:00
                                    } else {  // After baseTime became 1700, the highest temperature won't be renewed.
                                        getRowCount(characteristicTempHourSpan)  // Full span: 12:00, ..., 16:00
                                    }
                                    if (DEBUG_FLAG) Log.d("D${DayOfInterest.Today.dayOffset}-${CharacteristicTempType.Highest.descriptor}", "$numOfRows rows")
                                    todayHighTempResponse = async(retrofitDispatcher) {
                                        WeatherApi.service.getVillageWeather(
                                            baseDate = today,
                                            baseTime = highTempBaseTime,  // fsctTime starts from the noon
                                            numOfRows = numOfRows,
                                            nx = forecastRegion.xy.nx,
                                            ny = forecastRegion.xy.ny,
                                        )
                                    }
                                }

                                val todayHourlyTempResponse = async(retrofitDispatcher) {
                                    WeatherApi.service.getShortTermWeather(
                                        baseDate = latestHourlyBaseTime.date,
                                        baseTime = latestHourlyBaseTime.hour,  // 14:50 -> 13:00, 15:00 -> 14:00
                                        numOfRows = 30,  // [LGT -> PTY -> RN1 -> SKY -> TH1] -> REH -> UUU -> VVV -> ...
                                        nx = forecastRegion.xy.nx,
                                        ny = forecastRegion.xy.ny,
                                    )
                                }

                                val yesterdayHourlyTempResponse = async(retrofitDispatcher) {
                                    val yesterdayHourlyBaseTime = getKmaBaseTime(cal = calValue, dayOffset = -1, roundOff = Hour)

                                    WeatherApi.service.getShortTermWeather(
                                        baseDate = yesterdayHourlyBaseTime.date,
                                        baseTime = yesterdayHourlyBaseTime.hour,  // 14:50 -> 13:00, 15:00 -> 14:00
                                        numOfRows = rowCountShort,
                                        pageNo = t1hPageNo,  // LGT -> PTY -> RN1 -> SKY -> [TH1] -> REH -> UUU -> VVV -> ...
                                        nx = forecastRegion.xy.nx,
                                        ny = forecastRegion.xy.ny,
                                    )
                                }

                                // Occasionally, short-term temperatures are not available for the hour. A backup data for that such cases.
                                val yesterdayHourlyTempBackupResponse = async(retrofitDispatcher) {
                                    val yesterdayVillageBaseTime = getKmaBaseTime(
                                        cal = getYesterdayVillageCalendar(cal = calValue),
                                        roundOff = Village
                                    )

                                    // At 3:00, the latest data start from fcstTime of 3:00, of which data are on the 1st page.
                                    // At 4:00, the latest data start from fcstTime of 3:00, of which data are on the 2nd page.
                                    val pageNo: Int = getCurrentKoreanDateTime().hour() % VILLAGE_HOUR_INTERVAL + 1

                                    WeatherApi.service.getVillageWeather(
                                        baseDate = yesterdayVillageBaseTime.date,
                                        baseTime = yesterdayVillageBaseTime.hour,
                                        numOfRows = VILLAGE_ROWS_PER_HOUR,
                                        pageNo = pageNo,
                                        nx = forecastRegion.xy.nx,
                                        ny = forecastRegion.xy.ny,
                                    )
                                }

                                /*
                                KMA often emits the data earlier, at which expires the oldest observed data which is the following lines try to retrieve.
                                val yesterdayHourlyTempBackupResponse = async(retrofitDispatcher) {
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
                                        nx = forecastRegion.xy.nx,
                                        ny = forecastRegion.xy.ny,
                                    )
                                }
                                */

                                val yesterdayLowTempResponse = async(retrofitDispatcher) {
                                    WeatherApi.service.getShortTermWeather(
                                        baseDate = yesterday,
                                        baseTime = lowTempBaseTime,  // fsctTime starts from 03:00 AM
                                        numOfRows = rowCountShort,
                                        pageNo = t1hPageNo,  // LGT -> PTY -> RN1 -> SKY -> [TH1] -> REH -> UUU -> VVV -> ...
                                        nx = forecastRegion.xy.nx,
                                        ny = forecastRegion.xy.ny,
                                    )
                                }

                                val yesterdayHighTempResponse = async(retrofitDispatcher) {
                                    WeatherApi.service.getShortTermWeather(
                                        baseDate = yesterday,
                                        baseTime = highTempBaseTime,  // fsctTime starts from the noon
                                        numOfRows = rowCountShort,
                                        pageNo = t1hPageNo,  // LGT -> PTY -> RN1 -> SKY -> [TH1] -> REH -> UUU -> VVV -> ...
                                        nx = forecastRegion.xy.nx,
                                        ny = forecastRegion.xy.ny,
                                    )
                                }

                                // Occasionally, short-term temperatures are not available for the hour. A backup data for that such cases.
                                val yesterdayLowTempBackupResponse = async(retrofitDispatcher) {
                                    WeatherApi.service.getVillageWeather(
                                        baseDate = yesterday,
                                        baseTime = lowTempBaseTime,  // fsctTime starts from 03:00 AM
                                        numOfRows = getRowCount(characteristicTempHourSpan),  // Full span: 3:00, ..., 7:00
                                        nx = forecastRegion.xy.nx,
                                        ny = forecastRegion.xy.ny,
                                    )
                                }

                                val yesterdayHighTempBackupResponse = async(retrofitDispatcher) {
                                    WeatherApi.service.getVillageWeather(
                                        baseDate = yesterday,
                                        baseTime = highTempBaseTime,  // fsctTime starts from the noon
                                        numOfRows = getRowCount(characteristicTempHourSpan),  // Full span: 3:00, ..., 7:00
                                        nx = forecastRegion.xy.nx,
                                        ny = forecastRegion.xy.ny,
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
                                val todayHourlyData = todayHourlyTempResponse.await().body()?.response?.body?.items?.item ?: emptyList()
                                val todayHighTempData = todayHighTempResponse?.await()?.body()?.response?.body?.items?.item ?: emptyList()
                                val todayLowTempData = todayLowTempResponse?.await()?.body()?.response?.body?.items?.item ?: emptyList()

                                // Sometimes, short-term responses are empty(expired earlier than expected).
                                val yesterdayHourlyTempBackupData = if (yesterdayHourlyTempData.isEmpty()) {
                                        yesterdayHourlyTempBackupResponse.await().body()?.response?.body?.items?.item ?: emptyList()
                                } else emptyList()

                                val yesterdayHighTempBackupData = if (yesterdayHighTempData.isEmpty()) {
                                    yesterdayHighTempBackupResponse.await().body()?.response?.body?.items?.item ?: emptyList()
                                } else emptyList()

                                val yesterdayLowTempBackupData = if (yesterdayLowTempData.isEmpty()) {
                                    yesterdayLowTempBackupResponse.await().body()?.response?.body?.items?.item ?: emptyList()
                                } else emptyList()

                                // Log for debugging.
                                launch(defaultDispatcher) {
                                    var size = 0
                                    val emptyMessage = "List empty."

                                    if (yesterdayHighTempData.isEmpty()) {
                                        dataReport.appendAndLog("D${DayOfInterest.Yesterday.dayOffset}-${CharacteristicTempType.Highest.descriptor}", emptyMessage)
                                        if (yesterdayHighTempBackupData.isEmpty()) {
                                            dataReport.appendAndLog("D${DayOfInterest.Yesterday.dayOffset}-${CharacteristicTempType.Highest.descriptor}-B", emptyMessage)
                                        } else {
                                            for (i in yesterdayHighTempBackupData) {
                                                size += 1
                                                if (i.category == VILLAGE_TEMPERATURE_TAG) {
                                                    dataReport.appendAndLog("D${DayOfInterest.Yesterday.dayOffset}-${CharacteristicTempType.Highest.descriptor}", "$i")
                                                }
                                            }
                                        }
                                    } else {
                                        for (i in yesterdayHighTempData) {
                                            size += 1
                                            dataReport.appendAndLog("D${DayOfInterest.Yesterday.dayOffset}-${CharacteristicTempType.Highest.descriptor}", "$i")
                                        }
                                    }

                                    if (yesterdayLowTempData.isEmpty()) {
                                        dataReport.appendAndLog("D${DayOfInterest.Yesterday.dayOffset}-${CharacteristicTempType.Lowest.descriptor}", emptyMessage)
                                        if (yesterdayLowTempBackupData.isEmpty()) {
                                            dataReport.appendAndLog("D${DayOfInterest.Yesterday.dayOffset}-${CharacteristicTempType.Lowest.descriptor}-B", emptyMessage)
                                        } else {
                                            for (i in yesterdayLowTempBackupData) {
                                                size += 1
                                                if (i.category == VILLAGE_TEMPERATURE_TAG) {
                                                    dataReport.appendAndLog("D${DayOfInterest.Yesterday.dayOffset}-${CharacteristicTempType.Lowest.descriptor}-B", "$i")
                                                }
                                            }
                                        }
                                    } else {
                                        for (i in yesterdayLowTempData) {
                                            size += 1
                                            dataReport.appendAndLog("D${DayOfInterest.Yesterday.dayOffset}-${CharacteristicTempType.Lowest.descriptor}", "$i")
                                        }
                                    }

                                    if (yesterdayHourlyTempData.isEmpty()) {
                                        dataReport.appendAndLog("D${DayOfInterest.Yesterday.dayOffset}-$HOURLY_TEMPERATURE_TAG", emptyMessage)
                                        if (yesterdayHourlyTempBackupData.isEmpty()) {
                                            dataReport.appendAndLog("D${DayOfInterest.Yesterday.dayOffset}-$HOURLY_TEMPERATURE_TAG-B", emptyMessage)
                                        } else {
                                            for (i in yesterdayHourlyTempBackupData) {
                                                size += 1
                                                if (i.category == VILLAGE_TEMPERATURE_TAG) {
                                                    dataReport.appendAndLog("D${DayOfInterest.Yesterday.dayOffset}-$HOURLY_TEMPERATURE_TAG-B", "$i")
                                                }
                                            }
                                        }
                                    } else {
                                        for (i in yesterdayHourlyTempData) {
                                            size += 1
                                            dataReport.appendAndLog("D${DayOfInterest.Yesterday.dayOffset}-$HOURLY_TEMPERATURE_TAG", "$i")
                                        }
                                    }

                                    if (todayHourlyData.isEmpty()) {
                                        dataReport.appendAndLog("D${DayOfInterest.Today.dayOffset}-$HOURLY_TEMPERATURE_TAG", emptyMessage)
                                    } else {
                                        for (i in todayHourlyData) {
                                            size += 1
                                            if (i.category == HOURLY_TEMPERATURE_TAG || i.category == RAIN_TAG) {
                                                dataReport.appendAndLog("D${DayOfInterest.Today.dayOffset}-$HOURLY_TEMPERATURE_TAG", "$i")
                                            }
                                        }
                                    }

                                    if (isCoveredByFutureTodayData(latestVillageHour, highTempBaseTime.toInt()) &&
                                        todayHighTempData.isEmpty()) {
                                        dataReport.appendAndLog("D${DayOfInterest.Today.dayOffset}-${CharacteristicTempType.Highest.descriptor}", emptyMessage)
                                    } else {
                                        for (i in todayHighTempData) {
                                            size += 1
                                            if (i.category == VILLAGE_TEMPERATURE_TAG || i.category == HIGH_TEMPERATURE_TAG) {
                                                dataReport.appendAndLog("D${DayOfInterest.Today.dayOffset}-${CharacteristicTempType.Highest.descriptor}", "$i")
                                            }
                                        }
                                    }

                                    if (isCoveredByFutureTodayData(latestVillageHour, lowTempBaseTime.toInt()) &&
                                            todayLowTempData.isEmpty()) {
                                        dataReport.appendAndLog("D${DayOfInterest.Today.dayOffset}-${CharacteristicTempType.Lowest.descriptor}", emptyMessage)
                                    } else {
                                        for (i in todayLowTempData) {
                                            size += 1
                                            if (i.category == VILLAGE_TEMPERATURE_TAG || i.category == LOW_TEMPERATURE_TAG) {
                                                dataReport.appendAndLog("D${DayOfInterest.Today.dayOffset}-${CharacteristicTempType.Lowest.descriptor}", "$i")
                                            }
                                        }
                                    }

                                    if (futureTodayData.isEmpty()) {
                                        dataReport.appendAndLog("D${DayOfInterest.Today.dayOffset}", emptyMessage)
                                    } else {
                                        for (i in futureTodayData) {
                                            size += 1
                                            if (i.category == VILLAGE_TEMPERATURE_TAG) {
                                                dataReport.appendAndLog("D${DayOfInterest.Today.dayOffset}", "$i")
                                            }
                                        }
                                    }

                                    if (tomorrowHighTempData.isEmpty()) {
                                        dataReport.appendAndLog("D${DayOfInterest.Tomorrow.dayOffset}-${CharacteristicTempType.Highest.descriptor}", emptyMessage)
                                    } else {
                                        for (i in tomorrowHighTempData) {
                                            size += 1
                                            if (i.category == VILLAGE_TEMPERATURE_TAG || i.category == HIGH_TEMPERATURE_TAG) {
                                                dataReport.appendAndLog("D${DayOfInterest.Tomorrow.dayOffset}-${CharacteristicTempType.Highest.descriptor}", "$i")
                                            }
                                        }
                                    }

                                    if (tomorrowLowTempData.isEmpty()) {
                                        dataReport.appendAndLog("D${DayOfInterest.Tomorrow.dayOffset}-${CharacteristicTempType.Lowest.descriptor}", emptyMessage)
                                    } else {
                                        for (i in tomorrowLowTempData) {
                                            size += 1
                                            if (i.category == VILLAGE_TEMPERATURE_TAG || i.category == LOW_TEMPERATURE_TAG) {
                                                dataReport.appendAndLog("D${DayOfInterest.Tomorrow.dayOffset}-${CharacteristicTempType.Lowest.descriptor}", "$i")
                                            }
                                        }
                                    }

                                    if (d2HighTempData.isEmpty()) {
                                        dataReport.appendAndLog("D${DayOfInterest.Day2.dayOffset}-${CharacteristicTempType.Highest.descriptor}", emptyMessage)
                                    } else {
                                        for (i in d2HighTempData) {
                                            size += 1
                                            if (i.category == VILLAGE_TEMPERATURE_TAG || i.category == HIGH_TEMPERATURE_TAG) {
                                                dataReport.appendAndLog("D${DayOfInterest.Day2.dayOffset}-${CharacteristicTempType.Highest.descriptor}", "$i")
                                            }
                                        }
                                    }

                                    if (d2LowTempData.isEmpty()) {
                                        dataReport.appendAndLog("D${DayOfInterest.Day2.dayOffset}-${CharacteristicTempType.Lowest.descriptor}", emptyMessage)
                                    } else {
                                        for (i in d2LowTempData) {
                                            size += 1
                                            if (i.category == VILLAGE_TEMPERATURE_TAG || i.category == LOW_TEMPERATURE_TAG) {
                                                dataReport.appendAndLog("D${DayOfInterest.Day2.dayOffset}-${CharacteristicTempType.Lowest.descriptor}", "$i")
                                            }
                                        }
                                    }

                                    logElapsedTime(TAG, "$size items", fetchingStartTime)
                                }

                                // Refresh the highest/lowest temperatures
                                launch { refreshTodayCharacteristicTemp(todayHighTempData, todayLowTempData, todayHourlyData, futureTodayData) }
                                launch { refreshCharacteristicTemp(DayOfInterest.Tomorrow, tomorrowHighTempData, tomorrowLowTempData) }
                                launch { refreshCharacteristicTemp(DayOfInterest.Day2, d2HighTempData, d2LowTempData) }
                                launch {
                                    refreshCharacteristicTemp(DayOfInterest.Yesterday, yesterdayHighTempData, yesterdayLowTempData)
                                    // Refresh the hourly temperature and the difference(dependant to yesterday's highest/lowest temperature).
                                    refreshHourlyTemp(todayHourlyData, yesterdayHourlyTempData, yesterdayHourlyTempBackupData)
                                }

                                // Refresh the rainfall status.
                                launch { refreshRainfall(todayHourlyData, futureTodayData) }
                            }
                            networkJob.join()
                            adjustTodayCharTemp()
                            buildDailyTemps()
                            if (isNullInfoIncluded()) {
                                _exceptionSnackBarEvent.value = SnackBarEvent(
                                    getErrorReportSnackBarContent(R.string.snack_bar_error_kma_na,
                                        dataReport.appendRawData(separator))
                                )
                                lastSuccessfulTime = null  // Incomplete data
                            }
                        }
                        if (isCalModified) {
                            lastSuccessfulTime = null  // Not really successful
                            if (DEBUG_FLAG) Log.w(TAG, "Data retrieved for the modified baseTime: ${
                                getKmaBaseTime(calValue, roundOff = Hour).toTimeString()
                            }")
                        }
                        break
                    } catch (e: Exception) {
                        if (e is TimeoutCancellationException) {  // Worth retrying.
                            if (++trialCount < NETWORK_MAX_RETRY) {
                                if (DEBUG_FLAG) Log.w(TAG, "(Retrying) $e")
                                runBlocking { delay(NETWORK_PAUSE) }
                            } else {  // Maximum count of trials has been reached.
                                Log.e(TAG, "Stopped retrying after $NETWORK_MAX_RETRY times.\n$e")
                                dataReport.appendStackTrace(separator, e)
                                _exceptionSnackBarEvent.value = SnackBarEvent(
                                    getErrorReportSnackBarContent(
                                        R.string.snack_bar_weather_error_general,
                                        dataReport.appendRawData(separator)
                                    )
                                )
                                lastSuccessfulTime = null
                                break
                            }
                        } else if (
                            e is MalformedJsonException ||
                            e is JsonSyntaxException
                        ) {  // Worth retrying, with different baseTime
                            if (++trialCount < NETWORK_MAX_RETRY) {
                                if (DEBUG_FLAG) Log.w(TAG, "(Retrying) $e")
                                val additionalRetry = 2
                                if (trialCount < NETWORK_MAX_RETRY - additionalRetry) { // Retry twice more.
                                    trialCount = NETWORK_MAX_RETRY - additionalRetry
                                }

                                runBlocking { delay(NETWORK_PAUSE) }
                                if (!isCalModified) calValue.add(Calendar.HOUR_OF_DAY, -1)
                                isCalModified = true
                            } else {  // Maximum count of trials has been reached.
                                Log.e(TAG, "Stopped retrying", e)
                                dataReport.appendStackTrace(separator, e)
                                _exceptionSnackBarEvent.value = SnackBarEvent(
                                    getErrorReportSnackBarContent(
                                        R.string.snack_bar_weather_error_general,
                                        dataReport.appendRawData(separator)
                                    )
                                )
                                lastSuccessfulTime = null
                                break
                            }
                        } else {  // Not worth retrying, just stop.
                            dataReport.appendStackTrace(separator, e)
                            when (e) {
                                is CancellationException -> if (DEBUG_FLAG) Log.d(TAG, "Retrieving weather data cancelled.")
                                is UnknownHostException -> _toastMessage.value = ToastEvent(R.string.toast_weather_failure_network)
                                else -> {
                                    Log.e(TAG, "Cannot retrieve weather data.", e)
                                    _exceptionSnackBarEvent.value = SnackBarEvent(
                                        getErrorReportSnackBarContent(
                                            R.string.snack_bar_weather_error_general,
                                            dataReport.appendRawData(separator)
                                        )
                                    )
                                }
                            }
                            lastSuccessfulTime = null
                            break
                        }
                    } finally {
                        // _isLoading.value = false   Called on every loops, which is not intended.
                        // kmaJob.cancelAndJoin()  Cannot be run here: https://kt.academy/article/cc-cancellation
                        dataReport.clear()
                    }
                }
            }

            kmaJob?.invokeOnCompletion {
                if (kmaJob?.isCancelled == false) {
                    if (DEBUG_FLAG) Log.d(TAG, "kmaJob completed without cancelled.")
                    _isRefreshing.value = false
                    _showLandingScreen.value = false
                }
            }
        }
    }

    private fun StringBuilder.appendAndLog(tag: String, message: String) {
        if (DEBUG_FLAG) Log.d(tag, message)
        this.append("\n$tag\t\t$message")
    }

    private fun StringBuilder.appendStackTrace(separator: String, e: Exception) {
        val trace = e.stackTraceToString()
        if (trace.isNotBlank()) {
            this.append(separator + "Error trace\n" + trace)
        }
    }

    private fun StringBuilder.appendRawData(separator: String): String {
        return this.append(separator + "Raw data\n" + WeatherApi.getResponseString()).toString()
    }

    fun updateLastImplicitChecked(cal: Calendar) {
        lastImplicitlyCheckedTime = cal
    }

    private fun getErrorReportSnackBarContent(messageId: Int, description: String) = SnackBarContent(
        messageId, R.string.snack_bar_error_send_action, shareException(description))

    private fun shareException(description: String): () -> Unit = {
        viewModelScope.launch {
            val chooser = getChooserIntent(R.string.snack_bar_error_send_opening, description)
            context.startActivity(chooser)
        }
    }

    private suspend fun getChooserIntent(openingId: Int, description: String): Intent? {
        // A modified Context with the Locale from Preferences
        val modifiedContext = withContext(defaultDispatcher) {
            val config = createConfigurationWithStoredLocale(context)
            context.createConfigurationContext(config)
        }

        val title =
            "\"${modifiedContext.getString(R.string.app_name)}\" ${modifiedContext.getString(R.string.snack_bar_error_send_title)}"
        val opening = modifiedContext.getString(openingId)
        val body = opening + "\n" + description

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = (Uri.parse("mailto:"))
            putExtra(Intent.EXTRA_EMAIL, arrayOf(DEVELOPER_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, body)
        }

        val chooser =
            Intent.createChooser(intent, modifiedContext.getString(R.string.share_app_guide))
                .apply {
                    addFlags(FLAG_ACTIVITY_NEW_TASK)
                }
        return chooser
    }

    fun onClickReportError(explanationId: Int) {
        viewModelScope.launch {
            val chooser = getChooserIntent(explanationId, WeatherApi.getResponseString())
            context.startActivity(chooser)
        }
    }

    private fun isCoveredByFutureTodayData(latestVillageHour: Int, baseTime: Int): Boolean {
        return (latestVillageHour != 2300) &&  // futureToday data will cover the whole day. No need to retrieve.
                (latestVillageHour >= baseTime)  // The baseTime data are available.
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
        val today: String = formatToKmaDate(getCurrentKoreanDateTime())
        val primaryCoveredHourMax: Int? = todayHourlyData.maxOfOrNull { it.fcstTime }

        // Remove duplicate data according to the priority (More recent data is preferred.)
        val hourlyRainfallData = todayHourlyData.filter { it.category == RAIN_TAG && it.fcstDate == today.toInt() }
        val futureTodayRainfallData: List<ForecastResponse.Item> = futureTodayData.filter {
            (it.category == RAIN_TAG) and (it.fcstTime > (primaryCoveredHourMax ?: 0))
        }
        val rainfallData: List<ForecastResponse.Item> = hourlyRainfallData + futureTodayRainfallData

        // Process the organized data.
        val rainingHours = arrayListOf<Int>()
        val snowingHours = arrayListOf<Int>()
        for (i in rainfallData) {
            if (DEBUG_FLAG) Log.d(RAIN_TAG, "$i")
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

        if (DEBUG_FLAG) Log.d(TAG, "PTY: ${_rainfallStatus.value::class.simpleName}\t(${hours.minOrNull()} ~ ${hours.maxOrNull()})")
    }

    private fun buildDailyTemps() {
        val cal = getCurrentKoreanDateTime().also {
            it.add(Calendar.DAY_OF_YEAR, -2)
        }
        val isoCode: String = when (languageCode) {
            Language.English.code -> Language.English.iso
            Language.Korean.code -> Language.Korean.iso
            else -> {
                if (Locale.getDefault().language == Language.Korean.iso) {
                    Language.Korean.iso
                } else {
                    // Not system locale:
                    // if the system locale is Japanese, for example, the weekdays will be displayed in Japanese.
                    Language.English.iso
                }
            }
        }
        val locale = Locale(isoCode)

        val dailyTempsBuilder: ArrayList<DailyTemperature> = arrayListOf()
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
        highTempCandidates: List<ForecastResponse.Item>?,
        lowTempCandidates: List<ForecastResponse.Item>?,
    ) {
        val index = day.dayOffset + 1  // Yesterday's data go to [0].
        val highest = highTempCandidates?.filter {
            it.category == HOURLY_TEMPERATURE_TAG || it.category == HIGH_TEMPERATURE_TAG
        }?.maxByOrNull { it.fcstValue.toFloat() }?.fcstValue?.toFloat()?.roundToInt()  // TMN, TMX values are Floats.
        highestTemps[index] = highest

        val lowest = lowTempCandidates?.filter {
            it.category == HOURLY_TEMPERATURE_TAG || it.category == LOW_TEMPERATURE_TAG
        }?.minByOrNull { it.fcstValue.toFloat() }?.fcstValue?.toFloat()?.roundToInt()
        lowestTemps[index] = lowest
    }

    private fun refreshTodayCharacteristicTemp(
        todayHighTempData: List<ForecastResponse.Item>,
        todayLowTempData: List<ForecastResponse.Item>,
        todayHourlyData: List<ForecastResponse.Item>,
        futureTodayData: List<ForecastResponse.Item>,
    ) {
        val today: String = formatToKmaDate(getCurrentKoreanDateTime())
        val data = todayHighTempData + todayLowTempData + todayHourlyData.filter { it.fcstDate == today.toInt() } + futureTodayData
        var isPrimed = false

        // Using minOf(...) or maxOf(...) requires iterate each time, which is inefficient.
        data.forEach { item ->
            if (item.category == VILLAGE_TEMPERATURE_TAG
                || item.category == HOURLY_TEMPERATURE_TAG
                || item.category == HIGH_TEMPERATURE_TAG
                || item.category == LOW_TEMPERATURE_TAG
            ) {
                val temperature = item.fcstValue.toFloat().roundToInt()

                if (!isPrimed) {
                    lowestTemps[1] = temperature
                    highestTemps[1] = temperature
                    isPrimed = true
                } else {
                    // fcstValues of TMX, TMN are Float, so round to Integer.
                    compareAndUpdateFormerDailyTemp(DayOfInterest.Today, CharacteristicTempType.Highest, temperature)
                    compareAndUpdateFormerDailyTemp(DayOfInterest.Today, CharacteristicTempType.Lowest, temperature)
                }
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
        yesterdayHourlyTempData: List<ForecastResponse.Item>,
        yesterdayHourlyTempBackupData: List<ForecastResponse.Item>,
    ) {
        val cal = getCurrentKoreanDateTime()
        cal.add(Calendar.HOUR_OF_DAY, 1)
        val nextHour = formatToKmaHour(cal).toInt()

        var todayTemp: Int? = null
        var yesterdayTemp: Int? = null

        for (i in todayHourlyData) {
            if (i.fcstTime == nextHour && i.category == HOURLY_TEMPERATURE_TAG) {
                todayTemp = i.fcstValue.toInt()
                break
            }
        }

        for (i in yesterdayHourlyTempData + yesterdayHourlyTempBackupData) {
            if (i.fcstTime == nextHour &&
                (i.category == HOURLY_TEMPERATURE_TAG || i.category == VILLAGE_TEMPERATURE_TAG)
            ) {
                yesterdayTemp = i.fcstValue.toInt()
                break
            }
        }

        // Bound yesterdayTemp to yesterday's former data.
        lowestTemps[0]?.let { lt ->
            yesterdayTemp?.let { yt -> if (yt < lt) yesterdayTemp = lt }
        }
        highestTemps[0]?.let { ht ->
            yesterdayTemp?.let { yt -> if (yt > ht) yesterdayTemp = ht }
        }

        if (DEBUG_FLAG) Log.d(TAG, "T1H(at ${nextHour / 100}): $yesterdayTemp -> $todayTemp")

        todayTemp?.let { tt ->
            _hourlyTempToday.value = tt
            yesterdayTemp?.let { yt ->
                _hourlyTempDiff.value = tt - yt
            }
        }
    }

    /**
     * Often, TMX / TMN values are lower / higher than hourly value.
     * If the shown hourly value is higher than TMX than the user might doubt the reliability: adjust.
     */
    private fun adjustTodayCharTemp() {
        val index = DayOfInterest.Today.dayOffset + 1
        hourlyTempToday?.let { tt ->
            highestTemps[index]?.let { ht ->
                if (tt > ht) {
                    highestTemps[index] = tt
                    if (DEBUG_FLAG) Log.w(TAG, "Overridden by $HOURLY_TEMPERATURE_TAG: $ht -> $tt")
                }
            }
            lowestTemps[index]?.let { lt ->
                if (tt < lt) {
                    lowestTemps[index] = tt
                    if (DEBUG_FLAG) Log.w(TAG, "Overridden by $HOURLY_TEMPERATURE_TAG: $lt -> $tt")
                }
            }
        }
    }

    fun onClickRefresh(region: ForecastRegion = forecastRegion) {
        if (DEBUG_FLAG) Log.d(TAG, "onClickRefresh() called.")
        _isRefreshing.value = true
        try {
            if (isForecastRegionAuto) {  // Need to update the location.
                startLocationUpdate()
            } else {
                stopLocationUpdate()  // No need to request location.
                updateForecastRegion(region)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error while refreshWeatherData()", e)
            _exceptionSnackBarEvent.value = SnackBarEvent(
                getErrorReportSnackBarContent(
                    R.string.snack_bar_weather_error_general,
                    e.stackTraceToString()
                )
            )
        } finally {
            if (DEBUG_FLAG) Log.d(TAG, "kmaJob is ${kmaJob?.status()}")
            if (kmaJob?.isCompleted == true) {
                _isRefreshing.value = false
            }
        }
    }

    private fun checkTimeThenRequest(isSecondary: Boolean = false) {
        if (isNewDataReleasedAfter(lastSuccessfulTime)) {
            requestAllWeatherData()
        } else {
            if (!isSecondary) {
                viewModelScope.launch(defaultDispatcher) {
                    _isRefreshing.value = true
                    delay((200..280).random().toLong())
                    if (kmaJob?.isCompleted == true) _isRefreshing.value = false
                    _toastMessage.value = ToastEvent(R.string.toast_refresh_up_to_date)
                }
            }
        }
    }

    /**
     * Start location update and eventually retrieve new weather data based on the location.
     * */
    private fun startLocationUpdate() {
        if (DEBUG_FLAG) Log.d(TAG, "startLocationUpdated() called.")
        if (context.checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            if (isUpdatingLocation) stopLocationUpdate()

            locationClient.lastLocation.addOnSuccessListener {
                if (it != null) {
                    if (DEBUG_FLAG) Log.d(LOCATION_TAG, "Last location: (${it.latitude}, ${it.longitude})")
                    val coordinate = CoordinatesLatLon(lat = it.latitude, lon = it.longitude)
                    requestAutoForecastRegion(coordinate, false)
                } else {
                    // e.g. On the very first boot of the device
                    if (DEBUG_FLAG) Log.w(LOCATION_TAG, "FusedLocationProviderClient.lastLocation is null.")
                }
            }

            try {  // A costly process to update the current location. Might take about 10 seconds.
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

    fun onClickChangeRegion() {
        _forecastRegionCandidates.value = defaultRegionCandidates.toList()
        _toShowSearchRegionDialog.value = true
    }

    /**
     * Called when location results have been collected from [locationClient].
     * [isSecondary] is true when it was a (confirming) result from the [locationCallback].
     * */
    fun requestAutoForecastRegion(
        coordinates: CoordinatesLatLon,
        isSecondary: Boolean = false,
    ) {
        val xy = convertToXy(coordinates)
        if ((xy.nx in (NX_MIN..NX_MAX)) && (xy.ny in (NY_MIN..NY_MAX))) {
            KoreanGeocoder(context).updateAddresses(coordinates) { addresses ->
                if (addresses != null) {
                    val suitableAddress = getSuitableAddress(addresses)
                    if (DEBUG_FLAG) Log.d(TAG, "Suitable address(located): $suitableAddress")
                    val locatedRegion = ForecastRegion(address = suitableAddress, xy = xy)
                    updateForecastRegion(locatedRegion, isSecondary)
                } else showLocationError(coordinates)
            }
        } else {
            showLocationError(coordinates)
        }
    }

    private fun showLocationError(coordinates: CoordinatesLatLon) {
        Log.e(LOCATION_TAG, "Error retrieving a city name(${coordinates.lat}, ${coordinates.lon}).")
        _toastMessage.value = ToastEvent(R.string.error_auto_location_na)
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            super.onLocationResult(locationResult)
            locationResult.lastLocation?.let {
                if (DEBUG_FLAG) Log.d(LOCATION_TAG, "Current location: (${it.latitude}, ${it.longitude})")
                requestAutoForecastRegion(CoordinatesLatLon(it.latitude, it.longitude), isSecondary = true)
            }
        }
    }

    private fun getDefaultDailyTemps(): List<DailyTemperature> {
        val dailyTempPlaceholder = DailyTemperature(
            false, stringForNull, stringForNull, stringForNull
        )

        val builder: ArrayList<DailyTemperature> = arrayListOf()
        for (i in 0 until temperatureCount) {
            builder.add(dailyTempPlaceholder)
        }

        return builder.toList()
    }

    fun onLandingScreenTimeout() {
        _showLandingScreen.value = false
    }

    fun updateSimplifiedEnabled(enabled: Boolean) {
        if (DEBUG_FLAG) Log.d(TAG, "Simple View: ${enabled.toEnablementString()}")
        _isSimplified.value = enabled
    }

    fun updateAutoRefreshEnabled(enabled: Boolean) {
        if (DEBUG_FLAG) Log.d(TAG, "Auto refresh: ${enabled.toEnablementString()}")
        isAutoRefresh = enabled
    }

    fun updateDaybreakEnabled(enabled: Boolean) {
        if (DEBUG_FLAG) Log.d(TAG, "Daybreak mode: ${enabled.toEnablementString()}")
        _isDaybreakMode.value = enabled
    }

    fun updatePresetRegionEnabled(enabled: Boolean) {
        if (DEBUG_FLAG) Log.d(TAG, "PresetRegion mode: ${enabled.toEnablementString()}")
        _forecastRegionCandidates.value = defaultRegionCandidates.toList()
        _isPresetRegion.value = enabled
    }

    fun updateLanguage(selectedCode: Int, isExplicit: Boolean = true) {
        if (languageCode != selectedCode) {
            languageCode = selectedCode
            buildDailyTemps()  // Need to rebuild because Mon, Tue, ... is dependant on the language.
            if (isExplicit) isLanguageChanged = true
        }
    }

    /**
     * Update [isForecastRegionAuto] only, without requesting data.
     * */
    fun updateAutoRegionEnabled(enabled: Boolean, isExplicit: Boolean = true) {
        if (DEBUG_FLAG) Log.d(TAG, "Auto region: ${enabled.toEnablementString()}")
        isForecastRegionAuto = enabled
        _selectedForecastRegionIndex.value = if (enabled) 0 else 1
        if (isExplicit) {  // No need to feed back to Preferences.
            viewModelScope.launch {
                userPrefsRepo.updateAutoRegionEnabled(enabled)
            }
        }
    }

    fun stopRefreshing() {
        if (DEBUG_FLAG) Log.d(TAG, "Refresh cancelled.")

        // Cancel searching ForecastRegion. Indicator will be dismissed in the finally block.
        searchRegionJob?.cancel()

        kmaJob?.cancel()
        // The state should be explicitly reassigned,
        // as the loading indicator won't be dismissed when the Job is cancelled programmatically.
        _isRefreshing.value = false
    }

    fun searchRegionCandidateDebounced(query: String, delay: Long = 160) {
        searchRegionJob?.cancel()
        searchRegionJob = viewModelScope.launch {
            delay(delay)
            searchRegionCandidates(query, false)
        }
    }

    fun searchRegionCandidates(query: String, isExplicit: Boolean = true) {
        searchRegionJob = viewModelScope.launch(defaultDispatcher) {
            try {
                if (query.isBlank()) {
                    onNoRegionCandidates(isExplicit)
                } else {
                    if (isExplicit) _toShowSearchRegionDialogLoading.value = true
                    val geoCoder = KoreanGeocoder(context)
                    geoCoder.updateLatLng(query) { coordinateList ->
                        if (coordinateList != null) {
                            val searchResults = mutableListOf<ForecastRegion>()
                            for (coordinate in coordinateList) {
                                // Operations with the same coordinate
                                val xy = convertToXy(CoordinatesLatLon(coordinate.lat, coordinate.lon))

                                geoCoder.updateAddresses(coordinate) { addresses ->
                                    if (addresses != null) {
                                        // A clean set of addresses: Not RDS, takes < 40 ms
                                        val addressCandidates = getValidAddressSet(addresses).matchingQuery(query)
                                        for (address in addressCandidates) {
                                            if (coordinateList.size > 1) {
                                                // "제주" -> 이도2동, 연동 -> "제주시" with different xy.
                                                // (different ForecastRegion with the same "address")
                                                if (searchResults.isNotEmpty()) {
                                                    var toAdd = true
                                                    for (result in searchResults) {
                                                        if (result.address == address) {
                                                            // Duplicate addresses
                                                            toAdd = false  // Not to add
                                                            break  // No need to iterate further
                                                        }
                                                    }
                                                    if (toAdd) addRegionToSet(address, xy, searchResults)
                                                } else addRegionToSet(address, xy, searchResults)
                                            } else {  // Single xy. No possibility of the same "address" with different xy.
                                                addRegionToSet(address, xy, searchResults)
                                            }
                                        }
                                    } else {
                                        onNoRegionCandidates(isExplicit)
                                    }
                                }
                            }
                            // Operations with the coordinates done. All results have been collected.
                            if (searchResults.isEmpty()) {
                                onNoRegionCandidates(isExplicit)
                            } else {
                                searchResults.sortBy { it.address }
                                _forecastRegionCandidates.value = searchResults
                                _selectedForecastRegionIndex.value = 0
                            }
                        } else {
                            onNoRegionCandidates(isExplicit)
                        }
                    }
                }
            } catch (e: IOException) {
                if (DEBUG_FLAG) Log.w(TAG, "Invalid IO", e)
                onNoRegionCandidates(isExplicit)
            } catch (e: Exception) {
                Log.e(TAG, "Cannot retrieve Locations.", e)
                searchRegionJob?.cancel()
            } finally {
                dismissSearchRegionLoading()
            }
        }
    }

    private fun addRegionToSet(
        address: String,
        xy: CoordinatesXy,
        searchResults: MutableList<ForecastRegion>
    ) {
        val region = ForecastRegion(
            address = address,
            xy = xy,
        )
        searchResults.add(region)
    }

    /**
     * Returns Set<String> excluding 1. 도로명 주소 2. 숫자가 들어간 동 이름.
     * */
    private fun getValidAddressSet(addresses: List<Address>): Set<String> {
        val addressCandidates = mutableSetOf<String>()
        // Remove practically duplicate items.
        for (address in addresses) {
            val fullAddress = address.getAddressLine(0)

            if (Regex("[시도군구동읍면리]$").containsMatchIn(fullAddress)) {
                val suitableAddress = getDong(fullAddress, false) ?: getGu(fullAddress, false) ?: getSi(fullAddress, false)
                if (DEBUG_FLAG) Log.d(TAG, "Suitable address(searched): $suitableAddress")
                if (suitableAddress != null) addressCandidates.add(suitableAddress)
            }
        }
        return addressCandidates
    }

    private fun onNoRegionCandidates(isExplicit: Boolean) {
        if (DEBUG_FLAG) Log.d(TAG, "No region candidate.")
        if (isExplicit) {
            _toastMessage.value = ToastEvent(R.string.dialog_search_region_no_result)
        } else {
            _forecastRegionCandidates.value = defaultRegionCandidates.toList()
        }
    }

    /**
     * Called everytime the Activity is created(including rotating the screen, of course).
     * */
    fun initiateForecastRegions(region: ForecastRegion, autoTitle: String) {
        forecastRegion = region  // A force update
        defaultRegionCandidates = arrayOf(
            ForecastRegion(autoTitle, autoRegionCoordinate),
            region
        )
        updateRepresentedCityName(region)
    }

    fun initiateConsumedSnackBar(activity: Activity, lastConsumedSnackBar: Int) {
        if (lastConsumedSnackBar < CODED_SNACK_BAR_ID) {
            // Update Preferences not to show the SnackBar on the next launch.
            viewModelScope.launch { userPrefsRepo.updateConsumedSnackBar(CODED_SNACK_BAR_ID) }

            _noticeSnackBarEvent.value = SnackBarEvent(SnackBarContent(
                R.string.snack_bar_new_setting,
                R.string.snack_bar_go_setting_action
            ) {
                val intent = Intent(activity, SettingsActivity::class.java).apply {
                    putExtra(EXTRA_NEW_SETTING_KEY, HIGHLIGHTED_SETTING_ROW)
                }
                activity.startActivity(intent)
            }
            ) 
        }
    }

    fun invalidateSearchDialog() {
        dismissRegionDialog()
        _forecastRegionCandidates.value = defaultRegionCandidates.toList()
        _selectedForecastRegionIndex.value = if (isForecastRegionAuto) 0 else 1
    }

    fun dismissRegionDialog() {
        _toShowSearchRegionDialog.value = false
    }

    fun updateSelectedForecastRegionIndex(index: Int) {
        _selectedForecastRegionIndex.value = index
    }

    fun dismissSearchRegionLoading() {
        _toShowSearchRegionDialogLoading.value = false
    }

    companion object {
        private const val REQUEST_INTERVAL: Long = 60 * 60 * 1000
        val currentLocationRequest: LocationRequest = LocationRequest.Builder(REQUEST_INTERVAL).build()
    }
}

private fun Set<String>.matchingQuery(query: String): Set<String> {
    var isMatching = false
    val trimmedQuery = query.trim()
    for (candidate in this) {
        if (candidate.trim().endsWith(trimmedQuery)) {
            isMatching = true
            break
        }
    }
    return if (isMatching) {
        this.filterTo(HashSet()) { it.trim().endsWith(trimmedQuery) }
    } else this  // Waste of time
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