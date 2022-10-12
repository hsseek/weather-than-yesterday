package com.hsseek.betterthanyesterday.util

import android.util.Log
import com.hsseek.betterthanyesterday.R
import com.hsseek.betterthanyesterday.location.CoordinatesXy
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.coroutineContext

private const val TIME_ZONE = "GMT+09:00"
private const val DATE_FORMAT = "yyyyMMdd"
private const val HOUR_FORMAT = "HH00"
private const val TAG = "Util"
const val LOCATING_METHOD_TAG = "LocatingMethod"
const val VILLAGE_ROWS_PER_HOUR = 12
const val VILLAGE_EXTRA_ROWS = 2


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
 * Note that the returned value may be a future time, at which data are not available yet.
 * */
fun getKmaBaseTime(
    cal: Calendar = getCurrentKoreanDateTime(),
    dayOffset: Int = 0,
    hourOffset: Int = 0,
    roundOff: KmaHourRoundOff,
): KmaTime
{
    if (dayOffset != 0) cal.add(Calendar.DAY_OF_YEAR, dayOffset)
    if (hourOffset != 0) cal.add(Calendar.HOUR_OF_DAY, hourOffset)

    val isHourAvailable: Boolean = if (roundOff == KmaHourRoundOff.VILLAGE) cal.minute() > 10 else false

    if (!isHourAvailable) {
        // The data for the current hour are not available. Use the previous hour.
        cal.add(Calendar.HOUR_OF_DAY, -1)
    }

    when (roundOff) {
        KmaHourRoundOff.HOUR -> { }  // Nothing to do
        KmaHourRoundOff.VILLAGE -> {
            // Only 0200, 0500, ..., 2300 are accepted as query
            val hour = cal.hour()
            val hourAdjustment: Int = when (hour % 3) {
                0 -> 1
                1 -> 2
                else -> 0
            }
            if (hourAdjustment > 0) {
                cal.add(Calendar.HOUR_OF_DAY, -hourAdjustment)
            }
        }
    }

    return KmaTime(
        date = formatToKmaDate(cal),
        hour = formatToKmaHour(cal)
    )
}

fun formatToKmaDate(cal: Calendar = getCurrentKoreanDateTime()): String = SimpleDateFormat(DATE_FORMAT, Locale.KOREA).format(cal.time)
fun formatToKmaHour(cal: Calendar = getCurrentKoreanDateTime()): String = SimpleDateFormat(HOUR_FORMAT, Locale.KOREA).format(cal.time)

fun logElapsedTime(tag: String, task: String, startTime: Long) {
    val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
    val formatter = DecimalFormat("#.00")
    Log.d(tag, "$task took ${formatter.format(elapsedSec)}\"")
}

suspend fun logCoroutineContext(msg: String = "") {
    val tag = "Coroutine"
    Log.d(tag, msg + "\nThread: ${Thread.currentThread().name}" + "\nScope: $coroutineContext")
}

fun Int.hour(): Int = this / 100

fun getCityName(address: String?): String? {
    return if (address == null) {
        null
    } else {
        val regex = Regex("\\s(.+?[시군])\\s")
        val cityFullName = regex.find(address)?.groupValues?.get(1)
        if (cityFullName == null) {
            null
        } else {
            for (special in listOf("특별시", "광역시", "특별자치")) {
                if (cityFullName.contains(special)) {
                    return cityFullName.replace(special, "")  // e.g. "서울특별시" -> "서울"
                }
            }
            cityFullName
        }
    }
}

fun getDistrictName(address: String?): String? {
    return if (address == null) {
        null
    } else {
        val regex = Regex("\\s(\\S+?[구읍면])\\s")
        return regex.find(address)?.groupValues?.get(1)
    }
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