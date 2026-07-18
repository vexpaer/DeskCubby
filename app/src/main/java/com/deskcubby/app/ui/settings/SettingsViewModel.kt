package com.deskcubby.app.ui.settings

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.DarkMode
import com.deskcubby.app.data.model.NavItemConfig
import com.deskcubby.app.data.model.NavItemId
import com.deskcubby.app.data.model.VisualStyle
import com.deskcubby.app.data.preferences.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    val settings: StateFlow<AppSettings> = repository.settings.onEach { _ready.value = true }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppSettings(),
    )

    fun persistFolder(uri: Uri, diary: Boolean) {
        viewModelScope.launch {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            if (diary) repository.setDiaryTreeUri(uri.toString()) else repository.setMediaTreeUri(uri.toString())
        }
    }

    fun setVisualStyle(value: VisualStyle) = launch { repository.setVisualStyle(value) }
    fun setDarkMode(value: DarkMode) = launch { repository.setDarkMode(value) }
    fun setMediaPrefix(value: String) = launch { repository.setMediaMarkdownPrefix(value) }
    fun setFileNamePattern(value: String) = launch { repository.setFileNamePattern(value) }
    fun setTitlePattern(value: String) = launch { repository.setTitlePattern(value) }
    fun setDatePattern(value: String) = launch { repository.setDatePattern(value) }
    fun setTemplate(value: String) = launch { repository.setMarkdownTemplate(value) }
    fun setImageNamePattern(value: String) = launch { repository.setImageNamePattern(value) }
    fun setImageMaxWidth(value: Int) = launch { repository.setImageMaxWidth(value) }
    fun setImageMaxHeight(value: Int) = launch { repository.setImageMaxHeight(value) }
    fun setBrowserHome(value: String) = launch { repository.setBrowserHomeUrl(value) }
    fun setDefaultPage(value: NavItemId) = launch { repository.setDefaultPage(value) }
    fun setNavItems(value: List<NavItemConfig>) = launch { repository.setNavItems(value) }
    fun setHomeWidgets(value: List<String>) = launch { repository.setHomeWidgets(value) }

    private fun launch(block: suspend () -> Unit) = viewModelScope.launch { block() }
}
