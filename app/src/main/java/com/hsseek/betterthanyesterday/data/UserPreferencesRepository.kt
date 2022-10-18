package com.hsseek.betterthanyesterday.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.*
import com.hsseek.betterthanyesterday.App.Companion.dataStore
import com.hsseek.betterthanyesterday.util.LocatingMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException


private const val TAG = "UserPreferencesRepository"

class UserPreferencesRepository(private val context: Context) {
    private object PreferencesKeys {
        val LOCATING_METHOD_CODE = intPreferencesKey("locating_method_code")
        val SIMPLE_VIEW_CODE = booleanPreferencesKey("simple_view_code")
        val AUTO_REFRESH_CODE = booleanPreferencesKey("auto_refresh_code")
    }

    val preferencesFlow: Flow<UserPreferences> = context.dataStore.data
        .catch { e ->
            if (e is IOException) {
                emit(emptyPreferences())
            } else {
                throw e
            }
        }.map { preferences ->
            val locatingMethodCode = preferences[PreferencesKeys.LOCATING_METHOD_CODE] ?: LocatingMethod.Auto.code
            val isSimplified = preferences[PreferencesKeys.SIMPLE_VIEW_CODE] ?: false
            val isAutoRefresh = preferences[PreferencesKeys.AUTO_REFRESH_CODE] ?: false

            UserPreferences(
                locatingMethodCode,
                isSimplified,
                isAutoRefresh
            )
        }

    suspend fun updateLocatingMethod(locatingMethod: LocatingMethod) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LOCATING_METHOD_CODE] = locatingMethod.code
        }
    }

    suspend fun updateSimpleViewEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            val status = if (enabled) "enabled" else "disabled"
            Log.d(TAG, "Simple mode: $status")
            preferences[PreferencesKeys.SIMPLE_VIEW_CODE] = enabled
        }
    }

    suspend fun updateAutoRefreshEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            val status = if (enabled) "enabled" else "disabled"
            Log.d(TAG, "Auto refresh: $status")
            preferences[PreferencesKeys.AUTO_REFRESH_CODE] = enabled
        }
    }
}

data class UserPreferences(
    val locatingMethodCode: Int,
    val isSimplified: Boolean,
    val isAutoRefresh: Boolean,
)
