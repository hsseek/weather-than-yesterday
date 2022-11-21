package com.hsseek.betterthanyesterday.widget

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition

class GrayTemperatureWidgetReceiver : TemperatureWidgetReceiver() {
    override val glanceAppWidget = GrayTemperatureWidget()
    override val tag = "GrayTemperatureWidgetReceiver"

    override suspend fun updateWidgets(
        context: Context,
        isValid: Boolean,
        hourlyTemp: Int?,
        tempDiff: Int?
    ) {
        for (glanceId in GlanceAppWidgetManager(context).getGlanceIds(GrayTemperatureWidget::class.java)) {
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { pref ->
                pref.toMutablePreferences().apply {
                    this[GRAY_REFRESHING_KEY] = false
                    this[GRAY_VALID_DATA_KEY] = isValid
                    if (hourlyTemp != null) this[GRAY_HOURLY_TEMPERATURE_PREFS_KEY] = hourlyTemp
                    if (tempDiff != null) this[GRAY_TEMPERATURE_DIFF_PREFS_KEY] = tempDiff
                }
            }
            glanceAppWidget.update(context, glanceId)
        }
    }

    companion object {
        val GRAY_HOURLY_TEMPERATURE_PREFS_KEY = intPreferencesKey("gray_hourly_temp_prefs_key")
        val GRAY_TEMPERATURE_DIFF_PREFS_KEY = intPreferencesKey("gray_temp_diff_prefs_key")
        val GRAY_VALID_DATA_KEY = booleanPreferencesKey("gray_valid_data_key")
        val GRAY_REFRESHING_KEY = booleanPreferencesKey("gray_refreshing_key")
    }
}
