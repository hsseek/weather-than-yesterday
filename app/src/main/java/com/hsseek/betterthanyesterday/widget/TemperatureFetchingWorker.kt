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
import com.hsseek.betterthanyesterday.network.NETWORK_PAUSE
import com.hsseek.betterthanyesterday.network.BACKGROUND_MAX_RETRY
import com.hsseek.betterthanyesterday.network.BACKGROUND_TIMEOUT
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
        coroutineScope.requestComparingTempData(
            xy = xy,
            retry = BACKGROUND_MAX_RETRY,
            timeout = BACKGROUND_TIMEOUT
        ) { todayTemp, yesterdayTemp -> onFinishJob(todayTemp, yesterdayTemp) }
        return Result.success()  // Forget it. Widget will take care of the result.
    }

    private fun onFinishJob(todayTemp: Int?, yesterdayTemp: Int?) {
        val isValid = todayTemp != null && yesterdayTemp != null

        val intent = Intent(context, TemperatureWidgetReceiver::class.java).apply {
            action = RefreshCallback.ACTION_DATA_FETCHED
            putExtra(RefreshCallback.EXTRA_DATA_VALID, isValid)
        }
        if (isValid) {
            intent.putExtra(RefreshCallback.EXTRA_TEMP_DIFF, todayTemp!! - yesterdayTemp!!)
            intent.putExtra(RefreshCallback.EXTRA_HOURLY_TEMP, todayTemp)
        }

        // Send broadcast to update Widget.
        if (DEBUG_FLAG) Log.d(TAG, "Send broadcast with: yt of $yesterdayTemp, tt of $todayTemp(isValid: $isValid)")
        context.sendBroadcast(intent)
    }
}

fun CoroutineScope.requestComparingTempData(
    xy: CoordinatesXy,
    retry: Int,
    timeout: Long,
    onFinished: (Int?, Int?) -> Unit,
) {
    if (DEBUG_FLAG) Log.d(TAG, "requestData(Context) called.")

    var trialCount = 0
    var todayTemp: Int? = null
    var yesterdayTemp: Int? = null
    val cal = getCurrentKoreanTime()
    var isCalModified = false

    this.launch {
        try {
            withContext(Dispatchers.IO) {
                while (trialCount < retry) {
                    try {
                        withTimeout(timeout) {
                            val todayResponse = getHourlyTempAsync(xy, cal, 0,
                                TAG, false)
                            val yesterdayResponse = getHourlyTempAsync(xy, cal, -1,
                                TAG, false)
                            todayTemp = todayResponse.await()
                                .body()?.response?.body?.items?.item?.first {
                                    it.category == VILLAGE_TEMPERATURE_TAG
                                }?.fcstValue?.toInt()
                            yesterdayTemp = yesterdayResponse.await()
                                .body()?.response?.body?.items?.item?.first {
                                    it.category == VILLAGE_TEMPERATURE_TAG
                                }?.fcstValue?.toInt()
                            if (DEBUG_FLAG) Log.d(TAG, "Temperature: $yesterdayTemp -> $todayTemp")
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
                                is CancellationException -> if (DEBUG_FLAG) Log.d(TAG, "kmaJob cancelled.")
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
}