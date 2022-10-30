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
import com.hsseek.betterthanyesterday.viewmodel.getHourlyTempAsync
import com.hsseek.betterthanyesterday.widget.RefreshCallback.Companion.EXTRA_HOURLY_TEMP
import com.hsseek.betterthanyesterday.widget.RefreshCallback.Companion.EXTRA_TEMP_DIFF
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.*

private const val TAG = "TemperatureWidgetReceiver"

class TemperatureWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = TemperatureWidget()
    private val coroutineScope = MainScope()
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

        // Refresh data.
        if (intent.action == RefreshCallback.REFRESH_ACTION) {
            requestData(context)
        }

        // Update Widget with the numbers from Extra
        if (intent.action == RefreshCallback.UPDATE_ACTION) {
            val tempDiff = intent.extras?.getInt(EXTRA_TEMP_DIFF)
            val hourlyTemp = intent.extras?.getInt(EXTRA_HOURLY_TEMP)

            if (tempDiff != null && hourlyTemp != null) {
                coroutineScope.launch {
                    val glanceIdList = GlanceAppWidgetManager(context).getGlanceIds(TemperatureWidget::class.java)
                    for (glanceId in glanceIdList) {
                        updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { pref ->
                            if (DEBUG_FLAG) Log.d(TAG, "Temperature difference: $tempDiff")
                            pref.toMutablePreferences().apply {
                                this[REFRESHING_KEY] = false
                                this[VALID_DATA_KEY] = true
                                this[HOURLY_TEMPERATURE_PREFS_KEY] = hourlyTemp
                                this[TEMPERATURE_DIFF_PREFS_KEY] = tempDiff
                            }
                        }
                        glanceAppWidget.update(context, glanceId)
                    }
                }
            }
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
                            try {
                                withTimeout(WIDGET_TIMEOUT) {
                                    val todayResponse = getHourlyTempAsync(xy, cal, 0, TAG, false)
                                    val yesterdayResponse = getHourlyTempAsync(xy, cal, -1, TAG, false)
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
            action = REFRESH_ACTION
        }
        context.sendBroadcast(intent)
    }

    companion object {
        const val REFRESH_ACTION = "refresh_action"
        const val UPDATE_ACTION = "update_action"
        const val EXTRA_HOURLY_TEMP = "extra_hourly_temp"
        const val EXTRA_TEMP_DIFF = "extra_temp_diff"
    }
}