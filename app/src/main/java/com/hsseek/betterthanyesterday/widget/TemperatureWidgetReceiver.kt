package com.hsseek.betterthanyesterday.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.google.gson.JsonSyntaxException
import com.google.gson.stream.MalformedJsonException
import com.hsseek.betterthanyesterday.data.UserPreferencesRepository
import com.hsseek.betterthanyesterday.location.CoordinatesXy
import com.hsseek.betterthanyesterday.network.*
import com.hsseek.betterthanyesterday.util.*
import com.hsseek.betterthanyesterday.viewmodel.VILLAGE_TEMPERATURE_TAG
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.*

private const val TAG = "TemperatureWidgetReceiver"

class TemperatureWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = TemperatureWidget()
    private val coroutineScope = MainScope()
    private val networkDispatcher = Dispatchers.IO
    private val defaultDispatcher = Dispatchers.Default

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        if (DEBUG_FLAG) Log.d(TAG, "onUpdate() called.")
        requestData(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (DEBUG_FLAG) Log.d(TAG, "onReceive() called.")
        if (intent.action == RefreshCallback.UPDATE_ACTION) {
            requestData(context)
        }
    }

    private fun requestData(context: Context) {
        Log.d(TAG, "requestData(Context) called.")
        val xy: CoordinatesXy
        runBlocking {
            val prefsRepo = UserPreferencesRepository(context)
            val prefs = prefsRepo.preferencesFlow.first()
            xy = CoordinatesXy(prefs.forecastRegionNx, prefs.forecastRegionNy)
        }

        coroutineScope.launch {
            val glanceIdList = GlanceAppWidgetManager(context).getGlanceIds(TemperatureWidget::class.java)
            for (glanceId in glanceIdList) {  // For each Widget
                launch(defaultDispatcher) {  // A Job for each Widget
                    var trialCount = 0
                    var todayTemp: Int? = null
                    var yesterdayTemp: Int? = null
                    val cal = getCurrentKoreanTime()
                    var isCalModified = false

                    updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { pref ->
                        pref.toMutablePreferences().apply {
                            this[REFRESHING_KEY] = true
                        }
                    }
                    glanceAppWidget.update(context, glanceId)

                    withContext(defaultDispatcher) {
                        while (trialCount < WIDGET_MAX_RETRY) {
                            val hoursToShift = if (isCalModified) VILLAGE_HOUR_INTERVAL else 0
                            try {
                                withTimeout(WIDGET_TIMEOUT) {
                                    val latestVillageBaseTime = getKmaBaseTime(
                                        cal = cal,
                                        roundOff = KmaHourRoundOff.Village
                                    )
                                    val yesterdayVillageBaseTime = getKmaBaseTime(
                                        cal = cal, dayOffset = -1,
                                        roundOff = KmaHourRoundOff.Village
                                    )
                                    val pageNo = hoursToShift + cal.hour() - latestVillageBaseTime.hour.toInt() / 100 + 1

                                    val todayResponse = async(networkDispatcher) {
                                        WeatherApi.service.getVillageWeather(
                                            baseDate = latestVillageBaseTime.date,  // Yesterday(23:00 only) or today
                                            baseTime = latestVillageBaseTime.hour,
                                            numOfRows = VILLAGE_ROWS_PER_HOUR,
                                            pageNo = pageNo,
                                            nx = xy.nx,
                                            ny = xy.ny,
                                        )
                                    }
                                    val yesterdayResponse = async(networkDispatcher) {
                                        WeatherApi.service.getVillageWeather(
                                            baseDate = yesterdayVillageBaseTime.date,
                                            baseTime = yesterdayVillageBaseTime.hour,
                                            numOfRows = VILLAGE_ROWS_PER_HOUR,
                                            pageNo = pageNo,
                                            nx = xy.nx,
                                            ny = xy.ny,
                                        )
                                    }
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
                                    if (++trialCount < WIDGET_MAX_RETRY) {
                                        if (DEBUG_FLAG) Log.w(TAG, "(Retrying) $e")
                                        runBlocking { delay(NETWORK_PAUSE) }
                                    } else {  // Maximum count of trials has been reached.
                                        Log.e(TAG, "Stopped retrying after $WIDGET_MAX_RETRY times.\n$e")
                                        break
                                    }
                                } else if (
                                    e is MalformedJsonException ||
                                    e is JsonSyntaxException
                                ) {  // Worth retrying, with different baseTime
                                    if (++trialCount < WIDGET_MAX_RETRY) {
                                        if (DEBUG_FLAG) Log.w(TAG, "(Retrying) $e")
                                        val additionalRetry = 2
                                        if (trialCount < WIDGET_MAX_RETRY - additionalRetry) { // Retry twice more.
                                            trialCount = WIDGET_MAX_RETRY - additionalRetry
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

                    val tt = todayTemp
                    val yt = yesterdayTemp

                    if (tt != null && yt != null) {  // Update Widget.
                        updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { pref ->
                            val diff = tt - yt
                            if (DEBUG_FLAG) Log.d(TAG, "Temperature difference: $diff")
                            pref.toMutablePreferences().apply {
                                this[REFRESHING_KEY] = false
                                this[VALID_DATA_KEY] = true
                                this[HOURLY_TEMPERATURE_PREFS_KEY] = tt
                                this[TEMPERATURE_DIFF_PREFS_KEY] = tt - yt
                            }
                        }
                        glanceAppWidget.update(context, glanceId)
                    } else {  // Show the message for null data.
                        updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { pref ->
                            pref.toMutablePreferences().apply {
                                this[REFRESHING_KEY] = false
                                this[VALID_DATA_KEY] = false
                            }
                        }
                        glanceAppWidget.update(context, glanceId)
                    }
                }
            }
        }
    }

    companion object {
        val HOURLY_TEMPERATURE_PREFS_KEY = intPreferencesKey("hourly_temp_prefs_key")
        val TEMPERATURE_DIFF_PREFS_KEY = intPreferencesKey("temp_diff_prefs_key")
        val VALID_DATA_KEY = booleanPreferencesKey("valid_data_key")
        val REFRESHING_KEY = booleanPreferencesKey("refreshing_key")
    }
}

class RefreshCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val intent = Intent(context, TemperatureWidgetReceiver::class.java).apply {
            action = UPDATE_ACTION
        }
        context.sendBroadcast(intent)
    }

    companion object {
        const val UPDATE_ACTION = "update_action"
    }
}