package com.hsseek.betterthanyesterday.widget

import android.app.NotificationChannel
import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
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
import com.hsseek.betterthanyesterday.data.UserPreferencesRepository
import com.hsseek.betterthanyesterday.util.*
import com.hsseek.betterthanyesterday.widget.RefreshCallback.Companion.EXTRA_DATA_VALID
import com.hsseek.betterthanyesterday.widget.RefreshCallback.Companion.EXTRA_HOURLY_TEMP
import com.hsseek.betterthanyesterday.widget.RefreshCallback.Companion.EXTRA_TEMP_DIFF
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.*
import java.util.concurrent.TimeUnit


private const val TAG = "TemperatureWidgetReceiver"
private const val TEMP_WORK_ID_IMMEDIATE = "hsseek.betterthanyesterday.widget.TEMP_WORK_ID_IMMEDIATE"
private const val TEMP_WORK_ID_NEXT = "hsseek.betterthanyesterday.widget.TEMP_WORK_ID_NEXT"
private const val DUMMY_PENDING_WORK = "hsseek.betterthanyesterday.widget.TEMP_DUMMY_WORK"

class TemperatureWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = TemperatureWidget()
    private val coroutineScope = MainScope()
    private val defaultDispatcher = Dispatchers.Default

    // Called when a Widget is added, or on the automatic update.
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        if (DEBUG_FLAG) Log.d(TAG, "onUpdate() called.")
        requestData(context, true)
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

    // To avoid infinite onUpdate() loop(https://stackoverflow.com/a/70685721/17198283)
    override fun onEnabled(context: Context) {
        val alwaysPendingWork = OneTimeWorkRequestBuilder<TemperatureFetchingWorker>()
            .setInitialDelay(5000L, TimeUnit.DAYS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            DUMMY_PENDING_WORK,
            ExistingWorkPolicy.KEEP,
            alwaysPendingWork
        )
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

            // Update the hour to UserPreferences so that it won't request for the same hour.
            val currentHour = Calendar.getInstance().hourSince1970()
            val userPrefsRepo = UserPreferencesRepository(context)
            userPrefsRepo.updateWidgetTime(currentHour.toInt())
        }

        // Schedule Work for the next hour.
        val cal = Calendar.getInstance()
        val remainingMinutes = 60L - cal.get(Calendar.MINUTE)  // Seconds rounded up, working as a buffer.
        notifyDebuggingLog(context, "Next job scheduled in $remainingMinutes min.")

        val nextHourWork = OneTimeWorkRequest.Builder(TemperatureFetchingWorker::class.java)
            .setInitialDelay(remainingMinutes, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).also {
            it.enqueueUniqueWork(TEMP_WORK_ID_NEXT, ExistingWorkPolicy.REPLACE, nextHourWork)
        }
    }

    private fun requestData(context: Context, forced: Boolean = false) {
        if (DEBUG_FLAG) Log.d(TAG, "requestData(Context) called.")
        // Let the user know a job is ongoing.
        coroutineScope.launch {
            val glanceIdList = GlanceAppWidgetManager(context).getGlanceIds(TemperatureWidget::class.java)
            for (glanceId in glanceIdList) {  // For each Widget
                launch(defaultDispatcher) {
                    updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { pref ->
                        pref.toMutablePreferences().apply {
                            this[REFRESHING_KEY] = true
                        }
                    }
                    glanceAppWidget.update(context, glanceId)
                }
            }
        }

        // Check whether new data should be presented.
        val currentHour = Calendar.getInstance().hourSince1970()
        val representedHour: Int
        runBlocking {
            val prefsRepo = UserPreferencesRepository(context)
            val prefs = prefsRepo.preferencesFlow.first()
            representedHour = prefs.widgetTime
        }

        if (currentHour > representedHour || forced) {
            notifyDebuggingLog(context, "Refreshing")
            if (DEBUG_FLAG) Log.d(TAG, "Hour changed, new data required.")
            val immediateWork = OneTimeWorkRequest.Builder(TemperatureFetchingWorker::class.java).build()
            WorkManager.getInstance(context).also {
                it.enqueueUniqueWork(TEMP_WORK_ID_IMMEDIATE, ExistingWorkPolicy.KEEP, immediateWork)
            }
        } else {
            notifyDebuggingLog(context, "Skipping")
            if (DEBUG_FLAG) Log.d(TAG, "Hour not changed, new data not required.")
            coroutineScope.launch(defaultDispatcher) {
                delay((220..360).random().toLong())  // Show fake loading for ux.
                val glanceIdList = GlanceAppWidgetManager(context).getGlanceIds(TemperatureWidget::class.java)
                for (glanceId in glanceIdList) {  // Dismiss Loading
                    updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { pref ->
                        pref.toMutablePreferences().apply {
                            this[REFRESHING_KEY] = false
                        }
                    }
                    glanceAppWidget.update(context, glanceId)
                }
            }
        }
    }

    private fun stopTempWorks(context: Context) {
        WorkManager.getInstance(context).also {
            it.cancelUniqueWork(TEMP_WORK_ID_IMMEDIATE)
            it.cancelUniqueWork(TEMP_WORK_ID_NEXT)
            it.cancelUniqueWork(DUMMY_PENDING_WORK)
        }
    }

    private fun notifyDebuggingLog(context: Context, msg: String? = null) {
        // A Notification for logging
        if (DEBUG_FLAG) {
            val channelId = "TEMP_CHANNEL_ID"
            val channelName = "TEMP_CHANNEL_NAME"
            val groupKey = "TEMP_GROUP_KEY"
            val notificationChannel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH)

            val notification = NotificationCompat.Builder(context, channelId)
                .setContentText("Widget: $msg")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setGroup(groupKey)
                .build()

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(notificationChannel)
            notificationManager.notify(Calendar.getInstance().timeInMillis.toInt(), notification)
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
        const val ACTION_REFRESH = "hsseek.betterthanyesterday.widget.ACTION_REFRESH"
        const val ACTION_DATA_FETCHED = "hsseek.betterthanyesterday.widget.ACTION_DATA_FETCHED"
        const val EXTRA_HOURLY_TEMP = "extra_hourly_temp"
        const val EXTRA_TEMP_DIFF = "extra_temp_diff"
        const val EXTRA_DATA_VALID = "extra_data_valid"
    }
}