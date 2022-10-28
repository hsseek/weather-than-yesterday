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
import com.hsseek.betterthanyesterday.network.*
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
private const val DATA_TAG = "KMA-Data"
private const val VILLAGE_TEMPERATURE_TAG = "TMP"
private const val LOW_TEMPERATURE_TAG = "TMN"
private const val HIGH_TEMPERATURE_TAG = "TMX"
private const val SHORT_TERM_TEMPERATURE_TAG = "T1H"
private const val RAIN_TAG = "PTY"
private const val LOW_TEMP_BASE_TIME = "0200"  // From 3:00
private const val HIGH_TEMP_BASE_TIME = "1100"  // From 12:00

private const val CODED_SNACK_BAR_ID = 0
private const val HIGHLIGHTED_SETTING_ROW = 1  // If out of index, none will be highlighted.

class WeatherViewModel(
    application: Application,
    private val userPrefsRepo: UserPreferencesRepository,
) : AndroidViewModel(application) {
    val autoRegionCoordinate: CoordinatesXy = PresetRegion.Auto.xy
    private var lastSuccessfulTimeArray: Array<WeatherDataTimeStamp?> = arrayOfNulls(11)
    var referenceCal: Calendar = refreshReferenceCal()
        get() = field.clone() as Calendar
        private set
    private fun refreshReferenceCal(): Calendar {  // THE TIME MACHINE
        val now = getCurrentKoreanDateTime()
        if (DEBUG_FLAG) Log.d(TAG, "Reference time: ${now.time}")
        return now
    }

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
    private val _toShowLoadingDialog = mutableStateOf(false)
    val toShowSearchRegionDialogLoading: Boolean
        get() = _toShowLoadingDialog.value

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
        val isSameCoordinate: Boolean
        if (region == forecastRegion) {
            isSameCoordinate = true
            if (DEBUG_FLAG) Log.d(TAG, "forecastRegion unchanged: ${forecastRegion.toRegionString()}")
        } else {
            // Store the selection.
            viewModelScope.launch {
                userPrefsRepo.updateForecastRegion(region)
                defaultRegionCandidates[1] = region
            }

            if (region.address != forecastRegion.address) {
                // Update the city name.
                updateRepresentedCityName(region.address)
            }

            // Check if the location changed before reassigning.
            isSameCoordinate = region.xy == forecastRegion.xy
            forecastRegion = region
            if (DEBUG_FLAG) Log.d(TAG, "forecastRegion changed: ${forecastRegion.toRegionString()}")
        }

        if (isSameCoordinate) {
            if (DEBUG_FLAG) Log.d(TAG, "The same coordinates.")
            checkTimeThenRequest(isSecondary)
        } else {
            if (DEBUG_FLAG) Log.d(LOCATION_TAG, "A new coordinates\t: (${region.xy.nx}, ${region.xy.ny})")

            // No need to check time, request the new data for the location.
            requestAllWeatherData(isSecondary)
        }
    }

    private fun updateRepresentedCityName(address: String) {
        if (DEBUG_FLAG) Log.d(TAG, "updateRepresentedCityName(...) called.")
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
        var isNullPresent = false
        if (_hourlyTempToday.value == null) {
            if (DEBUG_FLAG) Log.d(TAG, "hourlyTempToday is null.")
            isNullPresent = true
        }
        if (_hourlyTempDiff.value == null) {
            if (DEBUG_FLAG) Log.d(TAG, "hourlyTempDiff is null.")
            isNullPresent = true
        }
        if (_rainfallStatus.value == Sky.Undetermined) {
            if (DEBUG_FLAG) Log.d(TAG, "Rainfall status is null.")
            isNullPresent = true
        }

        for (dailyTemp in _dailyTemps.value) {
            if (dailyTemp.highest == stringForNull) {
                isNullPresent = true
                if (DEBUG_FLAG) Log.d(TAG, "${dailyTemp.day} ${CharacteristicTempType.Highest} is null.")
            }
            if (dailyTemp.lowest == stringForNull) {
                if (DEBUG_FLAG) Log.d(TAG, "${dailyTemp.day} ${CharacteristicTempType.Lowest} is null.")
                isNullPresent = true
            }
        }
        return isNullPresent
    }

    private fun isNewDataReleased(): Boolean {  // I know the word 'data' is plural, but...
        for (tst in lastSuccessfulTimeArray) {
            if (tst == null) return true  // No former data
            else if (isNewDataReleasedAfter(tst, referenceCal)) {  // Outdated data
                if (DEBUG_FLAG) Log.d(TAG, "New ${tst.tag} available, request refresh.")
                return true
            }
        }
        return false
    }

    private fun requestAllWeatherData(isSecondary: Boolean) {
        if (isRefreshing) {
            if (!isSecondary) _toastMessage.value = ToastEvent(R.string.toast_refreshing_ongoing)
            if (DEBUG_FLAG) Log.d(TAG, "requestAllWeatherData() called but returned to respect the former process.")
            return
        } else {
            if (DEBUG_FLAG) Log.d(TAG, "requestAllWeatherData() called.")
        }

        _isRefreshing.value = true  // To be safe

        viewModelScope.launch(defaultDispatcher) {
            // Nullify information to let the user know it is "refreshing."
            // If new data are not available, the information will be filled again with the former data anyway.
            nullifyWeatherInfo()
            lastSuccessfulTimeArray.forEach { it?.invalidate() }
            val cal = referenceCal

            // Variables regarding the while loop
            var trialCount = 0
            var isCalModified = false

            // Data holders should survive over loops
            // Unconditional (condition is always true) data
            val todayFutureData = WeatherData(tst = WeatherDataTimeStamp("D${DayOfInterest.Today.dayOffset}-FT", condition = true))
            val tomorrowHighTempData = WeatherData(tst = WeatherDataTimeStamp("D${DayOfInterest.Tomorrow.dayOffset}-${CharacteristicTempType.Highest.descriptor}", condition = true))
            val tomorrowLowTempData = WeatherData(tst = WeatherDataTimeStamp("D${DayOfInterest.Tomorrow.dayOffset}-${CharacteristicTempType.Lowest.descriptor}", condition = true))
            val d2HighTempData = WeatherData(tst = WeatherDataTimeStamp("D${DayOfInterest.Day2.dayOffset}-${CharacteristicTempType.Highest.descriptor}", condition = true))
            val d2LowTempData = WeatherData(tst = WeatherDataTimeStamp("D${DayOfInterest.Day2.dayOffset}-${CharacteristicTempType.Lowest.descriptor}", condition = true))
            val yesterdayHighTempData = WeatherData(tst = WeatherDataTimeStamp("D${DayOfInterest.Yesterday.dayOffset}-${CharacteristicTempType.Highest.descriptor}", condition = true))
            val yesterdayLowTempData = WeatherData(tst = WeatherDataTimeStamp("D${DayOfInterest.Yesterday.dayOffset}-${CharacteristicTempType.Lowest.descriptor}", condition = true))
            val yesterdayShortTermTempData = WeatherData(tst = WeatherDataTimeStamp("D${DayOfInterest.Yesterday.dayOffset}-$SHORT_TERM_TEMPERATURE_TAG", condition = true))
            val todayShortTermData = WeatherData(tst = WeatherDataTimeStamp("D${DayOfInterest.Today.dayOffset}-${SHORT_TERM_TEMPERATURE_TAG}", condition = true))
            // Conditional data holders: They can be empty purposely.
            val todayHighTempData = WeatherData(tst = WeatherDataTimeStamp(
                "D${DayOfInterest.Today.dayOffset}-${CharacteristicTempType.Highest.descriptor}",
                condition = isCoveredByFutureTodayData(getKmaBaseTime(cal = referenceCal, roundOff = Village).hour.toInt(), HIGH_TEMP_BASE_TIME.toInt()),
            ))
            val todayLowTempData = WeatherData(tst = WeatherDataTimeStamp(
                "D${DayOfInterest.Today.dayOffset}-${CharacteristicTempType.Lowest.descriptor}",
                condition = isCoveredByFutureTodayData(getKmaBaseTime(cal = referenceCal, roundOff = Village).hour.toInt(), LOW_TEMP_BASE_TIME.toInt()),
            ))

            val weatherDataArray = arrayOf(
                todayFutureData,
                tomorrowHighTempData,
                tomorrowLowTempData,
                d2HighTempData,
                d2LowTempData,
                yesterdayHighTempData,
                yesterdayLowTempData,
                yesterdayShortTermTempData,
                todayShortTermData,
                todayHighTempData,
                todayLowTempData,
            )

            val separator = "\n────────────────\n"
            val reportHeader = separator +
                    "App version: ${BuildConfig.VERSION_CODE}\n" +
                    "SDK version:${android.os.Build.VERSION.SDK_INT}" +
                    separator +
                    "Weather data to compose the main screen\n"
            val dataReport = StringBuilder()

            WeatherApi.clearResponseString()
            kmaJob?.cancelAndJoin()
            if (DEBUG_FLAG) Log.d(TAG, "kmaJob status: ${kmaJob?.status()}")
            kmaJob = launch(defaultDispatcher) {
                if (DEBUG_FLAG) Log.d(TAG, "kmaJob launched.")
                while (trialCount < NETWORK_MAX_RETRY) {
                    if (isCalModified) {
                        if (DEBUG_FLAG) Log.w(TAG, "Retrieving data for modified reference time: ${cal.time}")
                    }
                    // If the frame has been shifted, the page on the target data are shifted as well.
                    val hoursToShift = if (isCalModified) VILLAGE_HOUR_INTERVAL else 0

                    dataReport.append(reportHeader)
                    try {
                        // Clear the former response so that it always hold the last one.
                        withTimeout(minOf(NETWORK_TIMEOUT_MIN + trialCount * NETWORK_ADDITIONAL_TIMEOUT, NETWORK_TIMEOUT_MAX)) {
                            val networkJob = launch(defaultDispatcher) {
                                val calValue = cal.clone() as Calendar
                                if (isCalModified) calValue.add(Calendar.HOUR_OF_DAY, VILLAGE_HOUR_INTERVAL)
                                val today: String = formatToKmaDate(calValue)
                                calValue.add(Calendar.DAY_OF_YEAR, -1)
                                val yesterday: String = formatToKmaDate(calValue)
                                if (DEBUG_FLAG) Log.d(TAG, "today: $today, yesterday: $yesterday")

                                // BaseTimes are to be modified.
                                val latestVillageBaseTime = getKmaBaseTime(cal = cal, roundOff = Village)  // 2:00 for 2:11 ~ 5:10
                                val latestHourlyBaseTime = getKmaBaseTime(cal = cal, roundOff = Hour)  // 2:00 for 3:00 ~ 3:59
                                if (DEBUG_FLAG) Log.d(TAG, "VillageBaseTime: ${latestVillageBaseTime.toTimeString()}")
                                if (DEBUG_FLAG) Log.d(TAG, "HourlyBaseTime: ${latestHourlyBaseTime.toTimeString()}")

                                dataReport.append("${latestHourlyBaseTime.toTimeString()}." +
                                        "${latestVillageBaseTime.hour.toInt()/100}." +
                                        "${forecastRegion.xy.nx}.${forecastRegion.xy.ny}\n")

                                val characteristicTempHourSpan = 6  // Monitors 6 hours(3 AM..8 AM and 12 PM..17 PM)

                                // Conditional tasks, only if the current time passed baseTime
                                var todayLowTempResponse: Deferred<Response<ForecastResponse>>? = null
                                var todayHighTempResponse: Deferred<Response<ForecastResponse>>? = null

                                val fetchingStartTime = System.currentTimeMillis()

                                // The response includes duplicate data of earlier hours of hourly data.
                                // However, as we can cut tails only, retrieve the whole data.
                                val todayFutureResponse: Deferred<Response<ForecastResponse>>? = if (todayFutureData.isValidReferring(referenceCal)) {
                                    null  // Valid data held. No need to retrieve again.
                                } else {
                                    async(retrofitDispatcher) {
                                        val remainingHours = 24 - hoursNotFuture(cal, latestVillageBaseTime, isCalModified)
                                        WeatherApi.service.getVillageWeather(
                                            baseDate = latestVillageBaseTime.date,  // Yesterday(23:00 only) or today
                                            baseTime = latestVillageBaseTime.hour,
                                            numOfRows = remainingHours * VILLAGE_ROWS_PER_HOUR,
                                            nx = forecastRegion.xy.nx,
                                            ny = forecastRegion.xy.ny,
                                        )
                                    }
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
                                    // fcstTime starts from today 12:00.
                                    futureDayBaseDate = today
                                    futureDayBaseTime = baseTime1.toString()
                                    tomorrowLowTempPageNo = 5
                                    tomorrowHighTempPageNo = 7
                                } else {
                                    // fcstTime starts from today 00:00.
                                    futureDayBaseDate = yesterday
                                    futureDayBaseTime = baseTime2.toString()
                                    tomorrowLowTempPageNo = 8
                                    tomorrowHighTempPageNo = 10
                                }
                                if (DEBUG_FLAG) Log.d(TAG, "future base: $futureDayBaseDate-$futureDayBaseTime")

                                val tomorrowHighTempResponse = if (tomorrowHighTempData.isValidReferring(referenceCal)) null else {
                                    async(retrofitDispatcher) {
                                        WeatherApi.service.getVillageWeather(
                                            baseDate = futureDayBaseDate,  // Yesterday(23:00 only) or today
                                            baseTime = futureDayBaseTime,
                                            numOfRows = futureDayRowCount,
                                            pageNo = tomorrowHighTempPageNo,
                                            nx = forecastRegion.xy.nx,
                                            ny = forecastRegion.xy.ny,
                                        )
                                    }
                                }

                                val tomorrowLowTempResponse = if (tomorrowLowTempData.isValidReferring(referenceCal)) null else {
                                    async(retrofitDispatcher) {
                                        WeatherApi.service.getVillageWeather(
                                            baseDate = futureDayBaseDate,  // Yesterday(23:00 only) or today
                                            baseTime = futureDayBaseTime,
                                            numOfRows = futureDayRowCount,
                                            pageNo = tomorrowLowTempPageNo,
                                            nx = forecastRegion.xy.nx,
                                            ny = forecastRegion.xy.ny,
                                        )
                                    }
                                }

                                val d2HighTempResponse = if (d2HighTempData.isValidReferring(referenceCal)) null else {
                                    async(retrofitDispatcher) {
                                        WeatherApi.service.getVillageWeather(
                                            baseDate = futureDayBaseDate,  // Yesterday(23:00 only) or today
                                            baseTime = futureDayBaseTime,
                                            numOfRows = futureDayRowCount,
                                            pageNo = tomorrowHighTempPageNo + pagesPerDay,
                                            nx = forecastRegion.xy.nx,
                                            ny = forecastRegion.xy.ny,
                                        )
                                    }
                                }

                                val d2LowTempResponse = if (d2LowTempData.isValidReferring(referenceCal)) null else {
                                    async(retrofitDispatcher) {
                                        WeatherApi.service.getVillageWeather(
                                            baseDate = futureDayBaseDate,  // Yesterday(23:00 only) or today
                                            baseTime = futureDayBaseTime,
                                            numOfRows = futureDayRowCount,
                                            pageNo = tomorrowLowTempPageNo + pagesPerDay,
                                            nx = forecastRegion.xy.nx,
                                            ny = forecastRegion.xy.ny,
                                        )
                                    }
                                }

                                val latestVillageHour = latestVillageBaseTime.hour.toInt()
                                if (isCoveredByFutureTodayData(latestVillageHour, LOW_TEMP_BASE_TIME.toInt())) {
                                    val numOfRows = if (latestVillageHour == LOW_TEMP_BASE_TIME.toInt() + VILLAGE_HOUR_INTERVAL * 100) {  // During baseTime = 500
                                        getRowCount(VILLAGE_HOUR_INTERVAL)  // Missing hours span only an interval: 3:00, 4:00, 5:00
                                    } else {  // After baseTime became 800, the lowest temperature won't be renewed.
                                        getRowCount(characteristicTempHourSpan)  // Full span: 3:00, ..., 7:00
                                    }
                                    if (DEBUG_FLAG) Log.d(todayLowTempData.tst.tag, "$numOfRows rows")

                                    todayLowTempResponse = if (todayLowTempData.isValidReferring(referenceCal)) null else {
                                        async(retrofitDispatcher) {
                                            WeatherApi.service.getVillageWeather(
                                                baseDate = today,
                                                baseTime = LOW_TEMP_BASE_TIME,  // fsctTime starts from 03:00 AM
                                                numOfRows = numOfRows,
                                                nx = forecastRegion.xy.nx,
                                                ny = forecastRegion.xy.ny,
                                            )
                                        }
                                    }
                                }
                                if (isCoveredByFutureTodayData(latestVillageHour, HIGH_TEMP_BASE_TIME.toInt())) {  // As soon as the latest highest temperature span available
                                    val numOfRows = if (latestVillageHour == HIGH_TEMP_BASE_TIME.toInt() + VILLAGE_HOUR_INTERVAL * 100) {  // During baseTime = 1400
                                        getRowCount(VILLAGE_HOUR_INTERVAL)  // Missing hours span only an interval: 12:00, 13:00, 14:00
                                    } else {  // After baseTime became 1700, the highest temperature won't be renewed.
                                        getRowCount(characteristicTempHourSpan)  // Full span: 12:00, ..., 16:00
                                    }
                                    if (DEBUG_FLAG) Log.d("D${DayOfInterest.Today.dayOffset}-${CharacteristicTempType.Highest.descriptor}", "$numOfRows rows")
                                    todayHighTempResponse = if (todayHighTempData.isValidReferring(referenceCal)) null else {
                                        async(retrofitDispatcher) {
                                            WeatherApi.service.getVillageWeather(
                                                baseDate = today,
                                                baseTime = HIGH_TEMP_BASE_TIME,  // fsctTime starts from the noon
                                                numOfRows = numOfRows,
                                                nx = forecastRegion.xy.nx,
                                                ny = forecastRegion.xy.ny,
                                            )
                                        }
                                    }
                                }

                                val todayShortTermResponse = if (todayShortTermData.isValidReferring(referenceCal)) null else {
                                    async(retrofitDispatcher) {
                                        WeatherApi.service.getShortTermWeather(
                                            baseDate = latestHourlyBaseTime.date,
                                            baseTime = latestHourlyBaseTime.hour,  // 14:50 -> 13:00, 15:00 -> 14:00
                                            numOfRows = 30,  // [LGT -> PTY -> RN1 -> SKY -> TH1] -> REH -> UUU -> VVV -> ...
                                            nx = forecastRegion.xy.nx,
                                            ny = forecastRegion.xy.ny,
                                        )
                                    }
                                }

                                // Not really hourly: VillageWeather with retaining the old name for readability.
                                val yesterdayHourlyTempResponse = if (yesterdayShortTermTempData.isValidReferring(referenceCal)) null else {
                                    async(retrofitDispatcher) {
                                        val yesterdayVillageBaseTime = getKmaBaseTime(cal = cal, dayOffset = -1, roundOff = Village)
                                        if (DEBUG_FLAG) Log.d(yesterdayShortTermTempData.tst.tag, "baseTime: ${yesterdayVillageBaseTime.toTimeString()}")

                                        // At 4:00, the latest data start from fcstTime of 3:00.
                                        // the data for the target hour(5 AM) are on the 3rd page, and so on...
                                        val pageNo = hoursToShift + cal.hour() - yesterdayVillageBaseTime.hour.toInt()/100 + 1
                                        WeatherApi.service.getVillageWeather(
                                            baseDate = yesterdayVillageBaseTime.date,
                                            baseTime = yesterdayVillageBaseTime.hour,
                                            numOfRows = VILLAGE_ROWS_PER_HOUR,
                                            pageNo = pageNo,
                                            nx = forecastRegion.xy.nx,
                                            ny = forecastRegion.xy.ny,
                                        )
                                    }
                                }

                                /* Deprecated: Short-term forecasts are not reliable: data absent, wrong data(20 C at 8 AM of the 26th of Oct).
                                val todayHourlyTempResponse = async(retrofitDispatcher) {
                                    WeatherApi.service.getShortTermWeather(
                                        baseDate = latestHourlyBaseTime.date,
                                        baseTime = latestHourlyBaseTime.hour,  // 14:50 -> 13:00, 15:00 -> 14:00
                                        numOfRows = 30,  // [LGT -> PTY -> RN1 -> SKY -> TH1] -> REH -> UUU -> VVV -> ...
                                        nx = forecastRegion.xy.nx,
                                        ny = forecastRegion.xy.ny,
                                    )
                                }

                                // Occasionally, short-term temperatures are not available for the hour. A backup data for that such cases.
                                val yesterdayHourlyTempResponse = async(retrofitDispatcher) {
                                    val yesterdayHourlyBaseTime = getKmaBaseTime(cal = calValue, dayOffset = -1, roundOff = Hour)

                                    WeatherApi.service.getShortTermWeather(
                                        baseDate = yesterdayHourlyBaseTime.date,
                                        baseTime = yesterdayHourlyBaseTime.hour,  // 14:50 -> 13:00, 15:00 -> 14:00
                                        numOfRows = 6,
                                        pageNo = 5,  // LGT -> PTY -> RN1 -> SKY -> [TH1] -> REH -> UUU -> VVV -> ...
                                        nx = forecastRegion.xy.nx,
                                        ny = forecastRegion.xy.ny,
                                    )
                                }

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

                                val yesterdayHourlyTempBackupData = if (yesterdayHourlyTempData.isEmpty()) {
                                        yesterdayHourlyTempBackupResponse.await().body()?.response?.body?.items?.item ?: emptyList()
                                } else emptyList()
                                */

                                /*// KMA often emits the data earlier, at which expires the oldest observed data which is the following lines try to retrieve.
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

                                val yesterdayLowTempResponse = if (yesterdayLowTempData.isValidReferring(referenceCal)) null else {
                                    async(retrofitDispatcher) {
                                        WeatherApi.service.getVillageWeather(
                                            baseDate = yesterday,
                                            baseTime = LOW_TEMP_BASE_TIME,  // fsctTime starts from 03:00 AM
                                            numOfRows = getRowCount(characteristicTempHourSpan),  // Full span: 3:00, ..., 7:00
                                            nx = forecastRegion.xy.nx,
                                            ny = forecastRegion.xy.ny,
                                        )
                                    }
                                }

                                val yesterdayHighTempResponse = if (yesterdayHighTempData.isValidReferring(referenceCal)) null else {
                                    async(retrofitDispatcher) {
                                        WeatherApi.service.getVillageWeather(
                                            baseDate = yesterday,
                                            baseTime = HIGH_TEMP_BASE_TIME,  // fsctTime starts from the noon
                                            numOfRows = getRowCount(characteristicTempHourSpan),  // Full span: 3:00, ..., 7:00
                                            nx = forecastRegion.xy.nx,
                                            ny = forecastRegion.xy.ny,
                                        )
                                    }
                                }

                                // Gather the data.
                                todayFutureResponse?.let { todayFutureData.updateContents(it.await().body()?.response?.body?.items?.item ?: emptyList(), referenceCal) }
                                tomorrowHighTempResponse?.let { tomorrowHighTempData.updateContents(it.await().body()?.response?.body?.items?.item ?: emptyList(), referenceCal) }
                                tomorrowLowTempResponse?.let { tomorrowLowTempData.updateContents(it.await().body()?.response?.body?.items?.item ?: emptyList(), referenceCal) }
                                d2HighTempResponse?.let { d2HighTempData.updateContents(it.await().body()?.response?.body?.items?.item ?: emptyList(), referenceCal) }
                                d2LowTempResponse?.let { d2LowTempData.updateContents(it.await().body()?.response?.body?.items?.item ?: emptyList(), referenceCal) }
                                yesterdayHighTempResponse?.let { yesterdayHighTempData.updateContents(it.await().body()?.response?.body?.items?.item ?: emptyList(), referenceCal) }
                                yesterdayLowTempResponse?.let { yesterdayLowTempData.updateContents(it.await().body()?.response?.body?.items?.item ?: emptyList(), referenceCal) }
                                yesterdayHourlyTempResponse?.let { yesterdayShortTermTempData.updateContents(it.await().body()?.response?.body?.items?.item ?: emptyList(), referenceCal) }
                                todayShortTermResponse?.let { todayShortTermData.updateContents(it.await().body()?.response?.body?.items?.item ?: emptyList(), referenceCal) }
                                todayHighTempResponse?.let { todayHighTempData.updateContents(it.await().body()?.response?.body?.items?.item ?: emptyList(), referenceCal) }
                                todayLowTempResponse?.let { todayLowTempData.updateContents(it.await().body()?.response?.body?.items?.item ?: emptyList(), referenceCal) }
                                logElapsedTime(TAG, "Fetching data", fetchingStartTime)

                                // Log for debugging.
                                val loggingStartTime = System.currentTimeMillis()
                                launch(defaultDispatcher) {
                                    var size = 0
                                    val emptyMessage = "List empty."

                                    weatherDataArray.forEach {
                                        if (it.contents.isEmpty()) {
                                            dataReport.appendAndLog(it.tst.tag, emptyMessage)
                                        } else {
                                            for (item in it.contents) {
                                                if (item.category == SHORT_TERM_TEMPERATURE_TAG ||
                                                    item.category == VILLAGE_TEMPERATURE_TAG) {
                                                    dataReport.appendAndLog(it.tst.tag, "$item")
                                                }
                                                size++
                                            }
                                        }
                                    }
                                    logElapsedTime(TAG, "Logging $size items", loggingStartTime)
                                }

                                // Refresh the highest/lowest temperatures
                                launch { refreshTodayCharacteristicTemp(cal, todayHighTempData.contents, todayLowTempData.contents, todayFutureData.contents) }
                                launch { refreshCharacteristicTemp(DayOfInterest.Tomorrow, tomorrowHighTempData.contents, tomorrowLowTempData.contents) }
                                launch { refreshCharacteristicTemp(DayOfInterest.Day2, d2HighTempData.contents, d2LowTempData.contents) }
                                launch {
                                    refreshCharacteristicTemp(DayOfInterest.Yesterday, yesterdayHighTempData.contents, yesterdayLowTempData.contents)
                                    // Refresh the hourly temperature and the difference(dependant to yesterday's highest/lowest temperature).
                                    refreshHourlyTemp(cal, isCalModified, todayFutureData.contents, yesterdayShortTermTempData.contents)
                                }

                                // Refresh the rainfall status.
                                launch { refreshRainfall(cal, todayShortTermData.contents, todayFutureData.contents) }
                            }
                            networkJob.join()
                            adjustTodayCharTemp()
                            buildDailyTemps()

                            if (isNullInfoIncluded()) throw JsonSyntaxException("No Data(Result code: 03)")
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
                                if (!isCalModified) cal.add(Calendar.HOUR_OF_DAY, -VILLAGE_HOUR_INTERVAL)
                                isCalModified = true
                            } else {  // Maximum count of trials has been reached.
                                Log.e(TAG, "Stopped retrying", e)
                                dataReport.appendStackTrace(separator, e)
                                _exceptionSnackBarEvent.value = SnackBarEvent(
                                    getErrorReportSnackBarContent(
                                        R.string.snack_bar_error_kma_na,
                                        dataReport.appendRawData(separator))
                                )
                                break
                            }
                        } else {  // Not worth retrying, just stop.
                            dataReport.appendStackTrace(separator, e)
                            when (e) {
                                is CancellationException -> if (DEBUG_FLAG) Log.d(TAG, "kmaJob cancelled.")
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

                    // Update lastSuccessfulTimeArray
                    weatherDataArray.forEachIndexed { index, weatherData ->
                        lastSuccessfulTimeArray[index] = weatherData.tst
                    }
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
    private fun hoursNotFuture(referenceTime: Calendar, baseTime: KmaTime, isCalModified: Boolean): Int {
        // At 20:10, for instance, the latestVillageBaseTime is 17:00, not 20:00. So, 3 more hours must be covered.
        val correctionForDelayed = if (referenceTime.get(Calendar.MINUTE) > VILLAGE_DELAYED_MINUTES) {
            0 } else VILLAGE_HOUR_INTERVAL
        val correctionForModified = if (isCalModified) VILLAGE_HOUR_INTERVAL else 0
        val correction = correctionForDelayed + correctionForModified
        val hour = baseTime.hour.toInt() / 100

        // The count that should be + adjustment to retrieve additional count
        return (hour + 1 + correction) % 24 - correction
    }

    /**
     * Update the rainfall status of today,
     * based on [todayShortTermData] and [futureTodayData].
     * if there are data from the same base time, [todayShortTermData] take the priority.
     * Always called from a ViewModelScope.
     * */
    private fun refreshRainfall(
        cal: Calendar,
        todayShortTermData: List<ForecastResponse.Item>,
        futureTodayData: List<ForecastResponse.Item>,
    ) {
        val today: String = formatToKmaDate(cal)
        val primaryCoveredHourMax: Int? = todayShortTermData.maxOfOrNull { it.fcstTime }

        // Remove duplicate data according to the priority (More recent data is preferred.)
        val hourlyRainfallData = todayShortTermData.filter { it.category == RAIN_TAG && it.fcstDate == today.toInt() }
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
        val cal = referenceCal
        cal.add(Calendar.DAY_OF_YEAR, -2)

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
            it.category == SHORT_TERM_TEMPERATURE_TAG || it.category == HIGH_TEMPERATURE_TAG
        }?.maxByOrNull { it.fcstValue.toFloat() }?.fcstValue?.toFloat()?.roundToInt()  // TMN, TMX values are Floats.
        highestTemps[index] = highest

        val lowest = lowTempCandidates?.filter {
            it.category == SHORT_TERM_TEMPERATURE_TAG || it.category == LOW_TEMPERATURE_TAG
        }?.minByOrNull { it.fcstValue.toFloat() }?.fcstValue?.toFloat()?.roundToInt()
        lowestTemps[index] = lowest
    }

    private fun refreshTodayCharacteristicTemp(
        cal: Calendar,
        todayHighTempData: List<ForecastResponse.Item>,
        todayLowTempData: List<ForecastResponse.Item>,
        futureTodayData: List<ForecastResponse.Item>,
    ) {
        val today: String = formatToKmaDate(cal)
        val data = todayHighTempData + todayLowTempData + futureTodayData
        var isPrimed = false

        // Using minOf(...) or maxOf(...) requires iterate each time, which is inefficient.
        data.forEach { item ->
            if (item.fcstTime == today.toInt() &&  // If the Calendar has been modified, the first rows might include yesterday's data.
                item.category == VILLAGE_TEMPERATURE_TAG ||
                item.category == SHORT_TERM_TEMPERATURE_TAG ||
                item.category == HIGH_TEMPERATURE_TAG ||
                item.category == LOW_TEMPERATURE_TAG
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
        cal: Calendar,
        isCalModified: Boolean,
        todayData: List<ForecastResponse.Item>,
        yesterdayShortTermTempData: List<ForecastResponse.Item>,
    ) {
        val calValue = cal.clone() as Calendar
        calValue.add(Calendar.HOUR_OF_DAY, 1 + if (isCalModified) VILLAGE_HOUR_INTERVAL else 0)
        val nextHour = formatToKmaHour(calValue).toInt()

        var todayTemp: Int? = null
        var yesterdayTemp: Int? = null

        for (i in todayData) {
            if (i.fcstTime == nextHour &&
                (i.category == SHORT_TERM_TEMPERATURE_TAG || i.category == VILLAGE_TEMPERATURE_TAG)
            ) {
                todayTemp = i.fcstValue.toInt()
                break
            }
        }

        for (i in yesterdayShortTermTempData /*+ yesterdayHourlyTempBackupData*/) {
            if (i.fcstTime == nextHour &&
                (i.category == SHORT_TERM_TEMPERATURE_TAG || i.category == VILLAGE_TEMPERATURE_TAG)
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
                    if (DEBUG_FLAG) Log.w(TAG, "Overridden by $SHORT_TERM_TEMPERATURE_TAG: $ht -> $tt")
                }
            }
            lowestTemps[index]?.let { lt ->
                if (tt < lt) {
                    lowestTemps[index] = tt
                    if (DEBUG_FLAG) Log.w(TAG, "Overridden by $SHORT_TERM_TEMPERATURE_TAG: $lt -> $tt")
                }
            }
        }
    }

    fun onClickRefresh(region: ForecastRegion = forecastRegion) {
        if (DEBUG_FLAG) Log.d(TAG, "onClickRefresh() called.")
        referenceCal = refreshReferenceCal()
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
        }
    }

    /**
     * [isSecondary] is true when it was a (confirming) result from the [locationCallback].
     * */
    private fun checkTimeThenRequest(isSecondary: Boolean = false) {
        if (isNewDataReleased()) {
            requestAllWeatherData(isSecondary)
        } else {
            if (!isSecondary) {
                viewModelScope.launch(defaultDispatcher) {
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
                    if (isExplicit) _toShowLoadingDialog.value = true
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
        updateRepresentedCityName(region.address)
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
        _toShowLoadingDialog.value = false
    }

    companion object {
        private const val REQUEST_INTERVAL: Long = 60 * 60 * 1000
        val currentLocationRequest: LocationRequest = LocationRequest.Builder(REQUEST_INTERVAL).build()
    }

    open class WeatherData(
        contents: List<ForecastResponse.Item> = emptyList(),
        val tst: WeatherDataTimeStamp,
    ) {
        var contents = contents
            private set

        fun isValidReferring(cal: Calendar): Boolean {
            val isValid = !isNewDataReleasedAfter(this.tst, cal) && contents.isNotEmpty()
            if (DEBUG_FLAG) {
                if (isValid) Log.d(DATA_TAG, "(${tst.tag})\tData still valid, releasing the former data.")
                else if (DEBUG_FLAG) Log.d(DATA_TAG, "(${tst.tag})\tNeed to refresh data.")
            }
            return isValid
        }

        fun updateContents(contents: List<ForecastResponse.Item>, cal: Calendar) {
            tst.lastSuccessfulTime = if (contents.isEmpty()) null else cal
            this.contents = contents
        }
    }

    class WeatherDataTimeStamp(
        val tag: String,
        lastSuccessfulTime: Calendar? = null,
        val condition: Boolean,
    ) {
        var lastSuccessfulTime = lastSuccessfulTime
            set(value) {
                if (DEBUG_FLAG) Log.d(tag, "Last successful: ${value?.get(Calendar.HOUR_OF_DAY)}")
                field = value
            }

        fun invalidate() {
            lastSuccessfulTime = null
        }
    }
}

private fun isNewDataReleasedAfter(tst: WeatherViewModel.WeatherDataTimeStamp, cal: Calendar): Boolean {
    return if (tst.condition) {
        checkBaseTime(tst, cal)
    } else {
        if (DEBUG_FLAG) Log.d(DATA_TAG, "(${tst.tag})\tCondition not met.")
        false
    }
}

private fun checkBaseTime(tst: WeatherViewModel.WeatherDataTimeStamp, cal: Calendar): Boolean {
    val lastCheckedCal = tst.lastSuccessfulTime
    val tag = tst.tag
    return if (lastCheckedCal == null) {
        if (DEBUG_FLAG) Log.d(DATA_TAG, "($tag)\tViewModel doesn't hold any data.")
        true
    } else {
        val lastBaseTime = getKmaBaseTime(cal = lastCheckedCal, roundOff = Village)
        val currentBaseTime = getKmaBaseTime(cal = cal, roundOff = Village)

        if (currentBaseTime.isLaterThan(lastBaseTime)) {
            if (DEBUG_FLAG) Log.d(DATA_TAG, "($tag)\tNew data are available.(${lastBaseTime.hour} -> ${currentBaseTime.hour})")
            true
        } else {
            if (cal.hour() > lastCheckedCal.hour()) {
                if (DEBUG_FLAG) Log.d(DATA_TAG, "($tag)\tNew data are required.")
                true
            } else {
                if (DEBUG_FLAG) Log.d(DATA_TAG, "($tag)\tNo new data available.")
                false
            }
        }
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
    Highest("H"), Lowest("L")
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