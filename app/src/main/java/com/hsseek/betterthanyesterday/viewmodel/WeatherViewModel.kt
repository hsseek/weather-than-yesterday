package com.hsseek.betterthanyesterday.viewmodel

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Address
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
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
import com.hsseek.betterthanyesterday.data.*
import com.hsseek.betterthanyesterday.location.*
import com.hsseek.betterthanyesterday.network.*
import com.hsseek.betterthanyesterday.util.*
import com.hsseek.betterthanyesterday.util.KmaHourRoundOff.Hour
import com.hsseek.betterthanyesterday.util.KmaHourRoundOff.Village
import com.hsseek.betterthanyesterday.widget.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.Response
import java.io.IOException
import java.net.UnknownHostException
import java.util.*
import kotlin.math.roundToInt


private const val TAG = "WeatherViewModel"
private const val DATA_TAG = "KMA-Data"
const val VILLAGE_TEMPERATURE_TAG = "TMP"
private const val SHORT_TERM_TEMPERATURE_TAG = "T1H"
private const val RAIN_TAG = "PTY"
private const val HOURLY_PRECIPITATION_TAG = "RN1"
private const val PRECIPITATION_TAG = "PCP"
const val TEST_HOUR_OFFSET = 1

private const val HIGHLIGHTED_SETTING_ROW = 3  // If out of index, none will be highlighted.
private const val HARDCODED_SNACK_BAR_ID = 4  // Don't forget to change [snack_bar_notice] as well.

class WeatherViewModel(
    application: Application,
    private val userPrefsRepo: UserPreferencesRepository,
) : AndroidViewModel(application) {
    val autoRegionCoordinate: CoordinatesXy = PresetRegion.Auto.xy

    // Array of last successful timestamps of responses such as todayFutureResponse, tomorrowResponse, etc.
    private var lastSuccessfulTimeArray: Array<WeatherDataTimeStamp?> = arrayOfNulls(6)
    var referenceCal: Calendar = refreshReferenceCal()
        get() = field.clone() as Calendar
        private set
    private fun refreshReferenceCal(): Calendar {
        val now = getCurrentKoreanTime()
        // THE TIME MACHINE, e.g.
        // now.set(Calendar.DAY_OF_MONTH, 16)
        // now.set(Calendar.HOUR_OF_DAY, 22)
        // now.set(Calendar.MINUTE, 30)
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

    private var darkModeCode = DarkMode.System.code
    private val _isDarkTheme = mutableStateOf(false)
    val isDarkTheme: Boolean
        get() = _isDarkTheme.value

    private val _isSimplified = mutableStateOf(false)
    val isSimplified: Boolean
        get() = _isSimplified.value

    private val _isDaybreakMode = mutableStateOf(false)
    val isDaybreakMode: Boolean
        get() = _isDaybreakMode.value

    // TODO: Enable Search Region dialog
//    private val _isPresetRegion = mutableStateOf(false)
    private val _isPresetRegion = mutableStateOf(true)
    val isPresetRegion: Boolean
        get() = _isPresetRegion.value

    // TODO: Adjustable hourOffset with a Slider in Settings
    val hourOffset = TEST_HOUR_OFFSET

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
    private val _selectedForecastRegionIndex = mutableIntStateOf(if (isForecastRegionAuto) 0 else 1)
    val selectedForecastRegionIndex: Int
        get() = _selectedForecastRegionIndex.intValue

    // Displayed location names
    private val _cityName: MutableState<String> = mutableStateOf(stringForNull)
    val cityName: String
        get() = _cityName.value

    private val _districtName: MutableState<String> = mutableStateOf(stringForNull)
    val districtName: String
        get() = _districtName.value

    // The highest/lowest temperatures of today, tomorrow and the day after tomorrow
    private val temperatureCount: Int = 3
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
        } else {
            if (DEBUG_FLAG) Log.d(LOCATION_TAG, "A new coordinates\t: (${region.xy.nx}, ${region.xy.ny})")
        }
        requestAllWeatherData(isSecondary)
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

            // Data holders should survive over loops
            // Unconditional (condition is always true) data
            val todayData = WeatherData(tst = WeatherDataTimeStamp("D${DayOfInterest.Today.dayOffset}", condition = true))
            val tomorrowData = WeatherData(tst = WeatherDataTimeStamp("D${DayOfInterest.Tomorrow.dayOffset}", condition = true))
            val d2Data = WeatherData(tst = WeatherDataTimeStamp("D${DayOfInterest.Day2.dayOffset}", condition = true))
            val todayNextHourTempData = WeatherData(tst = WeatherDataTimeStamp("D${DayOfInterest.Today.dayOffset}-HOURLY", condition = true))
            val yesterdayNextHourTempData = WeatherData(tst = WeatherDataTimeStamp("D${DayOfInterest.Yesterday.dayOffset}-HOURLY", condition = true))
            val todayShortTermData = WeatherData(tst = WeatherDataTimeStamp("D${DayOfInterest.Today.dayOffset}-${SHORT_TERM_TEMPERATURE_TAG}", condition = true))

            val weatherDataArray = arrayOf(
                todayData,
                tomorrowData,
                d2Data,
                todayNextHourTempData,
                yesterdayNextHourTempData,
                todayShortTermData,
            )
            if (weatherDataArray.size != lastSuccessfulTimeArray.size) {
                if (DEBUG_FLAG) Log.w(TAG, "Response holders don't match.(Responses: ${weatherDataArray.size}, tst: ${lastSuccessfulTimeArray.size})")
           }

            val separator = "\n────────────────\n"
            val reportHeader = separator +
                    "App version: ${BuildConfig.VERSION_CODE}\n" +
                    "SDK version: ${Build.VERSION.SDK_INT}" +
                    separator +
                    "Weather data to compose the main screen\n"
            val dataReport = StringBuilder()

            // Clear the former response so that it always hold the last one.
            WeatherApi.clearResponseString()
            kmaJob?.cancelAndJoin()
            if (DEBUG_FLAG) Log.d(TAG, "kmaJob status: ${kmaJob?.status()}")
            kmaJob = launch(defaultDispatcher) {
                if (DEBUG_FLAG) Log.d(TAG, "kmaJob launched.")
                while (trialCount < NETWORK_MAX_RETRY) {
                    dataReport.append(reportHeader)
                    try {
                        withTimeout(minOf(NETWORK_TIMEOUT_MIN + trialCount * NETWORK_ADDITIONAL_TIMEOUT, NETWORK_TIMEOUT_MAX)) {
                            val networkJob = launch(defaultDispatcher) {
                                val calValue = cal.clone() as Calendar
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

                                val fetchingStartTime = System.currentTimeMillis()

                                val todayResponse: Deferred<Response<ForecastResponse>>? =
                                    if (todayData.isValidReferring(referenceCal)) {
                                        null  // Valid data held. No need to retrieve again.
                                    } else getOneDayTempAsync(cal, DayOfInterest.Today.dayOffset, forecastRegion.xy)

                                val tomorrowResponse = if (tomorrowData.isValidReferring(referenceCal)) null else {
                                    getOneDayTempAsync(cal, DayOfInterest.Tomorrow.dayOffset, forecastRegion.xy)
                                }

                                val d2Response = if (d2Data.isValidReferring(referenceCal)) null else {
                                    getOneDayTempAsync(cal, DayOfInterest.Day2.dayOffset, forecastRegion.xy)
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

                                val todayNextHourTempResponse = if (todayNextHourTempData.isValidReferring(referenceCal)) null else {
                                    getHourlyTempAsync(
                                        forecastRegion.xy, cal,
                                        dayOffset = DayOfInterest.Today.dayOffset, hourOffset,
                                        todayNextHourTempData.tst.tag
                                    )
                                }

                                val yesterdayNextHourTempResponse = if (yesterdayNextHourTempData.isValidReferring(referenceCal)) null else {
                                    getHourlyTempAsync(
                                        forecastRegion.xy, cal,
                                        dayOffset = DayOfInterest.Yesterday.dayOffset, hourOffset,
                                        yesterdayNextHourTempData.tst.tag
                                    )
                                }

                                // Gather the data. The number of responses determines lastSuccessfulTimeArray.size().
                                todayResponse?.let { todayData.updateContents(it.await().body()?.response?.body?.items?.item ?: emptyList(), referenceCal) }
                                tomorrowResponse?.let { tomorrowData.updateContents(it.await().body()?.response?.body?.items?.item ?: emptyList(), referenceCal) }
                                d2Response?.let { d2Data.updateContents(it.await().body()?.response?.body?.items?.item ?: emptyList(), referenceCal) }
                                todayNextHourTempResponse?.let { todayNextHourTempData.updateContents(it.await().body()?.response?.body?.items?.item ?: emptyList(), referenceCal) }
                                yesterdayNextHourTempResponse?.let { yesterdayNextHourTempData.updateContents(it.await().body()?.response?.body?.items?.item ?: emptyList(), referenceCal) }
                                todayShortTermResponse?.let { todayShortTermData.updateContents(it.await().body()?.response?.body?.items?.item ?: emptyList(), referenceCal) }
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
                                                if (
                                                    item.category == SHORT_TERM_TEMPERATURE_TAG ||
                                                    item.category == VILLAGE_TEMPERATURE_TAG
                                                    ) dataReport.appendAndLog(it.tst.tag, "$item")
                                                size++
                                            }
                                        }
                                    }
                                    logElapsedTime(TAG, "Logging $size items", loggingStartTime)
                                }

                                // Refresh the highest/lowest temperatures
                                launch { refreshCharacteristicTemp(cal, DayOfInterest.Today, todayData.contents) }
                                launch { refreshCharacteristicTemp(cal, DayOfInterest.Tomorrow, tomorrowData.contents) }
                                launch { refreshCharacteristicTemp(cal, DayOfInterest.Day2, d2Data.contents) }
                                launch { refreshHourlyTemp(cal, hourOffset, todayNextHourTempData.contents, yesterdayNextHourTempData.contents) }

                                // Refresh the rainfall status.
                                launch { refreshRainfall(cal, todayShortTermData.contents, todayData.contents) }
                            }
                            networkJob.join()
                            adjustTodayCharTemp()
                            buildDailyTemps()

                            // Check whether Views can be composed.
                            checkNullInfo(
                                todayData,
                                tomorrowData,
                                d2Data,
                                todayNextHourTempData,
                                yesterdayNextHourTempData,
                            )
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
                            } else {  // Maximum count of trials has been reached.
                                Log.e(TAG, "Stopped retrying", e)
                                dataReport.appendStackTrace(separator, e)

                                // Check the result code from the server.
                                val response = WeatherApi.getResponseString()
                                val errorMessageId = when (Regex("<returnReasonCode>(\\d{2})</").find(response)?.groupValues?.get(1)) {
                                    "04" -> R.string.snack_bar_weather_error_http
                                    "05" -> R.string.snack_bar_weather_error_http
                                    // <string name="snack_bar_weather_error_http">공공 데이터 페이지 응답이 없습니다. 앱에서 할 수 있는 것이 없네요 &#128546;</string>
                                    "12" -> R.string.snack_bar_weather_error_expired_service
                                    // <string name="snack_bar_weather_error_expired_service">공공 데이터가 더 이상 제공되지 않습니다. 개발자에게 이 사실을 알려주세요.</string>
                                    "22" -> R.string.snack_bar_weather_error_traffic
                                    "30" -> R.string.snack_bar_weather_error_traffic
                                    // <string name="snack_bar_weather_error_traffic">앱의 공공 데이터 사용량이 한계에 달했습니다. 개발자에게 이 사실을 알려주세요.</string>
                                    else -> R.string.snack_bar_weather_error_general
                                    // <string name="snack_bar_weather_error_general">날씨 정보를 받아올 수 없습니다.</string>
                                }

                                _exceptionSnackBarEvent.value = SnackBarEvent(
                                    getErrorReportSnackBarContent(
                                        errorMessageId,
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

    private fun checkNullInfo(
        todayData: WeatherData,
        tomorrowData: WeatherData,
        d2Data: WeatherData,
        todayNextHourTempData: WeatherData,
        yesterdayNextHourTempData: WeatherData,
    ) {
        // Hourly temperatures
        if (_hourlyTempToday.value == null) {
            todayNextHourTempData.invalidate()
            throw JsonSyntaxException("No Data for today's hourly temp.")
        } else if (_hourlyTempDiff.value == null) {
            yesterdayNextHourTempData.invalidate()
            throw JsonSyntaxException("No Data for yesterday's next hour's temp.")
        }
        // Rainfall status
        if (_rainfallStatus.value == Sky.Undetermined) {
            // todayShortTermData.invalidate()  "Safe mode" fetching Village data only
            todayData.invalidate()
            throw JsonSyntaxException("No Data for rainfall status.")
        }
        // Daily temperatures,
        // today,
        if (dailyTemps[0].highest == stringForNull) {
            todayData.invalidate()
            throw JsonSyntaxException("No Data for today's highest temperature.")
        }
        if (dailyTemps[0].lowest == stringForNull) {
            todayData.invalidate()
            throw JsonSyntaxException("No Data for today's lowest temperature.")
        }
        // tomorrow,
        if (dailyTemps[1].highest == stringForNull) {
            tomorrowData.invalidate()
            throw JsonSyntaxException("No Data for tomorrow's highest temperature.")
        }
        if (dailyTemps[1].lowest == stringForNull) {
            tomorrowData.invalidate()
            throw JsonSyntaxException("No Data for tomorrow's lowest temperature.")
        }
        // and the day after tomorrow
        if (dailyTemps[2].highest == stringForNull) {
            d2Data.invalidate()
            throw JsonSyntaxException("No Data for the highest temperature of the day after tomorrow.")
        }
        if (dailyTemps[2].lowest == stringForNull) {
            d2Data.invalidate()
            throw JsonSyntaxException("No Data for the lowest temperature of the day after tomorrow.")
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

    /**
     * Update the rainfall status of today,
     * based on [todayShortTermData] and [todayFutureData].
     * if there are data from the same base time, [todayShortTermData] take the priority.
     * Always called from a ViewModelScope.
     * */
    private fun refreshRainfall(
        cal: Calendar,
        todayShortTermData: List<ForecastResponse.Item>,
        todayFutureData: List<ForecastResponse.Item>,
    ) {
        val today: String = formatToKmaDate(cal)
        val primaryCoveredHourMax: Int? = todayShortTermData.maxOfOrNull { it.fcstTime }

        // Remove duplicate data according to the priority (More recent data is preferred.)
        val hourlyRainfallData = todayShortTermData.filter {
            it.fcstDate == today.toInt() && it.category == RAIN_TAG
        }
        val futureTodayRainfallData: List<ForecastResponse.Item> = todayFutureData.filter {
            (it.fcstTime > (primaryCoveredHourMax ?: 0)) && it.category == RAIN_TAG
        }
        val rainfallData: List<ForecastResponse.Item> = hourlyRainfallData + futureTodayRainfallData

        // Repeat for precipitation data.
        val hourlyPrecipitationData = todayShortTermData.filter {
            it.fcstDate == today.toInt() && it.hasPrecipitationTag()
        }
        val futureTodayPrecipitationData: List<ForecastResponse.Item> = todayFutureData.filter {
            (it.fcstTime > (primaryCoveredHourMax ?: 0)) && it.hasPrecipitationTag()
        }
        val precipitationData: List<ForecastResponse.Item> = hourlyPrecipitationData + futureTodayPrecipitationData

        // Collect raining/snowing hours.
        val rainingHours = arrayListOf<Int>()
        val snowingHours = arrayListOf<Int>()
        for (i in rainfallData) {
            if (DEBUG_FLAG) Log.d(RAIN_TAG, "$i")
            val status = i.fcstValue.toInt()  // Must be integers of 0 through 7
            if (status == RainfallType.Mixed.code ||
                status == RainfallType.Shower.code ||
                status == RainfallType.Raining.code
            ) {
                rainingHours.add(i.fcstTime)
                if (DEBUG_FLAG) Log.d(RAIN_TAG, "Raining at ${i.fcstTime}")
            } else if (status == RainfallType.Snowing.code) {
                snowingHours.add(i.fcstTime)
                if (DEBUG_FLAG) Log.d(RAIN_TAG, "Snowing at ${i.fcstTime}")
            }
        }

        // Then remove negligible rain/snow data.
        for (i in precipitationData) {
            /*if (DEBUG_FLAG) Log.d(RAIN_TAG, "$i")
            if (i.isRainNegligible() && (rainingHours.contains(i.fcstTime))) {
                 rainingHours.remove(i.fcstTime)
                if (DEBUG_FLAG) Log.d(RAIN_TAG, "Not actually raining at ${i.fcstTime}")
            }*/

            if (i.isSnowNegligible() && (snowingHours.contains(i.fcstTime))) {
                 snowingHours.remove(i.fcstTime)
                if (DEBUG_FLAG) Log.d(RAIN_TAG, "Not actually snowing at ${i.fcstTime}")
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

    private fun ForecastResponse.Item.hasPrecipitationTag(): Boolean {
        return this.category == PRECIPITATION_TAG || this.category == HOURLY_PRECIPITATION_TAG
    }

    private fun ForecastResponse.Item.isSnowNegligible(): Boolean {
        if (this.category == PRECIPITATION_TAG || this.category == HOURLY_PRECIPITATION_TAG) {
            if (this.fcstValue.endsWith("mm")) return false
        }
        return true
    }

    private fun buildDailyTemps() {
        val cal = referenceCal

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
        cal.add(Calendar.DAY_OF_YEAR, -1)  // From -1
        for (i in highestTemps.indices) {
            cal.add(Calendar.DAY_OF_YEAR, 1)  // -1 + 1 = 0, then 1, ...
            val isToday = i == 0
            val day = cal.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT_FORMAT, locale)

            // The highest temperature
            val highestTemp = highestTemps[i]
            val highest = if (highestTemp != null) {
                "${highestTemp}\u00B0"
            } else {
                stringForNull
            }
            val isHighestButCold = (highestTemp != null) && (highestTemp < 0)

            // The lowest temperature
            val lowestTemp = lowestTemps[i]
            val lowest = if (lowestTemp != null) {
                "${lowestTemps[i].toString()}\u00B0"
            } else {
                stringForNull
            }
            val isLowestButHot = (lowestTemp != null) && (lowestTemp >= 25)

            dailyTempsBuilder.add(DailyTemperature(isToday, day ?: "", highest, lowest, isHighestButCold, isLowestButHot))
        }
        _dailyTemps.value = dailyTempsBuilder.toList()
    }

    private fun refreshCharacteristicTemp(
        cal: Calendar,
        day: DayOfInterest,
        candidates: List<ForecastResponse.Item>,
    ) {
        val calValue = cal.clone() as Calendar
        calValue.add(Calendar.DAY_OF_YEAR, day.dayOffset)
        val index = day.dayOffset
        var isPrimed = false

        // Using minOf(...) or maxOf(...) requires iterate each time, which is inefficient.
        candidates.forEach { item ->
            if (item.fcstDate == formatToKmaDate(calValue).toInt() &&
                item.category == VILLAGE_TEMPERATURE_TAG
            ) {
                val temperature = item.fcstValue.toFloat().roundToInt()  // TMN, TMX values are Floats.

                if (!isPrimed) {
                    lowestTemps[index] = temperature
                    highestTemps[index] = temperature
                    isPrimed = true
                    if (DEBUG_FLAG) Log.d(TAG, "Characteristic temp ${day}:${temperature}")
                } else {
                    // fcstValues of TMX, TMN are Float, so round to Integer.
                    compareAndUpdateDailyTemp(day, CharacteristicTempType.Highest, temperature)
                    if (_isDaybreakMode.value) {
                        // Exclude the temperatures for afternoon or evening
                        // (Rarely, evening temperature can be lower than the daybreak temperature).
                        if (item.fcstTime in 400..900) {
                            compareAndUpdateDailyTemp(day, CharacteristicTempType.Lowest, temperature)
                            if (DEBUG_FLAG) Log.d(TAG, "Daybreak lowest candidate of ${day}:${temperature}")
                        }
                    } else compareAndUpdateDailyTemp(day, CharacteristicTempType.Lowest, temperature)
                }
            }
        }
    }

    private fun compareAndUpdateDailyTemp(
        day: DayOfInterest,
        type: CharacteristicTempType,
        newValue: Int,
    ) {
        val index = day.dayOffset
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

    @Suppress("SameParameterValue")
    private fun refreshHourlyTemp(
        cal: Calendar,
        hourOffset: Int,
        todayData: List<ForecastResponse.Item>,
        yesterdayTempData: List<ForecastResponse.Item>,
    ) {
        val calValue = cal.clone() as Calendar
        calValue.add(Calendar.HOUR_OF_DAY, hourOffset)
        val targetHour = formatToKmaHour(calValue).toInt()
        val tolerance = 100  // In case the value for the exact hour is not available

        var todayTemp: Int? = null
        var yesterdayTemp: Int? = null

        for (i in todayData) {
            if (targetHour in (i.fcstTime - tolerance)..(i.fcstTime + tolerance) &&
                i.category == VILLAGE_TEMPERATURE_TAG
            ) {
                todayTemp = i.fcstValue.toInt()
                if (DEBUG_FLAG) Log.d(TAG, "Temperature of today(${i.fcstDate}) at ${i.fcstTime}: $todayTemp?")
                if (i.fcstTime == targetHour) {
                    if (DEBUG_FLAG) Log.d(TAG, "Right!")
                    break
                }
            }
        }

        for (i in yesterdayTempData) {
            if (targetHour in (i.fcstTime - tolerance)..(i.fcstTime + tolerance) &&
                i.category == VILLAGE_TEMPERATURE_TAG
            ) {
                yesterdayTemp = i.fcstValue.toInt()
                if (DEBUG_FLAG) Log.d(TAG, "Temperature of yesterday(${i.fcstDate}) at ${i.fcstTime}: $yesterdayTemp?")
                if (i.fcstTime == targetHour) {
                    if (DEBUG_FLAG) Log.d(TAG, "Right!")
                    break
                }
            }
        }

        todayTemp?.let { tt ->
            _hourlyTempToday.value = tt
            yesterdayTemp?.let { yt ->
                _hourlyTempDiff.value = tt - yt

                // Define widgets to receive the result.
                val intents = listOf(
                    Intent(context, GrayTemperatureWidgetReceiver::class.java),
                    Intent(context, DayTemperatureWidgetReceiver::class.java),
                    Intent(context, NightTemperatureWidgetReceiver::class.java),
                )

                for (intent in intents) {
                    intent.also {
                        it.action = ACTION_DATA_FETCHED
                        it.putExtra(EXTRA_TEMP_DIFF, tt - yt)
                        it.putExtra(EXTRA_HOURLY_TEMP, tt)
                        it.putExtra(EXTRA_DATA_VALID, true)
                    }
                    // Sync numbers shown in Widgets.
                    context.sendBroadcast(intent)
                }
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
                    if (DEBUG_FLAG) Log.w(TAG, "Today's T_H overridden by $SHORT_TERM_TEMPERATURE_TAG: $ht -> $tt")
                }
            }
            lowestTemps[index]?.let { lt ->
                if (tt < lt) {
                    lowestTemps[index] = tt
                    if (DEBUG_FLAG) Log.w(TAG, "Today's T_L overridden by $SHORT_TERM_TEMPERATURE_TAG: $lt -> $tt")
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
            false, stringForNull, stringForNull, stringForNull,
            isHighestButCold = false,
            isLowestButHot = false
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
        _isSimplified.value = enabled
    }

    fun updateDaybreakEnabled(enabled: Boolean) {
        _isDaybreakMode.value = enabled
    }

    fun updatePresetRegionEnabled(enabled: Boolean) {
        _forecastRegionCandidates.value = defaultRegionCandidates.toList()
        _isPresetRegion.value = enabled
    }

    /**
     * Update UI with regarding to language preferences.
     * [isExplicit] is true when the user changed the preference from Settings.
     * */
    fun updateLanguage(selectedCode: Int, isExplicit: Boolean) {
        if (languageCode != selectedCode) {
            languageCode = selectedCode
            buildDailyTemps()  // Need to rebuild because Mon, Tue, ... is dependant on the language.
            if (isExplicit) isLanguageChanged = true
        }
    }

    fun updateDarkMode(selectedCode: Int, systemConfig: Int) {
        darkModeCode = selectedCode

        _isDarkTheme.value = when (selectedCode) {
            1 -> false
            2 -> true
            else -> systemConfig and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
    }

    /**
     * Update [isForecastRegionAuto] only, without requesting data.
     * [isExplicit] is true when the user changed the preference from Settings.
     * * */
    fun updateAutoRegionEnabled(enabled: Boolean, isExplicit: Boolean = true) {
        isForecastRegionAuto = enabled
        _selectedForecastRegionIndex.intValue = if (enabled) 0 else 1
        if (isExplicit) {  // Need to store the value in Preferences.
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
                                _selectedForecastRegionIndex.intValue = 0
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
        if (lastConsumedSnackBar < HARDCODED_SNACK_BAR_ID) {
            // Update Preferences not to show the SnackBar on the next launch.
            viewModelScope.launch { userPrefsRepo.updateConsumedSnackBar(HARDCODED_SNACK_BAR_ID) }

            _noticeSnackBarEvent.value = SnackBarEvent(SnackBarContent(
                    R.string.snack_bar_notice,
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
        _selectedForecastRegionIndex.intValue = if (isForecastRegionAuto) 0 else 1
    }

    fun dismissRegionDialog() {
        _toShowSearchRegionDialog.value = false
    }

    fun updateSelectedForecastRegionIndex(index: Int) {
        _selectedForecastRegionIndex.intValue = index
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

        fun isValidReferring(cal: Calendar) = !isNewDataReleasedAfter(tst, cal) && contents.isNotEmpty()

        fun updateContents(contents: List<ForecastResponse.Item>, cal: Calendar?) {
            tst.lastSuccessfulTime = if (contents.isEmpty()) null else cal
            this.contents = contents
        }

        fun invalidate() = updateContents(emptyList(), null)
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

/**
 * [hourOffset] is the difference of hour, rounding off minutes.
 * i.e. [hourOffset] of 0 means 3:00 ~ 3:59 -> 4:00.
 * */
fun CoroutineScope.getHourlyTempAsync(
    xy: CoordinatesXy,
    cal: Calendar,
    dayOffset: Int,
    hourOffset: Int,
    tag: String,
) = async(Dispatchers.IO) {
    val villageBaseTime = getKmaBaseTime(cal = cal, dayOffset = dayOffset, roundOff = Village)
    if (DEBUG_FLAG) Log.d(tag, "baseTime: ${villageBaseTime.toTimeString()}")

    val hourDiff = cal.hour() - villageBaseTime.hour.toInt() / 100
    val correctedHourDiff =
        if (hourDiff < 0) hourDiff + 24 else hourDiff  // At 1:00 AM, the latestVillageHour is 23.

    // At 4:00, the latest data start from fcstTime of 3:00.
    // the data for the target hour(5 AM) are on the 3rd page, and so on...
//    val pageNo = correctedHourDiff + hourOffset + 1
    val pageNo = correctedHourDiff + hourOffset
    WeatherApi.service.getVillageWeather(
        baseDate = villageBaseTime.date,
        baseTime = villageBaseTime.hour,
        numOfRows = VILLAGE_ROWS_PER_HOUR,
        pageNo = pageNo,
        nx = xy.nx,
        ny = xy.ny,
    )
}

fun CoroutineScope.getOneDayTempAsync(
    cal: Calendar,
    dayOffset: Int,
    xy: CoordinatesXy,
) = async(Dispatchers.IO) {
    val calValue = cal.clone() as Calendar

    calValue.set(Calendar.HOUR_OF_DAY, 23)
    calValue.add(Calendar.DAY_OF_YEAR, -1)
    val latestVillageBaseTime = getKmaBaseTime(cal = calValue, roundOff = Village)
    WeatherApi.service.getVillageWeather(
        baseDate = latestVillageBaseTime.date,  // The day before
        baseTime = latestVillageBaseTime.hour,  // 23:00
        numOfRows = VILLAGE_ROWS_PER_DAY,
        pageNo = dayOffset + 1,
        nx = xy.nx,
        ny = xy.ny,
    )
}

private fun isNewDataReleasedAfter(tst: WeatherViewModel.WeatherDataTimeStamp, cal: Calendar): Boolean {
    return if (tst.condition) {
        checkBaseTime(tst, cal)
    } else {
        if (DEBUG_FLAG) Log.d(DATA_TAG, "(${tst.tag})\tCondition not met.")
        false
    }
}

/**
 * Returns true if new data are available for [cal] after [tst].
 * */
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
            if (cal.hourSince1970() != lastCheckedCal.hourSince1970()) {  // Hour changed.
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
    data object Undetermined: Sky()
    data object Good : Sky()
    sealed class Bad(val startingHour: Int, val endingHour: Int): Sky() {
        class Mixed(startingHour: Int, endingHour: Int): Bad(startingHour, endingHour)
        class Rainy(startingHour: Int, endingHour: Int): Bad(startingHour, endingHour)
        class Snowy(startingHour: Int, endingHour: Int): Bad(startingHour, endingHour)
    }
}

class DailyTemperature(
    val isToday: Boolean,
    val day: String,
    val highest: String,
    val lowest: String,
    val isHighestButCold: Boolean,
    val isLowestButHot: Boolean,
)

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