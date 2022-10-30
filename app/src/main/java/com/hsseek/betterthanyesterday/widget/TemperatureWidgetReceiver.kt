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
import com.hsseek.betterthanyesterday.data.UserPreferencesRepository
import com.hsseek.betterthanyesterday.location.CoordinatesXy
import com.hsseek.betterthanyesterday.service.HourlyTempFetchingService
import com.hsseek.betterthanyesterday.service.HourlyTempFetchingService.Companion.EXTRA_FORECAST_REGION_NX
import com.hsseek.betterthanyesterday.service.HourlyTempFetchingService.Companion.EXTRA_FORECAST_REGION_NY
import com.hsseek.betterthanyesterday.util.*
import com.hsseek.betterthanyesterday.widget.RefreshCallback.Companion.EXTRA_DATA_VALID
import com.hsseek.betterthanyesterday.widget.RefreshCallback.Companion.EXTRA_HOURLY_TEMP
import com.hsseek.betterthanyesterday.widget.RefreshCallback.Companion.EXTRA_TEMP_DIFF
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first


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
        if (DEBUG_FLAG) Log.d(TAG, "onReceive(...) called.(action: ${intent.action})")

        // Refresh data.
        if (intent.action == RefreshCallback.REFRESH_ACTION) {
            requestData(context)
        }

        // Update Widget with the numbers from Extra
        if (intent.action == RefreshCallback.DATA_RENEWED_ACTION) {
            val tempDiff = intent.extras?.getInt(EXTRA_TEMP_DIFF)
            val hourlyTemp = intent.extras?.getInt(EXTRA_HOURLY_TEMP)
            val isValid = intent.extras?.getBoolean(EXTRA_DATA_VALID) ?: false

            coroutineScope.launch {
                val glanceIdList = GlanceAppWidgetManager(context).getGlanceIds(TemperatureWidget::class.java)
                for (glanceId in glanceIdList) {
                    updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { pref ->
                        if (DEBUG_FLAG) Log.d(TAG, "Temperature difference: $tempDiff")
                        pref.toMutablePreferences().apply {
                            this[REFRESHING_KEY] = false
                            this[VALID_DATA_KEY] = isValid
                            if (hourlyTemp != null) this[HOURLY_TEMPERATURE_PREFS_KEY] = hourlyTemp
                            if (tempDiff != null) this[TEMPERATURE_DIFF_PREFS_KEY] = tempDiff
                        }
                    }
                    glanceAppWidget.update(context, glanceId)
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
                    // Let the user know a job is ongoing.
                    updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { pref ->
                        pref.toMutablePreferences().apply {
                            this[REFRESHING_KEY] = true
                        }
                    }
                    glanceAppWidget.update(context, glanceId)

                    // Start Service to fetch data.
                    val intent = Intent(context, HourlyTempFetchingService::class.java).apply {
                        putExtra(EXTRA_FORECAST_REGION_NX, xy.nx)
                        putExtra(EXTRA_FORECAST_REGION_NY, xy.ny)
                    }
                    if (DEBUG_FLAG) Log.d(TAG, "Start Service with (${xy.nx}, ${xy.ny})")
                    context.startForegroundService(intent)
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
        const val DATA_RENEWED_ACTION = "data_renewed_action"
        const val EXTRA_HOURLY_TEMP = "extra_hourly_temp"
        const val EXTRA_TEMP_DIFF = "extra_temp_diff"
        const val EXTRA_DATA_VALID = "extra_data_valid"
    }
}