package com.hsseek.betterthanyesterday.widget

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition

class NightTemperatureWidgetReceiver : TemperatureWidgetReceiver() {
    override val glanceAppWidget = NightTemperatureWidget()
    override val tag = "NightTemperatureWidgetReceiver"

    override suspend fun updateWidgets(
        context: Context,
        isValid: Boolean,
        hourlyTemp: Int?,
        tempDiff: Int?
    ) {
        for (glanceId in GlanceAppWidgetManager(context).getGlanceIds(NightTemperatureWidget::class.java)) {
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { pref ->
                pref.toMutablePreferences().apply {
                    this[NIGHT_REFRESHING_KEY] = false
                    this[NIGHT_VALID_DATA_KEY] = isValid
                    if (hourlyTemp != null) this[NIGHT_HOURLY_TEMPERATURE_PREFS_KEY] = hourlyTemp
                    if (tempDiff != null) this[NIGHT_TEMPERATURE_DIFF_PREFS_KEY] = tempDiff
                }
            }
            glanceAppWidget.update(context, glanceId)
        }
    }

    companion object {
        val NIGHT_HOURLY_TEMPERATURE_PREFS_KEY = intPreferencesKey("night_hourly_temp_prefs_key")
        val NIGHT_TEMPERATURE_DIFF_PREFS_KEY = intPreferencesKey("night_temp_diff_prefs_key")
        val NIGHT_VALID_DATA_KEY = booleanPreferencesKey("night_valid_data_key")
        val NIGHT_REFRESHING_KEY = booleanPreferencesKey("night_refreshing_key")
    }
}
