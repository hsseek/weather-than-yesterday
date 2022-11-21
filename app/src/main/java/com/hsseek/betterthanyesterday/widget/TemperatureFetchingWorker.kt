package com.hsseek.betterthanyesterday.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.gson.JsonSyntaxException
import com.google.gson.stream.MalformedJsonException
import com.hsseek.betterthanyesterday.data.UserPreferencesRepository
import com.hsseek.betterthanyesterday.location.CoordinatesXy
import com.hsseek.betterthanyesterday.network.*
import com.hsseek.betterthanyesterday.util.DEBUG_FLAG
import com.hsseek.betterthanyesterday.util.VILLAGE_HOUR_INTERVAL
import com.hsseek.betterthanyesterday.util.getCurrentKoreanTime
import com.hsseek.betterthanyesterday.viewmodel.VILLAGE_TEMPERATURE_TAG
import com.hsseek.betterthanyesterday.viewmodel.getHourlyTempAsync
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.*

private const val TAG = "TemperatureFetchingWorker"

class TemperatureFetchingWorker(private val context: Context, workerParams: WorkerParameters) :
    Worker(context, workerParams) {
    private val job = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + job)

    override fun doWork(): Result {
        val xy: CoordinatesXy
        runBlocking {
            val prefsRepo = UserPreferencesRepository(context)
            val prefs = prefsRepo.preferencesFlow.first()
            xy = CoordinatesXy(prefs.forecastRegionNx, prefs.forecastRegionNy)
        }
        coroutineScope.launch {
            requestComparingTempData(
                xy = xy,
                retry = BACKGROUND_MAX_RETRY,
                timeoutMin = BACKGROUND_TIMEOUT_MIN,
                additionalTimeout = BACKGROUND_ADDITIONAL_TIMEOUT,
                timeoutMax = BACKGROUND_TIMEOUT_MAX,
            ) { todayTemp, yesterdayTemp -> onFinishJob(todayTemp, yesterdayTemp) }
        }
        return Result.success()  // Forget it. Widget will take care of the result.
    }

    private fun onFinishJob(todayTemp: Int?, yesterdayTemp: Int?) {
        val isValid = todayTemp != null && yesterdayTemp != null

        // Define widgets to receive the result.
        val intents = listOf(
            Intent(context, GrayTemperatureWidgetReceiver::class.java),
            Intent(context, DayTemperatureWidgetReceiver::class.java),
            Intent(context, NightTemperatureWidgetReceiver::class.java),
        )

        for (intent in intents) {
            intent.also {
                it.action = ACTION_DATA_FETCHED
                it.putExtra(EXTRA_DATA_VALID, isValid)

                if (isValid) {
                    it.putExtra(EXTRA_TEMP_DIFF, todayTemp!! - yesterdayTemp!!)
                    it.putExtra(EXTRA_HOURLY_TEMP, todayTemp)
                }
            }
            // Send broadcast to update a Widget.
            context.sendBroadcast(intent)
        }

        if (DEBUG_FLAG) Log.d(TAG, "Send broadcast: $yesterdayTemp -> $todayTemp(isValid: $isValid)")
    }
}

suspend fun requestComparingTempData(
    xy: CoordinatesXy,
    retry: Int,
    timeoutMin: Long,
    additionalTimeout: Long,
    timeoutMax: Long,
    onFinished: (Int?, Int?) -> Unit,
) {
    if (DEBUG_FLAG) Log.d(TAG, "requestComparingTempData(Context) called.")

    var trialCount = 0
    var todayTemp: Int? = null
    var yesterdayTemp: Int? = null
    val cal = getCurrentKoreanTime()
    var isCalModified = false

    try {
        withContext(Dispatchers.IO) {
            while (trialCount < retry) {
                try {
                    withTimeout(minOf(timeoutMin + trialCount * additionalTimeout, timeoutMax)) {
                        val todayResponse = getHourlyTempAsync(xy, cal, 0, TAG, isCalModified)
                        val yesterdayResponse = getHourlyTempAsync(xy, cal, -1, TAG, isCalModified)
                        todayTemp = todayResponse.await()
                            .body()?.response?.body?.items?.item?.first {
                                it.category == VILLAGE_TEMPERATURE_TAG
                            }?.fcstValue?.toInt()
                        yesterdayTemp = yesterdayResponse.await()
                            .body()?.response?.body?.items?.item?.first {
                                it.category == VILLAGE_TEMPERATURE_TAG
                            }?.fcstValue?.toInt()
                        if (DEBUG_FLAG) Log.d(TAG, "Temperature data: $yesterdayTemp -> $todayTemp")
                    }
                    break
                } catch (e: Exception) {
                    if (e is TimeoutCancellationException) {  // Worth retrying.
                        if (++trialCount < retry) {
                            if (DEBUG_FLAG) Log.w(TAG, "(Retrying) $e")
                            runBlocking { delay(NETWORK_PAUSE) }
                        } else {  // Maximum count of trials has been reached.
                            Log.e(TAG, "Stopped retrying after $retry times.\n$e")
                            break
                        }
                    } else if (
                        e is MalformedJsonException ||
                        e is JsonSyntaxException
                    ) {  // Worth retrying, with different baseTime
                        if (++trialCount < retry) {
                            if (DEBUG_FLAG) Log.w(TAG, "(Retrying) $e")
                            val additionalRetry = 2
                            if (trialCount < retry - additionalRetry) { // Retry twice more.
                                trialCount = retry - additionalRetry
                            }

                            runBlocking { delay(NETWORK_PAUSE) }
                            if (!isCalModified) cal.add(Calendar.HOUR_OF_DAY, -VILLAGE_HOUR_INTERVAL)
                            isCalModified = true
                        } else {  // Maximum count of trials has been reached.
                            Log.e(TAG, "Stopped retrying", e)
                            break
                        }
                    } else {  // Not worth retrying, just stop.
                        when (e) {
                            is CancellationException -> if (DEBUG_FLAG) Log.d(TAG, "Widget Job cancelled.")
                            else -> Log.e(TAG, "Cannot retrieve weather data.", e)
                        }
                        break
                    }
                }
            }
        }
    } finally {
        onFinished(todayTemp, yesterdayTemp)
    }
}