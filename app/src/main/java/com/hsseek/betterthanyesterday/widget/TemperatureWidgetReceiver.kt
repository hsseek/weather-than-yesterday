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
import androidx.work.*
import com.hsseek.betterthanyesterday.util.*
import com.hsseek.betterthanyesterday.widget.RefreshCallback.Companion.EXTRA_DATA_VALID
import com.hsseek.betterthanyesterday.widget.RefreshCallback.Companion.EXTRA_HOURLY_TEMP
import com.hsseek.betterthanyesterday.widget.RefreshCallback.Companion.EXTRA_TEMP_DIFF
import kotlinx.coroutines.*
import java.util.Calendar
import java.util.concurrent.TimeUnit


private const val TAG = "TemperatureWidgetReceiver"
private const val TEMP_WORK_ID_IMMEDIATE = "TEMP_WORK_ID_IMMEDIATE"
private const val TEMP_WORK_ID_PERIODIC = "TEMP_WORK_ID_PERIODIC"

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
        when (intent.action) {
            RefreshCallback.ACTION_DATA_FETCHED -> insertData(intent, context)
            RefreshCallback.ACTION_REFRESH-> requestData(context)
            AppWidgetManager.ACTION_APPWIDGET_ENABLED -> requestData(context)
            AppWidgetManager.ACTION_APPWIDGET_DISABLED -> stopTempWorks(context)
            AppWidgetManager.ACTION_APPWIDGET_DELETED -> stopTempWorks(context)
            else -> if (DEBUG_FLAG) Log.d(TAG, "Nothing to do with the action.")
        }
    }

    private fun insertData(intent: Intent, context: Context) {
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

        val cal = Calendar.getInstance()
        val remainingSeconds = 3600L - (cal.get(Calendar.MINUTE) * 60 + cal.get(Calendar.SECOND))
        val flexSeconds = 300L
        if (DEBUG_FLAG) Log.d(TAG, "Periodic fetching starts in ${remainingSeconds / 60} min.")

        val periodicWork = PeriodicWorkRequest.Builder(
            TemperatureFetchingWorker::class.java,
            1, TimeUnit.HOURS,
            flexSeconds, TimeUnit.SECONDS,
        ).setInitialDelay(remainingSeconds + flexSeconds, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).also {
            it.enqueueUniquePeriodicWork(TEMP_WORK_ID_PERIODIC, ExistingPeriodicWorkPolicy.REPLACE, periodicWork)
        }
    }

    private fun requestData(context: Context) {
        if (DEBUG_FLAG) Log.d(TAG, "requestData(Context) called.")

        coroutineScope.launch {
            val glanceIdList = GlanceAppWidgetManager(context).getGlanceIds(TemperatureWidget::class.java)
            for (glanceId in glanceIdList) {  // For each Widget
                launch(defaultDispatcher) {  // Let the user know a job is ongoing.
                    updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { pref ->
                        pref.toMutablePreferences().apply {
                            this[REFRESHING_KEY] = true
                        }
                    }
                    glanceAppWidget.update(context, glanceId)
                }
            }
        }

        val immediateWork = OneTimeWorkRequest.Builder(TemperatureFetchingWorker::class.java).build()
        WorkManager.getInstance(context).also {
            it.enqueueUniqueWork(TEMP_WORK_ID_IMMEDIATE, ExistingWorkPolicy.KEEP, immediateWork)
        }
    }

    private fun stopTempWorks(context: Context) {
        WorkManager.getInstance(context).also {
            it.cancelUniqueWork(TEMP_WORK_ID_IMMEDIATE)
            it.cancelUniqueWork(TEMP_WORK_ID_PERIODIC)
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
            action = ACTION_REFRESH
        }
        context.sendBroadcast(intent)
    }

    companion object {
        const val ACTION_REFRESH = "ACTION_REFRESH"
        const val ACTION_DATA_FETCHED = "ACTION_DATA_FETCHED"
        const val EXTRA_HOURLY_TEMP = "extra_hourly_temp"
        const val EXTRA_TEMP_DIFF = "extra_temp_diff"
        const val EXTRA_DATA_VALID = "extra_data_valid"
    }
}