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
import com.hsseek.betterthanyesterday.util.getDarkTempDiffColor
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
        return getDarkTempDiffColor(context, tempDiff)
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