package com.hsseek.betterthanyesterday.util

import android.content.Context
import android.location.Location
import android.util.Log
import androidx.core.content.edit
import com.hsseek.betterthanyesterday.R
import com.hsseek.betterthanyesterday.viewmodel.DayOfInterest
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.coroutineContext


private const val TIME_ZONE = "GMT+09:00"
private const val DATE_FORMAT = "yyyyMMdd"
private const val HOUR_FORMAT = "HH00"


data class KmaTime (val date: String, val hour: String)


private fun Calendar.hour(): Int = this.get(Calendar.HOUR_OF_DAY)
private fun Calendar.minute(): Int = this.get(Calendar.MINUTE)

fun getCurrentKoreanDateTime(): Calendar {
    val timeZoneKorea = TimeZone.getTimeZone(TIME_ZONE)
    return Calendar.getInstance(timeZoneKorea)
}

/**
 * Returns the latest baseTime at [time] in accordance with the [roundOff] and [isQuickPublish] rules.
 * Note that the returned value may be a future time, at which data are not available yet.
 * */
fun getKmaBaseTime(
    dayOffset: Int = DayOfInterest.Today.dayOffset,
    time: Calendar = getCurrentKoreanDateTime(),
    roundOff: KmaHourRoundOff,
    isQuickPublish: Boolean = true,
): KmaTime
{
    if (dayOffset != 0) time.add(Calendar.DAY_OF_YEAR, dayOffset)
    val isHourAvailable: Boolean = if (isQuickPublish) time.minute() > 10 else time.minute() > 45

    if (!isHourAvailable) {
        // The data for the current hour are not available. Use the previous hour.
        time.add(Calendar.HOUR_OF_DAY, -1)
        if (time.hour() < 1) { // i.e. 00:mm
            time.add(Calendar.DAY_OF_YEAR, -1)
        }
    }

    when (roundOff) {
        KmaHourRoundOff.HOUR -> { }  // Nothing to do
        KmaHourRoundOff.VILLAGE -> {
            // Only 0200, 0500, ..., 2300 are accepted as query
            val hour = time.hour()
            val hourAdjustment: Int = when (hour % 3) {
                0 -> 1
                1 -> 2
                else -> 0
            }
            if (hourAdjustment > 0) {
                time.add(Calendar.HOUR_OF_DAY, -hourAdjustment)
                if (time.hour() < hourAdjustment) {  // e.g. 01:00 -> 23:00
                    time.add(Calendar.DAY_OF_YEAR, -1)  // Adjust to the previous day
                }
            }
        }
        KmaHourRoundOff.NOON -> {  // Round off to 11:00 or 23:00
            if (time.hour() != 11 && time.hour() != 23) {
                if (time.hour() < 11) {  // Data of the noon not available yet
                    time.add(Calendar.DAY_OF_YEAR, -1)
                    time.set(Calendar.HOUR_OF_DAY, 23)
                } else if (time.hour() != 11) {
                    // Data of the noon not available. Utilize theme.
                    time.set(Calendar.HOUR_OF_DAY, 11)
                }
            }
        }
        KmaHourRoundOff.DAY -> {
            time.add(Calendar.DAY_OF_YEAR, -1)
            time.set(Calendar.HOUR_OF_DAY, 23)
        }
    }

    return KmaTime(
        date = SimpleDateFormat(DATE_FORMAT, Locale.KOREA).format(time.time),
        hour = SimpleDateFormat(HOUR_FORMAT, Locale.KOREA).format(time.time)
    )
}

fun logElapsedTime(tag: String, task: String, startTime: Long) {
    val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
    val formatter = DecimalFormat("#.00")
    Log.d(tag, "$task took ${formatter.format(elapsedSec)}\"")
}

fun Location?.toText(): String {
    return if (this != null) "(${"%.4f".format(latitude)}, ${"%.4f".format(longitude)})" else { "(Unknown location)" }
}

suspend fun logCoroutineContext(msg: String = "") {
    val tag = "Coroutine"
    Log.d(tag, msg + "\nThread: ${Thread.currentThread().name}" + "\nScope: $coroutineContext")
}

internal object SharedPreferenceUtil {
    const val KEY_FOREGROUND_ENABLED = "tracking_foreground_location"

    /**
     * Returns true if requesting location updates, otherwise returns false.
     */
    fun getLocationTrackingPref(context: Context): Boolean =
        context.getSharedPreferences(
            context.getString(R.string.preference_file_key), Context.MODE_PRIVATE
        )
            .getBoolean(KEY_FOREGROUND_ENABLED, false)

    /**
     * Stores the location updates state in SharedPreferences.
     * @param requestingLocationUpdates The location updates state.
     */
    fun saveLocationTrackingPref(context: Context, requestingLocationUpdates: Boolean) =
        context.getSharedPreferences(
            context.getString(R.string.preference_file_key),
            Context.MODE_PRIVATE
        ).edit {
            putBoolean(KEY_FOREGROUND_ENABLED, requestingLocationUpdates)
        }
}

enum class KmaHourRoundOff {
    HOUR, VILLAGE, NOON, DAY
}
