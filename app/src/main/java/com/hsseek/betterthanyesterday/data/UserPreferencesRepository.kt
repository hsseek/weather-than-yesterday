package com.hsseek.betterthanyesterday.data

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.hsseek.betterthanyesterday.util.LOCATING_METHOD_TAG
import com.hsseek.betterthanyesterday.util.LocatingMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

class UserPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) {
    private object PreferencesKeys {
        val FORECAST_LOCATION_CODE = intPreferencesKey("forecast_location_code")
    }

    val locatingMethodFlow: Flow<Int> = dataStore.data
        .catch { e ->
            if (e is IOException) {
                emit(emptyPreferences())
            } else {
                throw e
            }
        }.map { preferences ->
            preferences[PreferencesKeys.FORECAST_LOCATION_CODE] ?: LocatingMethod.Auto.code
        }

    suspend fun updateLocatingMethod(locatingMethod: LocatingMethod) {
        dataStore.edit { preferences ->
            Log.d(LOCATING_METHOD_TAG, "Editing LocatingMethod preferences to ${locatingMethod.code}")
            preferences[PreferencesKeys.FORECAST_LOCATION_CODE] = locatingMethod.code
        }
    }
}
