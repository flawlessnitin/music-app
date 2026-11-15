package com.personal.music.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.personal.music.playback.EqPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore

/** The queue and spot the listener left off at, so the app can pick up where it stopped. */
data class SavedSession(val songIds: List<String>, val index: Int, val positionMs: Long)

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsStore(
    context: Context,
    private val dataStore: DataStore<Preferences> = context.settingsDataStore,
) {
    val eqPreset: Flow<EqPreset> = dataStore.data.map { prefs -> EqPreset.fromName(prefs[KEY_EQ_PRESET]) }

    suspend fun setEqPreset(preset: EqPreset) {
        dataStore.edit { prefs -> prefs[KEY_EQ_PRESET] = preset.name }
    }

    suspend fun session(): SavedSession? {
        val prefs = dataStore.data.first()
        val ids = prefs[KEY_SESSION_IDS]?.split(SESSION_SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()
        if (ids.isEmpty()) return null
        return SavedSession(ids, prefs[KEY_SESSION_INDEX] ?: 0, prefs[KEY_SESSION_POSITION] ?: 0L)
    }

    suspend fun saveSession(session: SavedSession?) {
        dataStore.edit { prefs ->
            if (session == null || session.songIds.isEmpty()) {
                prefs.remove(KEY_SESSION_IDS)
                prefs.remove(KEY_SESSION_INDEX)
                prefs.remove(KEY_SESSION_POSITION)
            } else {
                prefs[KEY_SESSION_IDS] = session.songIds.joinToString(SESSION_SEPARATOR)
                prefs[KEY_SESSION_INDEX] = session.index
                prefs[KEY_SESSION_POSITION] = session.positionMs
            }
        }
    }

    /**
     * Older versions of the app synced from Backblaze and kept a (scrambled) key on the phone.
     * That is gone now, so wipe whatever is left of it.
     */
    suspend fun forgetBackblaze() {
        dataStore.edit { prefs ->
            LEGACY_KEYS.forEach { prefs.remove(it) }
        }
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(LEGACY_KEY_ALIAS)
        }
    }

    companion object {
        private const val SESSION_SEPARATOR = "\n"
        private const val LEGACY_KEY_ALIAS = "b2_credentials_key"
        private val LEGACY_KEYS = listOf(
            stringPreferencesKey("b2_key_id"),
            stringPreferencesKey("b2_application_key"),
            stringPreferencesKey("b2_bucket"),
            androidx.datastore.preferences.core.booleanPreferencesKey("wifi_only"),
            longPreferencesKey("cap_bytes"),
            longPreferencesKey("last_sync_finished_at"),
            stringPreferencesKey("last_sync_outcome"),
            stringPreferencesKey("last_sync_message"),
            intPreferencesKey("last_sync_pending_songs"),
        )

        val KEY_EQ_PRESET = stringPreferencesKey("eq_preset")
        val KEY_SESSION_IDS = stringPreferencesKey("session_song_ids")
        val KEY_SESSION_INDEX = intPreferencesKey("session_index")
        val KEY_SESSION_POSITION = longPreferencesKey("session_position_ms")
    }
}
