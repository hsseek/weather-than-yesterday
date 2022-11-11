package com.hsseek.betterthanyesterday.service

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hsseek.betterthanyesterday.R
import com.hsseek.betterthanyesterday.WeatherActivity
import com.hsseek.betterthanyesterday.data.UserPreferencesRepository
import com.hsseek.betterthanyesterday.location.CoordinatesXy
import com.hsseek.betterthanyesterday.network.BACKGROUND_ADDITIONAL_TIMEOUT
import com.hsseek.betterthanyesterday.network.BACKGROUND_MAX_RETRY
import com.hsseek.betterthanyesterday.network.BACKGROUND_TIMEOUT_MAX
import com.hsseek.betterthanyesterday.network.BACKGROUND_TIMEOUT_MIN
import com.hsseek.betterthanyesterday.util.DEBUG_FLAG
import com.hsseek.betterthanyesterday.util.notifyDebuggingLog
import com.hsseek.betterthanyesterday.widget.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first


private const val TAG = "HourlyTempFetchingService"
private const val CHANNEL_ID = "HourlyTempFetchingService_Foreground_Channel"
private const val REFRESHING_NOTIFICATION_ID = 87

class HourlyTempFetchingService : Service() {
    private val binder = LocalBinder()
    private val job = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + job)
    private lateinit var xy: CoordinatesXy

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (DEBUG_FLAG) Log.d(TAG, "onStartCommand(...) called.")
        try {
            runBlocking {
                val prefsRepo = UserPreferencesRepository(this@HourlyTempFetchingService)
                val prefs = prefsRepo.preferencesFlow.first()
                xy = CoordinatesXy(prefs.forecastRegionNx, prefs.forecastRegionNy)
            }

            createNotificationChannel()
            val notificationIntent = Intent(this, WeatherActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                this,
                0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
            )
            val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentText("${getString(R.string.app_name)} ${getString(R.string.service_notification_content)}")
                .setSmallIcon(R.drawable.ic_refresh)
                .setContentIntent(pendingIntent)
                .build()
            startForeground(REFRESHING_NOTIFICATION_ID, notification)

            coroutineScope.launch {
                requestComparingTempData(
                    xy = CoordinatesXy(xy.nx, xy.ny),
                    retry = BACKGROUND_MAX_RETRY,
                    timeoutMin = BACKGROUND_TIMEOUT_MIN,
                    additionalTimeout = BACKGROUND_ADDITIONAL_TIMEOUT,
                    timeoutMax = BACKGROUND_TIMEOUT_MAX,
                ) { todayTemp, yesterdayTemp -> onFinishJob(todayTemp, yesterdayTemp) }
            }
        } catch (e: Exception) {
            if (android.os.Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException) {
                notifyDebuggingLog(this, TAG, "Cannot launch foreground Service from background.")
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(p0: Intent?): IBinder {
        if (DEBUG_FLAG) Log.d(TAG, "onBind(Intent?) called.")
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        stopForeground(STOP_FOREGROUND_DETACH)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(REFRESHING_NOTIFICATION_ID)
        stopSelf()
    }

    private fun onFinishJob(todayTemp: Int?, yesterdayTemp: Int?) {
        val diff = if (todayTemp != null && yesterdayTemp != null) todayTemp - yesterdayTemp else null

        // Send broadcast to update Widget.
        val intent = Intent(this, TemperatureWidgetReceiver::class.java).apply {
            action = ACTION_DATA_FETCHED
            putExtra(EXTRA_TEMP_DIFF, diff)
            putExtra(EXTRA_HOURLY_TEMP, todayTemp)
            putExtra(EXTRA_DATA_VALID, todayTemp != null && yesterdayTemp != null)
        }
        if (DEBUG_FLAG) Log.d(TAG, "Send broadcast: $todayTemp($diff), ${todayTemp != null && yesterdayTemp != null}")
        sendBroadcast(intent)

        // Job done.
        stopForeground(STOP_FOREGROUND_DETACH)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(REFRESHING_NOTIFICATION_ID)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_notification_channel_title),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setSound(null, null)
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(serviceChannel)
    }

    inner class LocalBinder : Binder() {
        // Return this instance of LocalService so clients can call public methods
        @Suppress("unused")
        fun getService(): HourlyTempFetchingService = this@HourlyTempFetchingService
    }
}

