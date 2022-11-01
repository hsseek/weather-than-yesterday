package com.hsseek.betterthanyesterday.service

//import android.app.*
//import android.content.Intent
//import android.os.Binder
//import android.os.IBinder
//import android.util.Log
//import androidx.core.app.NotificationCompat
//import com.hsseek.betterthanyesterday.R
//import com.hsseek.betterthanyesterday.WeatherActivity
//import com.hsseek.betterthanyesterday.location.CoordinatesXy
//import com.hsseek.betterthanyesterday.network.BACKGROUND_MAX_RETRY
//import com.hsseek.betterthanyesterday.network.BACKGROUND_TIMEOUT
//import com.hsseek.betterthanyesterday.util.DEBUG_FLAG
//import com.hsseek.betterthanyesterday.widget.RefreshCallback
//import com.hsseek.betterthanyesterday.widget.TemperatureWidgetReceiver
//import com.hsseek.betterthanyesterday.widget.requestComparingTempData
//import kotlinx.coroutines.*
//
//
//private const val TAG = "HourlyTempFetchingService"
//private const val CHANNEL_ID = "HourlyTempFetchingService_Foreground_Channel"
//private const val REFRESHING_NOTIFICATION_ID = 87
//
//class HourlyTempFetchingService : Service() {
//    private val binder = LocalBinder()
//    private val job = SupervisorJob()
//    private val coroutineScope = CoroutineScope(Dispatchers.IO + job)
//
//    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
//        if (DEBUG_FLAG) Log.d(TAG, "onStartCommand(...) called.")
//        createNotificationChannel()
//        val notificationIntent = Intent(this, WeatherActivity::class.java)
//        val pendingIntent = PendingIntent.getActivity(
//            this,
//            0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
//        )
//        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
//            .setContentTitle(getString(R.string.toast_refreshing_ongoing))
//            .setContentText("${getString(R.string.app_name)} ${getString(R.string.service_notification_title)}")
//            .setSmallIcon(R.drawable.ic_refresh)
//            .setContentIntent(pendingIntent)
//            .build()
//        startForeground(REFRESHING_NOTIFICATION_ID, notification)
//
//        val nx = intent?.extras?.getInt(EXTRA_FORECAST_REGION_NX)
//        val ny = intent?.extras?.getInt(EXTRA_FORECAST_REGION_NY)
//
//        if (nx != null && ny != null) {
//            coroutineScope.requestComparingTempData(
//                xy = CoordinatesXy(nx, ny),
//                retry = BACKGROUND_MAX_RETRY,
//                timeout = BACKGROUND_TIMEOUT
//            ) { todayTemp, yesterdayTemp -> onFinishJob(todayTemp, yesterdayTemp) }
//        } else {
//            if (DEBUG_FLAG) Log.w(TAG, "Invalid ForecastRegion: $nx, $ny")
//        }
//        return START_NOT_STICKY
//    }
//
//    override fun onBind(p0: Intent?): IBinder {
//        if (DEBUG_FLAG) Log.d(TAG, "onBind(Intent?) called.")
//        return binder
//    }
//
//    override fun onDestroy() {
//        super.onDestroy()
//        job.cancel()
//        stopForeground(STOP_FOREGROUND_DETACH)
//        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
//        notificationManager.cancel(REFRESHING_NOTIFICATION_ID)
//        stopSelf()
//    }
//
//    private fun onFinishJob(todayTemp: Int?, yesterdayTemp: Int?) {
//        val diff = if (todayTemp != null && yesterdayTemp != null) todayTemp - yesterdayTemp else null
//
//        // Send broadcast to update Widget.
//        val intent = Intent(this, TemperatureWidgetReceiver::class.java).apply {
//            action = RefreshCallback.ACTION_DATA_FETCHED
//            putExtra(RefreshCallback.EXTRA_TEMP_DIFF, diff)
//            putExtra(RefreshCallback.EXTRA_HOURLY_TEMP, todayTemp)
//            putExtra(RefreshCallback.EXTRA_DATA_VALID, todayTemp != null && yesterdayTemp != null)
//        }
//        if (DEBUG_FLAG) Log.d(TAG,
//            "Send broadcast with: $todayTemp($diff), ${todayTemp != null && yesterdayTemp != null}")
//        sendBroadcast(intent)
//
//        // Job done.
//        stopForeground(STOP_FOREGROUND_DETACH)
//        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
//        notificationManager.cancel(REFRESHING_NOTIFICATION_ID)
//        stopSelf()
//    }
//
//    private fun createNotificationChannel() {
//        val serviceChannel = NotificationChannel(
//            CHANNEL_ID,
//            getString(R.string.service_notification_channel_refreshing),
//            NotificationManager.IMPORTANCE_LOW
//        ).apply {
//            setSound(null, null)
//        }
//        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
//        manager.createNotificationChannel(serviceChannel)
//    }
//
//    companion object {
//        const val EXTRA_FORECAST_REGION_NX = "extra_service_forecast_region_nx"
//        const val EXTRA_FORECAST_REGION_NY = "extra_service_forecast_region_ny"
//    }
//
//    inner class LocalBinder : Binder() {
//        // Return this instance of LocalService so clients can call public methods
//         fun getService(): HourlyTempFetchingService = this@HourlyTempFetchingService
//    }
//}