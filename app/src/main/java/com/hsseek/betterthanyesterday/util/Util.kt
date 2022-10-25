package com.hsseek.betterthanyesterday.util

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import com.hsseek.betterthanyesterday.data.ForecastRegion
import com.hsseek.betterthanyesterday.data.Language
import com.hsseek.betterthanyesterday.data.UserPreferencesRepository
import com.hsseek.betterthanyesterday.location.CoordinatesXy
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.coroutineContext

private const val TIME_ZONE = "GMT+09:00"
private const val DATE_FORMAT = "yyyyMMdd"
private const val HOUR_FORMAT = "HH00"
private const val TAG = "Util"
const val DEVELOPER_EMAIL = "better.than.yesterday.weather@gmail.com"
const val LOCATION_TAG = "Location"
const val VILLAGE_ROWS_PER_HOUR = 12
const val VILLAGE_EXTRA_ROWS = 2
const val VILLAGE_HOUR_INTERVAL = 3
const val NX_MIN = 21
const val NX_MAX = 144
const val NY_MIN = 8
const val NY_MAX = 147
val SEOUL = CoordinatesXy(60, 127)


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
    cal: Calendar,
    dayOffset: Int = 0,
    hourOffset: Int = 0,
    roundOff: KmaHourRoundOff,
): KmaTime
{
    val calValues = cal.clone() as Calendar
    if (dayOffset != 0) calValues.add(Calendar.DAY_OF_YEAR, dayOffset)
    if (hourOffset != 0) calValues.add(Calendar.HOUR_OF_DAY, hourOffset)

    val isHourAvailable: Boolean = if (roundOff == KmaHourRoundOff.Village) calValues.minute() > 10 else false

    if (!isHourAvailable) {
        // The data for the current hour are not available. Use the previous hour.
        calValues.add(Calendar.HOUR_OF_DAY, -1)
    }

    when (roundOff) {
        KmaHourRoundOff.Hour -> { }  // Nothing to do
        KmaHourRoundOff.Village -> {
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

fun getYesterdayVillageCalendar(cal: Calendar): Calendar {
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

suspend fun createConfigurationWithStoredLocale(context: Context): Configuration {
    // Get the stored Locale(the code, to be exact).
    val userPrefsRepo = UserPreferencesRepository(context)
    val prefs = userPrefsRepo.preferencesFlow.first()
    val locale = getLocaleFromCode(prefs.languageCode)

    // Get the Configuration.
    val config = Configuration(context.resources.configuration)
    // Modify the Configuration the return it.
    config.setLocale(locale)
    return config
}

fun getLocaleFromCode(code: Int): Locale {
    val isoCode: String = when (code) {
        Language.English.code -> Language.English.iso
        Language.Korean.code -> Language.Korean.iso
        else -> Locale.getDefault().language
    }
    return Locale(isoCode)
}

fun Job.status(): String = when {
    isActive -> "Active"
    isCompleted && isCancelled -> "Cancelled"
    isCancelled -> "Cancelling"
    isCompleted -> "Completed"
    else -> "New"
}

fun Int.toEmojiString(): String = String(Character.toChars(this))
fun Boolean.toEnablementString(): String = if (this) "enabled" else "disabled"
fun ForecastRegion.toRegionString(): String = "${this.address} (${this.xy.nx}, ${this.xy.ny})"
fun KmaTime.toTimeString(): String = "${this.date}-${this.hour}"

enum class KmaHourRoundOff { Hour, Village }