package com.hsseek.betterthanyesterday.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.work.*
import com.hsseek.betterthanyesterday.service.HourlyTempFetchingService
import com.hsseek.betterthanyesterday.util.DEBUG_FLAG
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.TimeUnit


private const val TEMP_WORK_ID_IMMEDIATE = "hsseek.betterthanyesterday.widget.TEMP_WORK_ID_IMMEDIATE"
private const val TEMP_WORK_ID_NEXT = "hsseek.betterthanyesterday.widget.TEMP_WORK_ID_NEXT"
private const val DUMMY_PENDING_WORK = "hsseek.betterthanyesterday.widget.TEMP_DUMMY_WORK"

const val ACTION_REFRESH = "hsseek.betterthanyesterday.widget.ACTION_REFRESH"
const val ACTION_DATA_FETCHED = "hsseek.betterthanyesterday.widget.ACTION_DATA_FETCHED"
const val EXTRA_HOURLY_TEMP = "extra_hourly_temp"
const val EXTRA_TEMP_DIFF = "extra_temp_diff"
const val EXTRA_DATA_VALID = "extra_data_valid"


abstract class TemperatureWidgetReceiver : GlanceAppWidgetReceiver() {
    abstract override val glanceAppWidget: GlanceAppWidget
    abstract val tag: String
    abstract suspend fun updateWidgets(
        context: Context,
        isValid: Boolean,
        hourlyTemp: Int?,
        tempDiff: Int?,
    )

    private val coroutineScope = MainScope()

    // Called when a Widget is added, or on the automatic update.
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        if (DEBUG_FLAG) Log.d(tag, "onUpdate() called.")
        requestData(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (DEBUG_FLAG) Log.d(tag, "onReceive(...) called.(action: ${intent.action})")
        when (intent.action) {
            ACTION_DATA_FETCHED -> insertData(intent, context)
            ACTION_REFRESH -> requestData(context)
            AppWidgetManager.ACTION_APPWIDGET_ENABLED -> requestData(context)
            AppWidgetManager.ACTION_APPWIDGET_DISABLED -> stopTempWorks(context)
            AppWidgetManager.ACTION_APPWIDGET_DELETED -> stopTempWorks(context)
            else -> if (DEBUG_FLAG) Log.d(tag, "Nothing to do with the action.")
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
        val hourlyTemp = intent.extras?.getInt(EXTRA_HOURLY_TEMP)
        val tempDiff = intent.extras?.getInt(EXTRA_TEMP_DIFF)
        val isValid = intent.extras?.getBoolean(EXTRA_DATA_VALID) ?: false
        if (DEBUG_FLAG) Log.d(tag, "Temperature difference: $tempDiff")

        coroutineScope.launch {
            updateWidgets(context, isValid, hourlyTemp, tempDiff)
        }

        // Schedule Work for the next hour.
        val cal = Calendar.getInstance()
        val remainingSeconds = 3600L - (cal.get(Calendar.MINUTE) * 60 + cal.get(Calendar.SECOND))
        val bufferSeconds = 40

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isPowerSaveMode) {  // Use a Foreground Service with AlarmManger.
            if (DEBUG_FLAG) Log.d(tag, "Power save mode, reserving the Service.")
            val pendingIntent = PendingIntent.getService(
                context, 0,
                Intent(context, HourlyTempFetchingService::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + remainingSeconds * 1000, pendingIntent)
        } else {  // Utilize WorkManager.
            val nextHourWork = OneTimeWorkRequest.Builder(TemperatureFetchingWorker::class.java)
                .setConstraints(getFetchingWorkConstraints())
                .setInitialDelay(remainingSeconds + bufferSeconds, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).also {
                it.enqueueUniqueWork(TEMP_WORK_ID_NEXT, ExistingWorkPolicy.REPLACE, nextHourWork)
            }
        }
    }

    private fun getFetchingWorkConstraints(): Constraints = Constraints.Builder()
            .setRequiresCharging(false)
            .setRequiresBatteryNotLow(false)
            .setRequiresDeviceIdle(false)
            .setRequiresStorageNotLow(false)
            .build()

    private fun requestData(context: Context) {
        if (DEBUG_FLAG) Log.d(tag, "requestData(Context) called.")
        // Let the user know a job is ongoing.

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isPowerSaveMode) {  // Use a Foreground Service.
            if (DEBUG_FLAG) Log.d(tag, "Power save mode, starting the Service.")
            context.startForegroundService(Intent(context, HourlyTempFetchingService::class.java))
        } else {  // Utilize WorkManager.
            // Define the Work to fetch temperatures.
            val immediateWork = OneTimeWorkRequest.Builder(TemperatureFetchingWorker::class.java)
                .setConstraints(getFetchingWorkConstraints())
            if (Build.VERSION.SDK_INT >= 31) {
                immediateWork.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            WorkManager.getInstance(context).enqueueUniqueWork(
                TEMP_WORK_ID_IMMEDIATE,
                ExistingWorkPolicy.KEEP,
                immediateWork.build()
            )
        }
    }

    private fun stopTempWorks(context: Context) {
        WorkManager.getInstance(context).also {
            it.cancelUniqueWork(TEMP_WORK_ID_IMMEDIATE)
            it.cancelUniqueWork(TEMP_WORK_ID_NEXT)
            it.cancelUniqueWork(DUMMY_PENDING_WORK)
        }
    }
}