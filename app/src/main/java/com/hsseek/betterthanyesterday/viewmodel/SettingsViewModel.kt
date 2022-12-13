package com.hsseek.betterthanyesterday.viewmodel

import android.content.res.Configuration
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hsseek.betterthanyesterday.data.UserPreferencesRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class SettingsViewModel(private val userPreferencesRepository: UserPreferencesRepository): ViewModel() {
    // Language
    var languageCode = 0
        private set

    private val _showLanguageDialog = mutableStateOf(false)
    val showLanguageDialog: Boolean
        get() = _showLanguageDialog.value

    // Dark mode
    var darkModeCode = 0
        private set
    private val _isDarkTheme = mutableStateOf(false)
    val isDarkTheme: Boolean
        get() = _isDarkTheme.value

    private val _showDarkModeDialog = mutableStateOf(false)
    val showDarkModeDialog: Boolean
        get() = _showDarkModeDialog.value

    // Simple View mode
    private val _isSimplified = mutableStateOf(false)
    val isSimplified: Boolean
        get() = _isSimplified.value

    private val _showSimpleViewHelp = mutableStateOf(false)
    val showSimpleViewHelp: Boolean
        get() = _showSimpleViewHelp.value

    // Daybreak mode
    private val _isDaybreak = mutableStateOf(false)
    val isDaybreak: Boolean
        get() = _isDaybreak.value

    private val _showDaybreakHelp = mutableStateOf(false)
    val showDaybreakHelp: Boolean
        get() = _showDaybreakHelp.value

    // TODO: Daily status(Very hot, very cold, rainy, snowy)

    // PresetRegion
    private val _isPresetRegion = mutableStateOf(false)
    val isPresetRegion: Boolean
        get() = _isPresetRegion.value

    private val _showPresetRegionHelp = mutableStateOf(false)
    val showPresetRegionHelp: Boolean
        get() = _showPresetRegionHelp.value

    fun onClickLanguage() {
        _showLanguageDialog.value = true
    }

    fun onDismissLanguage() {
        _showLanguageDialog.value = false
    }

    fun onClickDarkMode() {
        _showDarkModeDialog.value = true
    }

    fun onDismissDarkMode() {
        _showDarkModeDialog.value = false
    }

    fun updateLanguageCode(selectedCode: Int, isExplicit: Boolean = true) {
        languageCode = selectedCode
        if (isExplicit) {
            runBlocking {
                // The stored value is drawn during recreate().
                // Therefore, wait to be stored.
                userPreferencesRepository.updateLanguage(selectedCode)
            }
        }
    }

    fun updateDarkModeCode(selectedCode: Int, systemConfig: Int, isExplicit: Boolean = true) {
        darkModeCode = selectedCode
        _isDarkTheme.value = when (selectedCode) {
            1 -> false
            2 -> true
            else -> systemConfig and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }

        if (isExplicit) {
            runBlocking {
                // The stored value is drawn during recreate().
                // Therefore, wait to be stored.
                userPreferencesRepository.updateDarkMode(selectedCode)
            }
        }
    }

    fun updateSimpleViewEnabled(enabled: Boolean, isExplicit: Boolean = true) {
        _isSimplified.value = enabled
        if (isExplicit) {
            viewModelScope.launch {
                userPreferencesRepository.updateSimpleViewEnabled(enabled)
            }
        }
    }

    fun updateDaybreakEnabled(enabled: Boolean, isExplicit: Boolean = true) {
        _isDaybreak.value = enabled
        if (isExplicit) {
            viewModelScope.launch {
                userPreferencesRepository.updateDaybreakEnabled(enabled)
            }
        }
    }

    fun updatePresetRegionEnabled(enabled: Boolean, isExplicit: Boolean = true) {
        _isPresetRegion.value = enabled
        if (isExplicit) {
            viewModelScope.launch {
                userPreferencesRepository.updatePresetRegionEnabled(enabled)
            }
        }
    }

    fun onClickSimpleViewHelp() {
        _showSimpleViewHelp.value = true
    }

    fun onDismissSimpleViewHelp() {
        _showSimpleViewHelp.value = false
    }

    fun onClickDaybreakHelp() {
        _showDaybreakHelp.value = true
    }

    fun onDismissDaybreakHelp() {
        _showDaybreakHelp.value = false
    }

    fun onClickPresetRegionHelp() {
        _showPresetRegionHelp.value = true
    }

    fun onDismissPresetRegionHelp() {
        _showPresetRegionHelp.value = false
    }
}

class SettingsViewModelFactory(
    private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(userPreferencesRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
