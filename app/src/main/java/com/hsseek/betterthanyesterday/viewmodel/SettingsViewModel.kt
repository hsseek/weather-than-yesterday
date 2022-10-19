package com.hsseek.betterthanyesterday.viewmodel

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

    // Simple View mode
    private val _isSimplified = mutableStateOf(false)
    val isSimplified: Boolean
        get() = _isSimplified.value

    private val _showSimpleViewHelp = mutableStateOf(false)
    val showSimpleViewHelp: Boolean
        get() = _showSimpleViewHelp.value

    // Auto refresh mode
    private val _isAutoRefresh = mutableStateOf(false)
    val isAutoRefresh: Boolean
        get() = _isAutoRefresh.value

    private val _showAutoRefreshHelp = mutableStateOf(false)
    val showAutoRefreshHelp: Boolean
        get() = _showAutoRefreshHelp.value

    // Daybreak mode
    private val _isDaybreak = mutableStateOf(false)
    val isDaybreak: Boolean
        get() = _isDaybreak.value

    private val _showDaybreakHelp = mutableStateOf(false)
    val showDaybreakHelp: Boolean
        get() = _showDaybreakHelp.value

    fun onClickLanguage() {
        _showLanguageDialog.value = true
    }

    fun onDismissLanguage() {
        _showLanguageDialog.value = false
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

    fun updateSimpleViewEnabled(enabled: Boolean, isExplicit: Boolean = true) {
        _isSimplified.value = enabled
        if (isExplicit) {
            viewModelScope.launch {
                userPreferencesRepository.updateSimpleViewEnabled(enabled)
            }
        }
    }

    fun updateAutoRefreshEnabled(enabled: Boolean, isExplicit: Boolean = true) {
        _isAutoRefresh.value = enabled
        if (isExplicit) {
            viewModelScope.launch {
                userPreferencesRepository.updateAutoRefreshEnabled(enabled)
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

    fun onClickSimpleViewHelp() {
        _showSimpleViewHelp.value = true
    }

    fun onDismissSimpleViewHelp() {
        _showSimpleViewHelp.value = false
    }

    fun onClickAutoRefreshHelp() {
        _showAutoRefreshHelp.value = true
    }

    fun onDismissAutoRefreshHelp() {
        _showAutoRefreshHelp.value = false
    }

    fun onClickDaybreakHelp() {
        _showDaybreakHelp.value = true
    }

    fun onDismissDaybreakHelp() {
        _showDaybreakHelp.value = false
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
