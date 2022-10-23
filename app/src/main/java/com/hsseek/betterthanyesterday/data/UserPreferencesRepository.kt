package com.hsseek.betterthanyesterday.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.*
import com.hsseek.betterthanyesterday.App.Companion.dataStore
import com.hsseek.betterthanyesterday.R
import com.hsseek.betterthanyesterday.location.CoordinatesXy
import com.hsseek.betterthanyesterday.util.SEOUL
import com.hsseek.betterthanyesterday.util.toEnablementString
import com.hsseek.betterthanyesterday.util.toRegionString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private const val TAG = "UserPreferencesRepository"

class UserPreferencesRepository(private val context: Context) {
    private object PreferencesKeys {
        val FORECAST_REGION_AUTO = booleanPreferencesKey("forecast_region_auto")
        val FORECAST_REGION_ADDRESS = stringPreferencesKey("forecast_region_address")
        val FORECAST_REGION_NX = intPreferencesKey("forecast_region_nx")
        val FORECAST_REGION_NY = intPreferencesKey("region_ny")
        val LANGUAGE_CODE = intPreferencesKey("language_code")
        val SIMPLE_VIEW = booleanPreferencesKey("simple_view")
        val AUTO_REFRESH = booleanPreferencesKey("auto_refresh")
        val DAYBREAK = booleanPreferencesKey("daybreak")
        val PRESET_REGION = booleanPreferencesKey("preset_region")
    }

    val preferencesFlow: Flow<UserPreferences> = context.dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }.map { preferences ->
            val isForecastRegionAuto = preferences[PreferencesKeys.FORECAST_REGION_AUTO] ?: true
            val forecastRegionAddress= preferences[PreferencesKeys.FORECAST_REGION_ADDRESS] ?: "서울특별시 중구"
            val forecastRegionNx = preferences[PreferencesKeys.FORECAST_REGION_NX] ?: SEOUL.nx
            val forecastRegionNy = preferences[PreferencesKeys.FORECAST_REGION_NY] ?: SEOUL.ny
            val languageCode = preferences[PreferencesKeys.LANGUAGE_CODE] ?: Language.System.code
            val isSimplified = preferences[PreferencesKeys.SIMPLE_VIEW] ?: false
            val isAutoRefresh = preferences[PreferencesKeys.AUTO_REFRESH] ?: false
            val isDaybreak = preferences[PreferencesKeys.DAYBREAK] ?: false
            val isPresetRegion = preferences[PreferencesKeys.PRESET_REGION] ?: false

            UserPreferences(
                isForecastRegionAuto,
                forecastRegionAddress,
                forecastRegionNx,
                forecastRegionNy,
                languageCode,
                isSimplified,
                isAutoRefresh,
                isDaybreak,
                isPresetRegion,
            )
        }

    suspend fun updateAutoRegionEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            Log.d(TAG, "Automatic region stored: ${enabled.toEnablementString()}")
            preferences[PreferencesKeys.FORECAST_REGION_AUTO] = enabled
        }
    }

    suspend fun updateForecastRegion(region: ForecastRegion) {
        context.dataStore.edit { preferences ->
            Log.d(TAG, "ForecastRegion stored: ${region.toRegionString()}")
            preferences[PreferencesKeys.FORECAST_REGION_ADDRESS] = region.address
            preferences[PreferencesKeys.FORECAST_REGION_NX] = region.xy.nx
            preferences[PreferencesKeys.FORECAST_REGION_NY] = region.xy.ny
        }
    }

    suspend fun updateLanguage(selectedCode: Int) {
        context.dataStore.edit { preferences ->
            Log.d(TAG, "Language code stored: $selectedCode")
            preferences[PreferencesKeys.LANGUAGE_CODE] = selectedCode
        }
    }

    suspend fun updateSimpleViewEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            Log.d(TAG, "Simple mode stored: ${enabled.toEnablementString()}")
            preferences[PreferencesKeys.SIMPLE_VIEW] = enabled
        }
    }

    suspend fun updateAutoRefreshEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            Log.d(TAG, "Auto refresh stored: ${enabled.toEnablementString()}")
            preferences[PreferencesKeys.AUTO_REFRESH] = enabled
        }
    }

    suspend fun updateDaybreakEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            Log.d(TAG, "Daybreak mode stored: ${enabled.toEnablementString()}")
            preferences[PreferencesKeys.DAYBREAK] = enabled
        }
    }

    suspend fun updatePresetRegionEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            Log.d(TAG, "Preset location mode stored: ${enabled.toEnablementString()}")
            preferences[PreferencesKeys.PRESET_REGION] = enabled
        }
    }
}

data class UserPreferences(
    val isForecastRegionAuto: Boolean,
    val forecastRegionAddress: String,
    val forecastRegionNx: Int,
    val forecastRegionNy: Int,
    val languageCode: Int,
    val isSimplified: Boolean,
    val isAutoRefresh: Boolean,
    val isDaybreak: Boolean,
    val isPresetRegion: Boolean,
)

enum class Language(val code: Int, val iso: String) {
    System(0, "en"), English(1, "en"), Korean(2, "ko")
}

enum class PresetRegion(val regionId: Int, val examplesId: Int, val xy: CoordinatesXy) {
     Auto(R.string.region_auto, R.string.examples_auto, CoordinatesXy(0, 0)),  // An impossible values
    Capital(R.string.region_capital, R.string.examples_capital, CoordinatesXy(SEOUL.nx, SEOUL.ny)),
    Gangwon(R.string.region_gangwon, R.string.examples_gangwon, CoordinatesXy(73, 134)),
    SouthGs(R.string.region_south_gs, R.string.examples_south_gs, CoordinatesXy(98, 76)),
    NorthGs(R.string.region_north_gs, R.string.examples_north_gs, CoordinatesXy(89, 90)),
    SouthJl(R.string.region_south_jl, R.string.examples_south_jl, CoordinatesXy(58, 74)),
    NorthJl(R.string.region_north_jl, R.string.examples_north_jl, CoordinatesXy(63, 89)),
    Jeju(R.string.region_jeju, R.string.examples_jeju, CoordinatesXy(52, 38)),
    SouthCh(R.string.region_south_ch, R.string.examples_south_ch, CoordinatesXy(67, 100)),
    NorthCh(R.string.region_north_ch, R.string.examples_north_ch, CoordinatesXy(69, 107)),
    Dokdo(R.string.region_dokdo, R.string.examples_dokdo, CoordinatesXy(144, 123)),
}

data class ForecastRegion(val address: String, val xy: CoordinatesXy)