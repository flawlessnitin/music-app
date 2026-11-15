package com.personal.music.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.music.b2.B2Client
import com.personal.music.b2.B2Exception
import com.personal.music.catalog.CatalogParser
import com.personal.music.library.LibraryRepository
import com.personal.music.playback.EqPreset
import com.personal.music.settings.Credentials
import com.personal.music.settings.SettingsStore
import com.personal.music.settings.SyncOutcome
import com.personal.music.settings.SyncSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUi(
    val hasCredentials: Boolean = false,
    val keyId: String = "",
    val bucket: String = "",
    val wifiOnly: Boolean = true,
    val capGb: Int = 20,
    val usedBytes: Long = 0L,
    val downloadedSongs: Int = 0,
    val waitingSongs: Int = 0,
    val lastSyncMessage: String? = null,
    val lastSyncFinishedAtMillis: Long? = null,
    val testMessage: String? = null,
    val isTesting: Boolean = false,
    val eqPreset: EqPreset = EqPreset.OFF,
    val localSongs: Int = 0,
    val localMessage: String? = null,
    val isScanningLocal: Boolean = false,
) {
    val hasKey: Boolean get() = hasCredentials
    val downloadedCount: Int get() = downloadedSongs
    val waitingCount: Int get() = waitingSongs
}

fun outcomeMessage(summary: SyncSummary): String = when (summary.outcome) {
    SyncOutcome.UP_TO_DATE -> "Up to date."
    SyncOutcome.DOWNLOADED -> "Downloaded. Everything is on your phone."
    SyncOutcome.WAITING -> "Backblaze paused downloads for now. ${summary.pendingSongs} songs will download later."
    SyncOutcome.BAD_KEY -> "Backblaze rejected the key. Check the key ID, key and bucket name."
    SyncOutcome.NO_NETWORK -> "No Wi-Fi. Sync will run when Wi-Fi is back."
    SyncOutcome.STORAGE_FULL -> "Your phone is out of space. ${summary.pendingSongs} songs are waiting."
    SyncOutcome.CAP_REACHED -> "Storage cap reached. Raise the cap to download ${summary.pendingSongs} more songs."
    SyncOutcome.NOT_SET_UP -> "Add your Backblaze key to start."
    SyncOutcome.ERROR -> "Sync failed: " + summary.message
}

class SettingsViewModel(
    private val settings: SettingsStore,
    private val library: LibraryRepository,
    private val b2: B2Client,
    private val onSyncNow: () -> Unit,
    coroutineScope: CoroutineScope? = null,
    private val onScanLocal: (suspend () -> Int)? = null,
) : ViewModel() {

    private val scope = coroutineScope ?: viewModelScope

    private data class TestState(
        val isTesting: Boolean = false,
        val message: String? = null,
    )

    private class Prefs(
        val credentials: Credentials?,
        val wifiOnly: Boolean,
        val capBytes: Long,
        val eqPreset: EqPreset,
    )

    private val testState = MutableStateFlow(TestState())
    private val scanState = MutableStateFlow<Pair<Boolean, String?>>(false to null)

    val ui: StateFlow<SettingsUi> = combine(
        combine(settings.credentialsFlow, settings.wifiOnly, settings.capBytes, settings.eqPreset) { creds, wifi, cap, eq ->
            Prefs(creds, wifi, cap, eq)
        },
        settings.lastSync,
        library.songs(),
        testState,
        scanState,
    ) { prefs, lastSync, songs, test, scan ->
        val creds = prefs.credentials
        val wifi = prefs.wifiOnly
        val cap = prefs.capBytes
        val capGb = (cap / 1_000_000_000L).toInt().coerceIn(1, 40)
        val synced = songs.filter { !it.local }
        val downloaded = synced.count { it.downloaded }
        val waiting = synced.count { !it.downloaded }
        val used = synced.filter { it.downloaded }.sumOf { it.sizeBytes }
        val syncMessage = lastSync?.let { outcomeMessage(it) }

        SettingsUi(
            hasCredentials = creds != null,
            keyId = creds?.keyId ?: "",
            bucket = creds?.bucket ?: "",
            wifiOnly = wifi,
            capGb = capGb,
            usedBytes = used,
            downloadedSongs = downloaded,
            waitingSongs = waiting,
            lastSyncMessage = syncMessage,
            lastSyncFinishedAtMillis = lastSync?.finishedAtMillis,
            testMessage = test.message,
            isTesting = test.isTesting,
            eqPreset = prefs.eqPreset,
            localSongs = songs.count { it.local },
            localMessage = scan.second,
            isScanningLocal = scan.first,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = SettingsUi(),
    )

    fun save(keyId: String, applicationKey: String, bucket: String): Job = scope.launch {
        val trimmedKeyId = keyId.trim()
        val trimmedBucket = bucket.trim()
        // The key field starts empty after leaving the screen; keep the saved key then.
        val trimmedAppKey = applicationKey.trim().ifBlank { settings.credentials()?.applicationKey.orEmpty() }

        if (trimmedKeyId.isBlank() || trimmedAppKey.isBlank() || trimmedBucket.isBlank()) {
            testState.value = TestState(
                isTesting = false,
                message = "Key ID, application key, and bucket cannot be blank.",
            )
            return@launch
        }

        testState.value = TestState(isTesting = true, message = null)
        settings.saveCredentials(Credentials(trimmedKeyId, trimmedAppKey, trimmedBucket))
        b2.invalidate()

        try {
            val catalogText = b2.readText("catalog.json")
            val catalog = CatalogParser.parse(catalogText)
            val n = catalog.songs.size
            testState.value = TestState(
                isTesting = false,
                message = "Connected. $n songs in your library.",
            )
            onSyncNow()
        } catch (e: B2Exception.BadKey) {
            testState.value = TestState(
                isTesting = false,
                message = "Backblaze rejected the key. Check the key ID, key and bucket name.",
            )
        } catch (e: B2Exception.NotFound) {
            testState.value = TestState(
                isTesting = false,
                message = "No catalog.json in the bucket yet. Add songs with musictool first.",
            )
        } catch (e: B2Exception.Network) {
            testState.value = TestState(
                isTesting = false,
                message = "Network error. Check your connection.",
            )
        } catch (e: Exception) {
            testState.value = TestState(
                isTesting = false,
                message = "Connection failed: ${e.message ?: "Unknown error"}",
            )
        }
    }

    fun setWifiOnly(v: Boolean): Job = scope.launch {
        settings.setWifiOnly(v)
    }

    fun setEqPreset(preset: EqPreset): Job = scope.launch {
        settings.setEqPreset(preset)
    }

    fun setCapGb(gb: Int): Job = scope.launch {
        val clamped = gb.coerceIn(1, 40)
        settings.setCapBytes(clamped * 1_000_000_000L)
    }

    fun syncNow() {
        onSyncNow()
    }

    fun scanLocal(): Job = scope.launch {
        val scan = onScanLocal ?: return@launch
        scanState.value = true to null
        scanState.value = try {
            val n = scan()
            false to (if (n == 0) "No songs found in that folder yet." else "Found $n songs on this phone.")
        } catch (e: Exception) {
            false to "Could not read the folder: ${e.message ?: "unknown error"}"
        }
    }

    fun clearKey(): Job = scope.launch {
        settings.clearCredentials()
        b2.invalidate()
        testState.value = TestState(isTesting = false, message = null)
    }
}
