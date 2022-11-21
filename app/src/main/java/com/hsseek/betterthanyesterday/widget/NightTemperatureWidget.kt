package com.hsseek.betterthanyesterday.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.Preferences
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.hsseek.betterthanyesterday.R
import com.hsseek.betterthanyesterday.util.getCurrentKoreanTime
import com.hsseek.betterthanyesterday.widget.NightTemperatureWidgetReceiver.Companion.NIGHT_HOURLY_TEMPERATURE_PREFS_KEY
import com.hsseek.betterthanyesterday.widget.NightTemperatureWidgetReceiver.Companion.NIGHT_REFRESHING_KEY
import com.hsseek.betterthanyesterday.widget.NightTemperatureWidgetReceiver.Companion.NIGHT_TEMPERATURE_DIFF_PREFS_KEY
import com.hsseek.betterthanyesterday.widget.NightTemperatureWidgetReceiver.Companion.NIGHT_VALID_DATA_KEY
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class NightTemperatureWidget : TemperatureWidget() {
    override val widgetBackground = ImageProvider(R.drawable.app_widget_transparent_background)
    override val refreshAction = actionRunCallback<NightRefreshCallback>()
    override val descriptiveTextColorId = android.R.color.white
    override val plainTextColorId = android.R.color.white
    override val refreshIconId = R.drawable.ic_refresh_white

    override fun getWidgetUiState(prefs: Preferences): TemperatureWidgetUiState {
        return TemperatureWidgetUiState(
            valid = prefs[NIGHT_VALID_DATA_KEY] ?: true,
            refreshing = prefs[NIGHT_REFRESHING_KEY] ?: false,
            tempDiff = prefs[NIGHT_TEMPERATURE_DIFF_PREFS_KEY],
            hourlyTemperature = prefs[NIGHT_HOURLY_TEMPERATURE_PREFS_KEY],
            time = getCurrentKoreanTime()
        )
    }

    override fun getWidgetTempDiffColor(context: Context, tempDiff: Int): Color {
        return when {
            tempDiff > 8 -> Color(ContextCompat.getColor(context, R.color.red_800))
            tempDiff == 7 -> Color(ContextCompat.getColor(context, R.color.night_red_700))
            tempDiff == 6 -> Color(ContextCompat.getColor(context, R.color.night_red_600))
            tempDiff == 5 -> Color(ContextCompat.getColor(context, R.color.night_red_500))
            tempDiff == 4 -> Color(ContextCompat.getColor(context, R.color.night_red_400))
            tempDiff == 3 -> Color(ContextCompat.getColor(context, R.color.night_red_300))
            tempDiff == 2 -> Color(ContextCompat.getColor(context, R.color.night_red_200))
            tempDiff == 1 -> Color(ContextCompat.getColor(context, R.color.night_red_100))
            tempDiff == 0 -> Color(ContextCompat.getColor(context, android.R.color.white))
            tempDiff == -1 -> Color(ContextCompat.getColor(context, R.color.night_cool_100))
            tempDiff == -2 -> Color(ContextCompat.getColor(context, R.color.night_cool_200))
            tempDiff == -3 -> Color(ContextCompat.getColor(context, R.color.night_cool_300))
            tempDiff == -4 -> Color(ContextCompat.getColor(context, R.color.night_cool_400))
            tempDiff == -5 -> Color(ContextCompat.getColor(context, R.color.night_cool_500))
            tempDiff == -6 -> Color(ContextCompat.getColor(context, R.color.night_cool_600))
            tempDiff == -7 -> Color(ContextCompat.getColor(context, R.color.night_cool_700))
            else -> Color(ContextCompat.getColor(context, R.color.cool_800))
        }
    }
}

class NightRefreshCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val intent = Intent(context, NightTemperatureWidgetReceiver::class.java).apply {
            action = ACTION_REFRESH
        }
        MainScope().launch {
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { pref ->
                pref.toMutablePreferences().apply {
                    this[NIGHT_REFRESHING_KEY] = true
                }
            }
            NightTemperatureWidget().update(context, glanceId)
        }
        context.sendBroadcast(intent)
    }
}