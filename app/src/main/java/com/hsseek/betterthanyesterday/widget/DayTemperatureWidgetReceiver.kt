package com.hsseek.betterthanyesterday.widget

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition

class DayTemperatureWidgetReceiver : TemperatureWidgetReceiver() {
    override val glanceAppWidget = DayTemperatureWidget()
    override val tag = "DayTemperatureWidgetReceiver"

    override suspend fun updateWidgets(
        context: Context,
        isValid: Boolean,
        hourlyTemp: Int?,
        tempDiff: Int?
    ) {
        for (glanceId in GlanceAppWidgetManager(context).getGlanceIds(DayTemperatureWidget::class.java)) {
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { pref ->
                pref.toMutablePreferences().apply {
                    this[DAY_REFRESHING_KEY] = false
                    this[DAY_VALID_DATA_KEY] = isValid
                    if (hourlyTemp != null) this[DAY_HOURLY_TEMPERATURE_PREFS_KEY] = hourlyTemp
                    if (tempDiff != null) this[DAY_TEMPERATURE_DIFF_PREFS_KEY] = tempDiff
                }
            }
            glanceAppWidget.update(context, glanceId)
        }
    }

    companion object {
        val DAY_HOURLY_TEMPERATURE_PREFS_KEY = intPreferencesKey("day_hourly_temp_prefs_key")
        val DAY_TEMPERATURE_DIFF_PREFS_KEY = intPreferencesKey("day_temp_diff_prefs_key")
        val DAY_VALID_DATA_KEY = booleanPreferencesKey("day_valid_data_key")
        val DAY_REFRESHING_KEY = booleanPreferencesKey("day_refreshing_key")
    }
}
