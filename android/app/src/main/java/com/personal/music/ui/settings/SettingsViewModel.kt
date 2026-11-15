package com.personal.music.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.music.library.LibraryRepository
import com.personal.music.playback.EqPreset
import com.personal.music.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUi(
    val eqPreset: EqPreset = EqPreset.OFF,
    val songs: Int = 0,
    val usedBytes: Long = 0L,
    val scanMessage: String? = null,
    val isScanning: Boolean = false,
)

class SettingsViewModel(
    private val settings: SettingsStore,
    library: LibraryRepository,
    private val onScanMusic: suspend () -> Int,
    coroutineScope: CoroutineScope? = null,
) : ViewModel() {

    private val scope = coroutineScope ?: viewModelScope

    private val scanState = MutableStateFlow<Pair<Boolean, String?>>(false to null)

    val ui: StateFlow<SettingsUi> = combine(
        settings.eqPreset,
        library.songs(),
        scanState,
    ) { eq, songs, scan ->
        SettingsUi(
            eqPreset = eq,
            songs = songs.size,
            usedBytes = songs.sumOf { it.sizeBytes },
            scanMessage = scan.second,
            isScanning = scan.first,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = SettingsUi(),
    )

    fun setEqPreset(preset: EqPreset): Job = scope.launch {
        settings.setEqPreset(preset)
    }

    fun scan(): Job = scope.launch {
        scanState.value = true to null
        scanState.value = try {
            val n = onScanMusic()
            false to (if (n == 0) "No songs found in that folder yet." else "Found $n songs on this phone.")
        } catch (e: Exception) {
            false to "Could not read the folder: ${e.message ?: "unknown error"}"
        }
    }
}
