package com.hsseek.betterthanyesterday.util

import android.content.Context
import android.content.res.Configuration
import android.location.Address
import android.util.Log
import com.hsseek.betterthanyesterday.R
import com.hsseek.betterthanyesterday.data.Language
import com.hsseek.betterthanyesterday.data.UserPreferencesRepository
import com.hsseek.betterthanyesterday.location.CoordinatesXy
import kotlinx.coroutines.flow.first
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.coroutineContext

private const val TIME_ZONE = "GMT+09:00"
private const val DATE_FORMAT = "yyyyMMdd"
private const val HOUR_FORMAT = "HH00"
private const val TAG = "Util"
const val LOCATION_TAG = "Location"
const val VILLAGE_ROWS_PER_HOUR = 12
const val VILLAGE_EXTRA_ROWS = 2
const val VILLAGE_HOUR_INTERVAL = 3

data class KmaTime(val date: String, val hour: String){
    fun isLaterThan(time: KmaTime): Boolean {
        try {
            if (this.date.toInt() > time.date.toInt()) { // It's a later date.
                return true
            } else if (this.date.toInt() == time.date.toInt()) { // It's the same date.
                if (this.hour.toInt() > time.hour.toInt()) {
                    return true
                }
            }
            return false
        } catch (e: Exception) {
            Log.e(TAG, "KmaTime.isLaterThan()", e)
            return false
        }
    }
}


fun Calendar.hour(): Int = this.get(Calendar.HOUR_OF_DAY)
private fun Calendar.minute(): Int = this.get(Calendar.MINUTE)

fun getCurrentKoreanDateTime(): Calendar {
    val timeZoneKorea = TimeZone.getTimeZone(TIME_ZONE)
    return Calendar.getInstance(timeZoneKorea)
}

/**
 * Returns the latest baseTime with offsets, in accordance with the [roundOff] rules.
 * [cal] is cloned because [cal] is passed by reference, which would lead to changed [Calendar.time] after the run.
 * (Therefore, use the default value unless it is intended such as for test purposes.)
 * Note that the returned value may be a future time, at which data are not available yet.
 * */
fun getKmaBaseTime(
    cal: Calendar = getCurrentKoreanDateTime(),
    dayOffset: Int = 0,
    hourOffset: Int = 0,
    roundOff: KmaHourRoundOff,
): KmaTime
{
    val calValues = cal.clone() as Calendar
    if (dayOffset != 0) calValues.add(Calendar.DAY_OF_YEAR, dayOffset)
    if (hourOffset != 0) calValues.add(Calendar.HOUR_OF_DAY, hourOffset)

    val isHourAvailable: Boolean = if (roundOff == KmaHourRoundOff.VILLAGE) calValues.minute() > 10 else false

    if (!isHourAvailable) {
        // The data for the current hour are not available. Use the previous hour.
        calValues.add(Calendar.HOUR_OF_DAY, -1)
    }

    when (roundOff) {
        KmaHourRoundOff.HOUR -> { }  // Nothing to do
        KmaHourRoundOff.VILLAGE -> {
            // Only 0200, 0500, ..., 2300 are accepted as query
            val hour = calValues.hour()
            val hourAdjustment: Int = when (hour % VILLAGE_HOUR_INTERVAL) {
                0 -> 1
                1 -> 2
                else -> 0
            }
            if (hourAdjustment > 0) {
                calValues.add(Calendar.HOUR_OF_DAY, -hourAdjustment)
            }
        }
    }

    return KmaTime(
        date = formatToKmaDate(calValues),
        hour = formatToKmaHour(calValues)
    )
}

fun formatToKmaDate(cal: Calendar = getCurrentKoreanDateTime()): String = SimpleDateFormat(DATE_FORMAT, Locale.KOREA).format(cal.time)
fun formatToKmaHour(cal: Calendar = getCurrentKoreanDateTime()): String = SimpleDateFormat(HOUR_FORMAT, Locale.KOREA).format(cal.time)

fun getYesterdayVillageCalendar(cal: Calendar = getCurrentKoreanDateTime()): Calendar {
    val calValue = cal.clone() as Calendar
    calValue.add(Calendar.DAY_OF_YEAR, -1)  // Yesterday

    val hour = calValue.hour()
    if (hour % 3 == 2) {
        calValue.add(Calendar.HOUR_OF_DAY, -VILLAGE_HOUR_INTERVAL)  // 5:?? -> 2:?? -> 2:00
    }
    return calValue
}

fun logElapsedTime(tag: String, task: String, startTime: Long) {
    val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
    val formatter = DecimalFormat("#.00")
    Log.d(tag, "$task took ${formatter.format(elapsedSec)}\"")
}

suspend fun logCoroutineContext(msg: String = "") {
    val tag = "Coroutine"
    Log.d(tag, msg + "\nThread: ${Thread.currentThread().name}" + "\nScope: $coroutineContext")
}

fun getCityName(addresses: List<Address>?): String? {
    if (addresses != null) {
        val regex = Regex("\\s(.+?[시군])\\s")
        for (address in addresses) {
            val cityFullName = regex.find(address.getAddressLine(0))?.groupValues?.get(1)
            if (cityFullName != null) {
                for (special in listOf("특별시", "광역시", "특별자치")) {
                    if (cityFullName.contains(special)) {
                        return cityFullName.replace(special, "")  // e.g. "서울특별시" -> "서울"
                    }
                }
                return cityFullName
            }
        }  // No matching pattern found.
        return addresses.first().getAddressLine(0).split(" ").first()
    }
    return null
}

fun getDistrictName(addresses: List<Address>?): String? {
    if (addresses != null) {
        val regex = Regex("\\s(\\S+?[구읍면])\\s")
        for (address in addresses) {
            val district = regex.find(address.getAddressLine(0))?.groupValues?.get(1)
            if (district != null) return district
        }  // No matching pattern found.
        val generalRegex = Regex("\\s.+?[시군]\\s(\\S+?)\\s")
        val street = generalRegex.find(addresses.first().getAddressLine(0))?.groupValues?.get(1)
        if (street != null) return street
    }
    return null
}

suspend fun createConfigurationWithStoredLocale(context: Context): Configuration {
    val userPrefsRepo = UserPreferencesRepository(context)
    val prefs = userPrefsRepo.preferencesFlow.first()
    val isoCode: String = when (prefs.languageCode) {
        Language.English.code -> Language.English.iso
        Language.Korean.code -> Language.Korean.iso
        else -> Locale.getDefault().language
    }
    val config = Configuration(context.resources.configuration)
    config.setLocale(Locale(isoCode))
    return config
}

enum class KmaHourRoundOff {
    HOUR, VILLAGE
}

enum class LocatingMethod(val code: Int, val regionId: Int, val citiesId: Int, val coordinates: CoordinatesXy?) {
    Auto(0, R.string.region_auto, R.string.cities_auto, null),
    Capital(1, R.string.region_captial, R.string.cities_captial, CoordinatesXy(60, 127)),
    Gangwon(2, R.string.region_gangwon, R.string.cities_gangwon, CoordinatesXy(73, 134)),
    SouthGs(3, R.string.region_south_gs, R.string.cities_south_gs, CoordinatesXy(98, 76)),
    NorthGs(4, R.string.region_north_gs, R.string.cities_north_gs, CoordinatesXy(89, 90)),
    SouthJl(5, R.string.region_south_jl, R.string.cities_south_jl, CoordinatesXy(58, 74)),
    NorthJl(6, R.string.region_north_jl, R.string.cities_north_jl, CoordinatesXy(63, 89)),
    Jeju(7, R.string.region_jeju, R.string.cities_jeju, CoordinatesXy(52, 38)),
    SouthCh(8, R.string.region_south_ch, R.string.cities_south_ch, CoordinatesXy(67, 100)),
    NorthCh(9, R.string.region_north_ch, R.string.cities_north_ch, CoordinatesXy(69, 107)),
    Dokdo(10, R.string.region_dokdo, R.string.cities_dokdo, CoordinatesXy(144, 123))
}