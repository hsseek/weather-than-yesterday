package com.hsseek.betterthanyesterday.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.Preferences
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.hsseek.betterthanyesterday.R
import com.hsseek.betterthanyesterday.util.getCurrentKoreanTime
import com.hsseek.betterthanyesterday.util.getLightTempDiffColor
import com.hsseek.betterthanyesterday.widget.DayTemperatureWidgetReceiver.Companion.DAY_HOURLY_TEMPERATURE_PREFS_KEY
import com.hsseek.betterthanyesterday.widget.DayTemperatureWidgetReceiver.Companion.DAY_REFRESHING_KEY
import com.hsseek.betterthanyesterday.widget.DayTemperatureWidgetReceiver.Companion.DAY_TEMPERATURE_DIFF_PREFS_KEY
import com.hsseek.betterthanyesterday.widget.DayTemperatureWidgetReceiver.Companion.DAY_VALID_DATA_KEY
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class DayTemperatureWidget : TemperatureWidget() {
    override val widgetBackground = ImageProvider(R.drawable.app_widget_transparent_background)
    override val refreshAction = actionRunCallback<DayRefreshCallback>()
    override val descriptiveTextColorId = android.R.color.black
    override val plainTextColorId = android.R.color.black
    override val refreshIconId = R.drawable.ic_refresh_black

    override fun getWidgetUiState(prefs: Preferences): TemperatureWidgetUiState {
        return TemperatureWidgetUiState(
            valid = prefs[DAY_VALID_DATA_KEY] ?: true,
            refreshing = prefs[DAY_REFRESHING_KEY] ?: false,
            tempDiff = prefs[DAY_TEMPERATURE_DIFF_PREFS_KEY],
            hourlyTemperature = prefs[DAY_HOURLY_TEMPERATURE_PREFS_KEY],
            time = getCurrentKoreanTime()
        )
    }

    override fun getWidgetTempDiffColor(context: Context, tempDiff: Int): Color {
        return getLightTempDiffColor(context, tempDiff)
    }
}

class DayRefreshCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val intent = Intent(context, DayTemperatureWidgetReceiver::class.java).apply {
            action = ACTION_REFRESH
        }
        MainScope().launch {
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { pref ->
                pref.toMutablePreferences().apply {
                    this[DAY_REFRESHING_KEY] = true
                }
            }
            DayTemperatureWidget().update(context, glanceId)
        }
        context.sendBroadcast(intent)
    }
}