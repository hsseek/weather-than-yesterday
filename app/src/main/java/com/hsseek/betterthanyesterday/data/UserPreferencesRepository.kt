package com.hsseek.betterthanyesterday.data

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.hsseek.betterthanyesterday.util.ForecastLocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private const val TAG = "PreferencesRepository"

class UserPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) {
    private object PreferencesKeys {
        val FORECAST_LOCATION_CODE = intPreferencesKey("forecast_location_code")
    }

    val forecastLocationFlow: Flow<Int> = dataStore.data
        .catch { e ->
            if (e is IOException) {
                emit(emptyPreferences())
            } else {
                throw e
            }
        }.map { preferences ->
            preferences[PreferencesKeys.FORECAST_LOCATION_CODE] ?: ForecastLocation.Auto.code
        }

    suspend fun updateForecastLocation(forecastLocation: ForecastLocation) {
        dataStore.edit { preferences ->
            Log.d(TAG, "Editing ForecastLocation preferences to ${forecastLocation.code}")
            preferences[PreferencesKeys.FORECAST_LOCATION_CODE] = forecastLocation.code
        }
    }
}
