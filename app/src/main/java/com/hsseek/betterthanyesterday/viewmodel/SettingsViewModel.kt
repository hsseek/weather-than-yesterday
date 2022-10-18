package com.hsseek.betterthanyesterday.viewmodel

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hsseek.betterthanyesterday.data.UserPreferencesRepository
import kotlinx.coroutines.launch

class SettingsViewModel(private val userPreferencesRepository: UserPreferencesRepository): ViewModel() {
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

    fun onClickSimpleView(checked: Boolean) {
        _isSimplified.value = checked
        viewModelScope.launch {
            userPreferencesRepository.updateSimpleViewEnabled(checked)
        }
    }

    fun onClickAutoRefresh(checked: Boolean) {
        _isAutoRefresh.value = checked
        viewModelScope.launch {
            userPreferencesRepository.updateAutoRefreshEnabled(checked)
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
