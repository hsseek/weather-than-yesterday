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
        val LANGUAGE_CODE = intPreferencesKey("language_code")
        val SIMPLE_VIEW_CODE = booleanPreferencesKey("simple_view_code")
        val AUTO_REFRESH_CODE = booleanPreferencesKey("auto_refresh_code")
        val DAYBREAK_CODE = booleanPreferencesKey("daybreak_code")
    }

    val preferencesFlow: Flow<UserPreferences> = context.dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }.map { preferences ->
            val locatingMethodCode = preferences[PreferencesKeys.LOCATING_METHOD_CODE] ?: LocatingMethod.Auto.code
            val languageCode = preferences[PreferencesKeys.LANGUAGE_CODE] ?: Language.System.code
            val isSimplified = preferences[PreferencesKeys.SIMPLE_VIEW_CODE] ?: false
            val isAutoRefresh = preferences[PreferencesKeys.AUTO_REFRESH_CODE] ?: false
            val isDaybreak = preferences[PreferencesKeys.DAYBREAK_CODE] ?: false

            UserPreferences(
                locatingMethodCode,
                languageCode,
                isSimplified,
                isAutoRefresh,
                isDaybreak,
            )
        }

    suspend fun updateLocatingMethod(locatingMethod: LocatingMethod) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LOCATING_METHOD_CODE] = locatingMethod.code
        }
    }

    suspend fun updateLanguage(selectedCode: Int) {
        context.dataStore.edit { preferences ->
            Log.d(TAG, "Language selected: $selectedCode")
            preferences[PreferencesKeys.LANGUAGE_CODE] = selectedCode
        }
    }

    suspend fun updateSimpleViewEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            Log.d(TAG, "Simple mode enabled: $enabled")
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

    suspend fun updateDaybreakEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            val status = if (enabled) "enabled" else "disabled"
            Log.d(TAG, "Daybreak mode: $status")
            preferences[PreferencesKeys.DAYBREAK_CODE] = enabled
        }
    }
}

data class UserPreferences(
    val locatingMethodCode: Int,
    val languageCode: Int,
    val isSimplified: Boolean,
    val isAutoRefresh: Boolean,
    val isDaybreak: Boolean,
)

enum class Language(val code: Int, val iso: String) {
    System(0, "en"), English(1, "en"), Korean(2, "ko")
}