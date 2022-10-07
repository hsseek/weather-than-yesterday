package com.hsseek.betterthanyesterday.viewmodel

import android.Manifest
import com.google.android.gms.location.*
import android.app.Application
import android.content.pm.PackageManager
import android.os.Looper
import android.util.Log
import com.hsseek.betterthanyesterday.R
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.ActivityCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hsseek.betterthanyesterday.data.UserPreferencesRepository
import com.hsseek.betterthanyesterday.location.*
import com.hsseek.betterthanyesterday.network.ForecastResponse
import com.hsseek.betterthanyesterday.util.OneShotEvent
import com.hsseek.betterthanyesterday.network.WeatherApi
import com.hsseek.betterthanyesterday.util.*
import com.hsseek.betterthanyesterday.util.KmaHourRoundOff.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*
import kotlin.math.roundToInt

private const val TAG = "WeatherViewModel"
private const val LOCATION_TAG = "Location"
private const val KMA_RAW_ITEM_TAG = "KmaItems"
private const val TEMPERATURE_TAG = "TMP"
private const val LOW_TEMPERATURE_TAG = "TMN"
private const val HIGH_TEMPERATURE_TAG = "TMX"
private const val HOURLY_TEMPERATURE_TAG = "T1H"
private const val RAIN_TAG = "PTY"
private const val PLACEHOLDER = "-"

class WeatherViewModel(
    application: Application,
    private val userPreferencesRepository: UserPreferencesRepository,
) : AndroidViewModel(application) {
    private val context = application

    private val retrofitDispatcher = Dispatchers.IO
    private val defaultDispatcher = Dispatchers.Default
    private var kmaJob: Job? = null

    private val _isDataUpToDate = mutableStateOf(false)
    val isDataUpToDate: Boolean
        get() = _isDataUpToDate.value

    private var lastHourBaseTime: KmaTime = getKmaBaseTime(roundOff = HOUR)
    private var lastVillageBaseTime: KmaTime = getKmaBaseTime(roundOff = VILLAGE)
    private var lastNoonBaseTime: KmaTime = getKmaBaseTime(roundOff = NOON)

    private var baseCoordinatesXy = CoordinatesXy(60, 127)

    // Variables regarding location.
    private val locationClient = LocationServices.getFusedLocationProviderClient(context)
    var toShowLocatingDialog = mutableStateOf(false)
        private set
    // The forecast location is an input from UI.
    var forecastLocation = ForecastLocation.Auto
        private set

    private val _baseCityName = mutableStateOf(PLACEHOLDER)
    val cityName: String
        get() = _baseCityName.value

    private val _districtName = mutableStateOf(PLACEHOLDER)
    val districtName: String
        get() = _districtName.value

    // The lowest temperatures of yesterday through the day after tomorrow
    private val _lowestTemps = mutableStateOf(arrayOfNulls<Int>(4))
    val lowestTemps: List<Int?>
        get() = _lowestTemps.value.toList()

    // The highest temperatures of yesterday through the day after tomorrow
    private val _highestTemps = mutableStateOf(arrayOfNulls<Int>(4))
    val highestTemps: List<Int?>
        get() = _highestTemps.value.toList()

    // The hourly temperature
    private val _hourlyTempDiff = mutableStateOf(0)
    val hourlyTempDiff: Int
        get() = _hourlyTempDiff.value

    var hourlyTempToday: Float? = null
        private set
    private var hourlyTempYesterday: Float? = null

    private val _rainfallStatus: MutableStateFlow<Sky?> = MutableStateFlow(null)
    val rainfallStatus = _rainfallStatus.asStateFlow()

    private val _toastMessage = MutableStateFlow(OneShotEvent(0))
    val toastMessage = _toastMessage.asStateFlow()

    /**
     * Process the selection of ForecastLocation from the dialog.
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
    fun updateForecastLocation(selectedForecastLocation: ForecastLocation, isSelectionValid: Boolean = true) {
        if (selectedForecastLocation != forecastLocation) {  // Changed
            /* auto?	permitted?	CHANGED?
            V			V			V			update
            V			-			V			request permission (INVALID SELECTION)
            -			V			V			update
            -			-			V			update
            * */
            Log.d(LOCATION_TAG, "ForecastLocation changed to ${selectedForecastLocation.name}")
            forecastLocation = selectedForecastLocation
            storeForecastLocation(selectedForecastLocation)

            if (isSelectionValid) {
                if (selectedForecastLocation == ForecastLocation.Auto) {
                    startLocationUpdate()
                } else {  // ForecastLocations for fixed locations
                    updateFixedLocation(selectedForecastLocation)
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
     * Update location information determined by fixed ForecastLocations,
     * and triggers retrieving weather data eventually.
     * */
    fun updateFixedLocation(lm: ForecastLocation) {
        val cityName = context.getString(lm.regionId)
        _districtName.value = context.getString(R.string.location_manually)
        updateLocationAndWeather(lm.coordinates!!, cityName)
    }

    fun requestIfNewAvailable(time: Calendar = getCurrentKoreanDateTime()) {
        val currentHourBaseTime = getKmaBaseTime(time = time, roundOff = HOUR)
        val currentVillageBaseTime = getKmaBaseTime(time = time, roundOff = VILLAGE)
        val currentNoonBaseTime = getKmaBaseTime(time = time, roundOff = NOON)

        if (currentNoonBaseTime.isLaterThan(lastNoonBaseTime)) {
            Log.d(TAG, "New data available:\nT_H,L\t\t[V]\nD0 PTY\t\t[V]\nD-1 T1H\t[V]")
            lastHourBaseTime = currentHourBaseTime
            lastVillageBaseTime = currentVillageBaseTime
            lastNoonBaseTime = currentNoonBaseTime
            requestAllWeatherData()
        } else if (currentVillageBaseTime.isLaterThan(lastVillageBaseTime)) {
            Log.d(TAG, "New data available:\nT_H,L\t\t[-]\nD0 PTY\t\t[V]\nD-1 T1H\t[V]")
            lastHourBaseTime = currentHourBaseTime
            lastVillageBaseTime = currentVillageBaseTime
            requestAllWeatherData(isHourly = true)
            // requestVillageData() not needed as the function would be included in requestHourlyData()
        } else if (currentHourBaseTime.isLaterThan(lastHourBaseTime)) {
            Log.d(TAG, "New data available:\nT_H,L\t\t[-]\nD0 PTY\t\t[-]\nD-1 T1H\t[V]")
            lastHourBaseTime = currentHourBaseTime
            requestAllWeatherData(isHourly = true)
        } else {
            Log.d(TAG, "No new data available:\nT_H,L\t\t[-]\nD0 PTY\t\t[-]\nD-1 T1H\t[-]")
        }
    }

    private fun requestAllWeatherData(isHourly: Boolean = false) {
        viewModelScope.launch {
            logCoroutineContext(
                "viewModelScope.launch(default) { ... }"
            )
            kmaJob?.cancel()
            kmaJob?.join()

            kmaJob = launch(defaultDispatcher) {
                logCoroutineContext(
                    "viewModelScope.launch(default) {\n" +
                            "\tlaunch{...}}"
                )
                if (!isHourly) launch { requestCharacteristicTemp() }
                launch { requestWeatherToday() }
                launch { requestTempYesterday() }
            }
            kmaJob?.join()

            _isDataUpToDate.value = true
            // Job done. Update variables dependent to another requests.
            updateHourlyTempDiff()
            adjustCharTemp()
        }
    }

    /**
     * Request characteristic temperatures(e.g. the highest/lowest temperatures of the day) of all DayOfInterest.
     * All the jobs are parallel.
     * */
    private suspend fun requestCharacteristicTemp() {
        coroutineScope {
            enumValues<CharacteristicTempType>().forEach { type ->
                enumValues<DayOfInterest>().forEach { date ->
                    launch {
                        requestDailyCharacteristicTemp(date, type)
                    }
                }
            }
        }
    }

    private suspend fun requestDailyCharacteristicTemp(
        date: DayOfInterest = DayOfInterest.Today,
        type: CharacteristicTempType,
    ) {
        val baseTime = getBaseTime(date.dayOffset)
        val page = getPage(date.dayOffset, type, baseTime)
        val items: List<ForecastResponse.Item>
        val characteristicTemp: Int?
        Log.d(TAG, "D${date.dayOffset}\t\t${type.descriptor} baseTime\t: ${baseTime.date}-${baseTime.hour}")

        try {
            coroutineScope {
                val fetchingStartTime = System.currentTimeMillis() // for test
                val kmaResponse = async(retrofitDispatcher) {
                    logCoroutineContext(
                        "${type.descriptor} of D${date.dayOffset}\n" +
                                "viewModelScope.launch(default){\n" +
                                "\trequestCharTemp{ launch{\n" +
                                "\t\trequestDailyTemp{ { async(IO){\n" +
                                "\t\t\t... }}}}}"
                    )
                    return@async WeatherApi.service.getVillageWeather(
                        baseDate = baseTime.date,
                        baseTime = baseTime.hour,
                        numOfRows = 48,  // = (4 h) * (12 rows / h)
                        pageNo = page,
                        nx = baseCoordinatesXy.nx,
                        ny = baseCoordinatesXy.ny,
                    )
                }.await()
                kmaResponse.body()?.let {
                    items = it.response.body.items.item
                    logElapsedTime(TAG, "${items.size} items", fetchingStartTime)
                    characteristicTemp = extractCharacteristicTemp(items, type)

                    characteristicTemp?.let { temp ->
                        when (type) {
                            CharacteristicTempType.Lowest -> _lowestTemps.value[date.dayOffset + 1] = temp
                            CharacteristicTempType.Highest -> _highestTemps.value[date.dayOffset + 1] = temp
                        }
                    }
                    Log.d(TAG, "${type.descriptor}\tof D${date.dayOffset}\t: $characteristicTemp")
                } ?: kotlin.run { Log.e(TAG, "Null response") }
            }

        } catch (e: CancellationException) {
            Log.i(TAG, "Retrieving ${type.descriptor} of D${date.dayOffset} job cancelled.")
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving ${type.descriptor} of D${date.dayOffset}", e)
        }
    }

    /**
     * Returns the baseTime to query.
     * */
    private fun getBaseTime(dayOffset: Int): KmaTime {
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
     * */
    private fun getPage(
        dayOffset: Int = DayOfInterest.Today.dayOffset,
        type: CharacteristicTempType,
        baseTime: KmaTime
    ): Int {
        val page: Int
        if (dayOffset <= 0) {
            page = when (type) {
                CharacteristicTempType.Lowest -> 2  // Data of 04:00, 05:00, 06:00 and 07:00
                CharacteristicTempType.Highest -> 4  // Data of 12:00, 13:00, 14:00 and 15:00
            }
        } else {  // Tomorrow or the day after tomorrow
            page = if (baseTime.hour.toInt() == 2300) {
                // Note that today's 00:00 ~ 03:00 on the first page
                when (type) {
                    CharacteristicTempType.Lowest -> 2 + dayOffset * 6
                    CharacteristicTempType.Highest -> 4 + dayOffset * 6
                }
            } else {  // Today's 12:00 ~ 15:00 on the first page
                when (type) {
                    CharacteristicTempType.Lowest -> -1 + dayOffset * 6
                    CharacteristicTempType.Highest -> 1 + dayOffset * 6
                }
            }
        }
        return page
    }

    /**
     * Returns the highest and the lowest temperature from items of a KmaResponse.
     */
    private fun extractCharacteristicTemp(
        items: List<ForecastResponse.Item>,
        type: CharacteristicTempType
    ): Int? {
        var characteristicTemp: Int? = null
        val tag = when (type) {
            CharacteristicTempType.Lowest -> LOW_TEMPERATURE_TAG
            CharacteristicTempType.Highest -> HIGH_TEMPERATURE_TAG
        }
        for (i in items) {
            Log.d(KMA_RAW_ITEM_TAG, "$i")
            // Often, values larger / smaller than the TMX / TMN are recorded.
            if (i.category == TEMPERATURE_TAG || i.category == tag) {
                val temp = i.fcstValue.toFloat().roundToInt()  // The temperature at the time
                characteristicTemp?.let { lt ->
                    when (type) {
                        CharacteristicTempType.Lowest -> if (temp < lt) characteristicTemp = temp
                        CharacteristicTempType.Highest -> if (temp > lt) characteristicTemp = temp
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
    private suspend fun requestWeatherToday() {
        try {
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
            Log.d(TAG, "D0 long-term baseTime\t:${longTermBaseTime.date}-${longTermBaseTime.hour}")

            val ltFetchingStartTime = System.currentTimeMillis() // for test
            coroutineScope {
                val longTermResponse = async(retrofitDispatcher) {
                return@async WeatherApi.service.getVillageWeather(
                    baseDate = longTermBaseTime.date,
                    baseTime = longTermBaseTime.hour,
                    numOfRows = numOfRows,
                    pageNo = 1,
                    nx = baseCoordinatesXy.nx,
                    ny = baseCoordinatesXy.ny,
                )
                }

                val shortTermItems = requestShortTermData(DayOfInterest.Today)
                if (shortTermItems != null) {
                    updateShortTermTemp(shortTermItems, DayOfInterest.Today)
                }

                longTermResponse.await().body()?.let {
                    val longTermItems = it.response.body.items.item
                    logElapsedTime(TAG, "${longTermItems.size} items", ltFetchingStartTime)

                    updateRainfall(shortTermItems, longTermItems)
                }
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "Retrieving D0 forecast job cancelled.")
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving D0 forecast.", e)
        }
    }

    private suspend fun requestShortTermData(date: DayOfInterest): List<ForecastResponse.Item>? =
        coroutineScope {
            async {
                try {
                    val baseTime = getKmaBaseTime(
                        dayOffset = date.dayOffset,
                        roundOff = HOUR,
                        isQuickPublish = false,
                    )
                    Log.d(TAG, "D${date.dayOffset} short-term T baseTime\t:${baseTime.date}-${baseTime.hour}")
                    // Retrieve the short-term data
                    val fetchingStartTime = System.currentTimeMillis() // for test

                    val kmaResponse = withContext(retrofitDispatcher) {
                        WeatherApi.service.getShortTermWeather(
                            baseDate = baseTime.date,
                            baseTime = baseTime.hour,
                            numOfRows = 30,  // [LGT -> PTY -> RN1 -> SKY -> TH1] -> REH -> UUU -> VVV -> ...
                            pageNo = 1,
                            nx = baseCoordinatesXy.nx,
                            ny = baseCoordinatesXy.ny,
                        )
                    }
                    kmaResponse.body()?.let {
                        val items = it.response.body.items.item
                        logElapsedTime(TAG, "${items.size} items", fetchingStartTime)
                        for (i in items) {
                            Log.d(KMA_RAW_ITEM_TAG, "$i")
                        }
                        return@async items
                    }
                }  catch (e: CancellationException) {
                    Log.i(TAG, "Retrieving D${date.dayOffset} short-term forecast job cancelled.")
                    return@async null
                } catch (e: Exception) {
                    Log.e(TAG, "Error retrieving D${date.dayOffset} short-term forecast.", e)
                    return@async null
                }
            }
        }.await()

    /**
     * Update the short-term temperatures of [date],
     * based on data from [items].
     * Always called from a ViewModelScope.
     */
    private fun updateShortTermTemp(
        items: List<ForecastResponse.Item>,
        date: DayOfInterest,
    ) {
        try {
            for (i in items) {
                if (i.category == HOURLY_TEMPERATURE_TAG) {
                    val tem = i.fcstValue  // The first item with TH1 category
                    Log.d(TAG, "T1H\tof D${date.dayOffset}\t: $tem")

                    if (date == DayOfInterest.Today) {
                        hourlyTempToday = tem.toFloat()
                    } else if (date == DayOfInterest.Yesterday) {
                        hourlyTempYesterday = tem.toFloat()
                    }
                    break
                }
            }
        }catch (e: CancellationException) {
            Log.i(TAG, "Retrieving T1H of D${date.dayOffset} job cancelled.")
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving T1H of D${date.dayOffset}.", e)
        }
    }

    private fun updateHourlyTempDiff() {
        hourlyTempYesterday?.let { y ->
            hourlyTempToday?.let { t ->
                _hourlyTempDiff.value = (t - y).roundToInt()
            }
        }
    }

    /**
     * Often, TMX / TMN values are lower / higher than hourly value.
     * If the shown hourly value is higher than TMX than the user might doubt the reliability: adjust.
     */
    private fun adjustCharTemp() {
        hourlyTempToday?.let { tt ->
            val t = tt.roundToInt()
            _highestTemps.value[1]?.let {
                if (t > it) _highestTemps.value[1] = t
            }

            _lowestTemps.value[1]?.let {
                if (t < it) _lowestTemps.value[1] = t
            }
        }

        hourlyTempYesterday?.let { ty ->
            val t = ty.roundToInt()
            _highestTemps.value[0]?.let {
                if (t > it) _highestTemps.value[0] = t
            }
            _lowestTemps.value[0]?.let {
                if (t < it) _lowestTemps.value[0] = t
            }
        }
    }

    /**
     * Update the rainfall status of today,
     * based on [primaryItems] and [secondaryItems].
     * if there are data from the same base time, [primaryItems] take the priority.
     * Always called from a ViewModelScope.
     * */
    private fun updateRainfall(
        primaryItems: List<ForecastResponse.Item>?,
        secondaryItems: List<ForecastResponse.Item>
    ) {
        val rainingHours = arrayListOf<Int>()
        val snowingHours = arrayListOf<Int>()
        val rainfallData: List<ForecastResponse.Item>
        try {
            rainfallData = if (primaryItems != null) {
                val today = primaryItems[0].fcstDate
                val primaryRainfallData = primaryItems.filter {
                    (it.category == RAIN_TAG) and (it.fcstDate == today)
                }

                // Data from primary items are the source of truth. Discard data of secondary items for hours before.
                val primaryCoveredHourMax = primaryRainfallData.maxOf { it.fcstTime }
                val secondaryRainfallData = secondaryItems.filter {
                    (it.category == RAIN_TAG) and (it.fcstDate == today) and (it.fcstTime > primaryCoveredHourMax)
                }
                primaryRainfallData + secondaryRainfallData
            } else {
                secondaryItems.filter { (it.category == RAIN_TAG) }
            }

            for (i in rainfallData) {
                Log.d("PTY", "$i")
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

            Log.d(TAG, "PTY: ${_rainfallStatus.value!!::class.simpleName}\t(${hours.minOrNull()} ~ ${hours.maxOrNull()})")
        } catch (e: CancellationException) {
            Log.i(TAG, "Retrieving the short-term PTY job cancelled.")
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving the short-term PTY.", e)
        }
    }

    private suspend fun requestTempYesterday() {
        try {
            val shortTermItems = requestShortTermData(DayOfInterest.Yesterday)
            if (shortTermItems != null) {
                updateShortTermTemp(shortTermItems, DayOfInterest.Yesterday)
            } else {
                Log.e(TAG, "The short-term data for D-1 null")
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "Retrieving the yesterday's T1H.")
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving the yesterday's T1H.", e)
        }
    }

    /**
     * Start location update and eventually retrieve new weather data based on the location.
     * */
    fun startLocationUpdate() {
        if (ActivityCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            locationClient.lastLocation.addOnSuccessListener {
                updateLocationAndWeather(CoordinatesLatLon(lat = it.latitude, lon = it.longitude))
            }

            // A costly process to update the current location. Might take about 10 seconds.
            locationClient.requestLocationUpdates(currentLocationRequest, currentLocationCallback, Looper.getMainLooper())
        }
    }

    /**
     * Update [_baseCityName] and [baseCoordinatesXy], then request new weather data based on [baseCoordinatesXy].
     * */
    private fun updateLocationAndWeather(coordinates: CoordinatesXy, locatedCityName: String) {
        if (locatedCityName != _baseCityName.value) {  // i.e. A new base location
            Log.d(LOCATION_TAG, "A new city\t: $locatedCityName")
            _baseCityName.value = locatedCityName

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
        }

        if (locatedCityName == null) {
            Log.e(LOCATION_TAG, "Error retrieving a city name(${coordinates.lat}, ${coordinates.lon}).")
        } else {
            val xy = convertToXy(coordinates)
            Log.d(LOCATION_TAG, "A new location: (${xy.nx}, ${xy.ny})")
            updateLocationAndWeather(xy, locatedCityName)
        }
    }

    private val currentLocationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            super.onLocationResult(locationResult)
            locationResult.lastLocation?.let {
                updateLocationAndWeather(CoordinatesLatLon(it.latitude, it.longitude))
            }
        }
    }

    companion object {
        private const val ONE_MINUTE: Long = 60000
        val currentLocationRequest: LocationRequest = LocationRequest.create().apply {
            interval = ONE_MINUTE
            fastestInterval = ONE_MINUTE / 4
        }
    }

    fun storeForecastLocation(forecastLocation: ForecastLocation) {
        viewModelScope.launch {
            userPreferencesRepository.updateForecastLocation(forecastLocation)
        }
    }
}

enum class CharacteristicTempType(val descriptor: String) {
    Highest("T_H"), Lowest("T_L")
}

enum class DayOfInterest(val dayOffset: Int) {
    Yesterday(-1), Today(0), Day2(1), Day3(2)
}

enum class RainfallType(val code: Int) {
    Raining(1), Mixed(2), Snowing(3), Shower(4),
    // None(0), LightRain(5), LightRainAndSnow(6), LightSnow(7)
}

sealed class Sky {
    object Good : Sky()
    sealed class Bad(val startingHour: Int, val endingHour: Int): Sky() {
        class Mixed(startingHour: Int, endingHour: Int): Bad(startingHour, endingHour)
        class Rainy(startingHour: Int, endingHour: Int): Bad(startingHour, endingHour)
        class Snowy(startingHour: Int, endingHour: Int): Bad(startingHour, endingHour)
    }
}

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
