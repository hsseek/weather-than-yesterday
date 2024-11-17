package com.hsseek.betterthanyesterday.widget

import android.content.Context
import android.content.Intent
import androidx.compose.material.MaterialTheme
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
import com.hsseek.betterthanyesterday.util.getAdaptiveTempDiffColor
import com.hsseek.betterthanyesterday.widget.GrayTemperatureWidgetReceiver.Companion.GRAY_HOURLY_TEMPERATURE_PREFS_KEY
import com.hsseek.betterthanyesterday.widget.GrayTemperatureWidgetReceiver.Companion.GRAY_REFRESHING_KEY
import com.hsseek.betterthanyesterday.widget.GrayTemperatureWidgetReceiver.Companion.GRAY_TEMPERATURE_DIFF_PREFS_KEY
import com.hsseek.betterthanyesterday.widget.GrayTemperatureWidgetReceiver.Companion.GRAY_VALID_DATA_KEY
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class GrayTemperatureWidget : TemperatureWidget() {
    override val widgetBackground = ImageProvider(R.drawable.app_widget_gray_background)
    override val refreshAction = actionRunCallback<GrayRefreshCallback>()
    override val refreshIconId = R.drawable.ic_refresh

    override fun getWidgetUiState(prefs: Preferences): TemperatureWidgetUiState {
        return TemperatureWidgetUiState(
            valid = prefs[GRAY_VALID_DATA_KEY] ?: true,
            refreshing = prefs[GRAY_REFRESHING_KEY] ?: false,
            tempDiff = prefs[GRAY_TEMPERATURE_DIFF_PREFS_KEY],
            hourlyTemperature = prefs[GRAY_HOURLY_TEMPERATURE_PREFS_KEY],
            time = getCurrentKoreanTime()
        )
    }

    override fun getWidgetPlainTextColor(context: Context): Color {
        return Color(ContextCompat.getColor(context, R.color.on_background))
    }

    override fun getWidgetTempDiffColor(context: Context, tempDiff: Int): Color {
        return getAdaptiveTempDiffColor(context, tempDiff)
    }
}

class GrayRefreshCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val intent = Intent(context, GrayTemperatureWidgetReceiver::class.java).apply {
            action = ACTION_REFRESH
        }
        MainScope().launch {
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { pref ->
                pref.toMutablePreferences().apply {
                    this[GRAY_REFRESHING_KEY] = true
                }
            }
            GrayTemperatureWidget().update(context, glanceId)
        }
        context.sendBroadcast(intent)
    }
}