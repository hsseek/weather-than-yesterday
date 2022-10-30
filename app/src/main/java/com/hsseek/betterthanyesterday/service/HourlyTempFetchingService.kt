package com.hsseek.betterthanyesterday.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.JsonSyntaxException
import com.google.gson.stream.MalformedJsonException
import com.hsseek.betterthanyesterday.R
import com.hsseek.betterthanyesterday.WeatherActivity
import com.hsseek.betterthanyesterday.location.CoordinatesXy
import com.hsseek.betterthanyesterday.network.NETWORK_PAUSE
import com.hsseek.betterthanyesterday.network.SERVICE_MAX_RETRY
import com.hsseek.betterthanyesterday.network.SERVICE_TIMEOUT
import com.hsseek.betterthanyesterday.util.DEBUG_FLAG
import com.hsseek.betterthanyesterday.util.VILLAGE_HOUR_INTERVAL
import com.hsseek.betterthanyesterday.util.getCurrentKoreanTime
import com.hsseek.betterthanyesterday.viewmodel.VILLAGE_TEMPERATURE_TAG
import com.hsseek.betterthanyesterday.viewmodel.getHourlyTempAsync
import com.hsseek.betterthanyesterday.widget.RefreshCallback
import com.hsseek.betterthanyesterday.widget.TemperatureWidgetReceiver
import kotlinx.coroutines.*
import java.util.*


private const val TAG = "HourlyTempFetchingService"
private const val CHANNEL_ID = "HourlyTempFetchingService_Foreground_Channel"
private const val REFRESHING_NOTIFICATION_ID = 87

class HourlyTempFetchingService : Service() {
    private val binder = LocalBinder()
    private val job = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + job)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (DEBUG_FLAG) Log.d(TAG, "onStartCommand(...) called.")
        createNotificationChannel()
        val notificationIntent = Intent(this, WeatherActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.toast_refreshing_ongoing))
            .setContentText("${getString(R.string.app_name)} ${getString(R.string.service_notification_title)}")
            .setSmallIcon(R.drawable.ic_refresh)
            .setContentIntent(pendingIntent)
            .build()
        startForeground(REFRESHING_NOTIFICATION_ID, notification)

        val nx = intent?.extras?.getInt(EXTRA_FORECAST_REGION_NX)
        val ny = intent?.extras?.getInt(EXTRA_FORECAST_REGION_NY)

        if (nx != null && ny != null) {
            requestData(this, CoordinatesXy(nx, ny))
        } else {
            if (DEBUG_FLAG) Log.w(TAG, "Invalid ForecastRegion: $nx, $ny")
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

    private fun requestData(context: Context, xy: CoordinatesXy) {
        Log.d(TAG, "requestData(Context) called.")

        var trialCount = 0
        var todayTemp: Int? = null
        var yesterdayTemp: Int? = null
        val cal = getCurrentKoreanTime()
        var isCalModified = false

        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                while (trialCount < SERVICE_MAX_RETRY) {
                    try {
                        withTimeout(SERVICE_TIMEOUT) {
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
                            if (++trialCount < SERVICE_MAX_RETRY) {
                                if (DEBUG_FLAG) Log.w(TAG, "(Retrying) $e")
                                runBlocking { delay(NETWORK_PAUSE) }
                            } else {  // Maximum count of trials has been reached.
                                Log.e(TAG, "Stopped retrying after $SERVICE_MAX_RETRY times.\n$e")
                                break
                            }
                        } else if (
                            e is MalformedJsonException ||
                            e is JsonSyntaxException
                        ) {  // Worth retrying, with different baseTime
                            if (++trialCount < SERVICE_MAX_RETRY) {
                                if (DEBUG_FLAG) Log.w(TAG, "(Retrying) $e")
                                val additionalRetry = 2
                                if (trialCount < SERVICE_MAX_RETRY - additionalRetry) { // Retry twice more.
                                    trialCount = SERVICE_MAX_RETRY - additionalRetry
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
            val diff = if (tt != null && yt != null) tt - yt else null

            // Send broadcast to update Widget.
            val intent = Intent(context, TemperatureWidgetReceiver::class.java).apply {
                action = RefreshCallback.DATA_RENEWED_ACTION
                putExtra(RefreshCallback.EXTRA_TEMP_DIFF, diff)
                putExtra(RefreshCallback.EXTRA_HOURLY_TEMP, tt)
                putExtra(RefreshCallback.EXTRA_DATA_VALID, tt != null && yt != null)
            }
            if (DEBUG_FLAG) Log.d(TAG, "Send broadcast with: $tt($diff), ${tt != null && yt != null}")
            context.sendBroadcast(intent)

            // Job done.
            stopForeground(STOP_FOREGROUND_DETACH)
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(REFRESHING_NOTIFICATION_ID)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_notification_channel_refreshing),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setSound(null, null)
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(serviceChannel)
    }

    companion object {
        const val EXTRA_FORECAST_REGION_NX = "extra_service_forecast_region_nx"
        const val EXTRA_FORECAST_REGION_NY = "extra_service_forecast_region_ny"
    }

    inner class LocalBinder : Binder() {
        // Return this instance of LocalService so clients can call public methods
        fun getService(): HourlyTempFetchingService = this@HourlyTempFetchingService
    }
}