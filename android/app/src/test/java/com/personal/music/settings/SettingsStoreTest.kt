package com.personal.music.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.personal.music.playback.EqPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    private lateinit var store: SettingsStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val file = File(tmp.newFolder("prefs"), "settings.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        store = SettingsStore(context, dataStore)
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `a saved listening session round trips and can be cleared`() = runTest {
        assertNull(store.session())

        store.saveSession(SavedSession(listOf("a", "b", "c"), index = 1, positionMs = 12_345))
        assertEquals(SavedSession(listOf("a", "b", "c"), 1, 12_345), store.session())

        store.saveSession(null)
        assertNull(store.session())
    }

    @Test
    fun `an empty session is stored as no session`() = runTest {
        store.saveSession(SavedSession(emptyList(), 0, 0))
        assertNull(store.session())
    }

    @Test
    fun `the sound preset defaults to off and is remembered`() = runTest {
        assertEquals(EqPreset.OFF, store.eqPreset.first())

        store.setEqPreset(EqPreset.BASS)

        assertEquals(EqPreset.BASS, store.eqPreset.first())
    }

    @Test
    fun `forgetting Backblaze wipes the old key and sync settings but keeps the rest`() = runTest {
        dataStore.edit {
            it[stringPreferencesKey("b2_application_key")] = "scrambled"
            it[stringPreferencesKey("b2_key_id")] = "id"
            it[booleanPreferencesKey("wifi_only")] = true
        }
        store.setEqPreset(EqPreset.VOCAL)

        store.forgetBackblaze()

        val prefs = dataStore.data.first()
        assertTrue(prefs.asMap().keys.none { it.name.startsWith("b2_") || it.name == "wifi_only" })
        assertEquals(EqPreset.VOCAL, store.eqPreset.first())
    }
}
