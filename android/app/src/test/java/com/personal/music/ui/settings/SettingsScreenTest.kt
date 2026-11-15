package com.personal.music.ui.settings

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.music.db.MusicDatabase
import com.personal.music.db.SongEntity
import com.personal.music.library.LibraryRepository
import com.personal.music.playback.EqPreset
import com.personal.music.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: MusicDatabase
    private lateinit var library: LibraryRepository
    private lateinit var settings: SettingsStore
    private lateinit var storeScope: CoroutineScope
    private var scans = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        library = LibraryRepository(db)
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val prefsFile = File(tmp.newFolder("prefs"), "settings.preferences_pb")
        settings = SettingsStore(context, PreferenceDataStoreFactory.create(scope = storeScope) { prefsFile })
        scans = 0
    }

    @After
    fun tearDown() {
        storeScope.cancel()
        db.close()
    }

    private fun viewModel() = SettingsViewModel(settings, library, { scans++; 3 })

    private fun show(path: String = "") {
        val vm = viewModel()
        composeTestRule.setContent { SettingsScreen(viewModel = vm, musicPath = path) }
    }

    @Test
    fun `shows the folder songs are sent to`() {
        show("/sdcard/Android/data/app/files/Music")

        composeTestRule.onNodeWithTag("music_path").assertTextContains("/sdcard/Android/data/app/files/Music")
    }

    @Test
    fun `shows how many songs are on the phone`() {
        runBlocking {
            library.replaceSongs(
                listOf(
                    SongEntity(
                        id = "a", title = "A", artist = "X", album = "Y", albumArtist = "X",
                        track = 1, disc = 1, year = 2024, durationMs = 1000, sizeBytes = 2_000_000_000,
                        format = "flac", file = "/m/a.flac", cover = null,
                    ),
                ),
            )
        }
        show()
        composeTestRule.waitUntil(5_000) {
            runCatching { composeTestRule.onNodeWithTag("library_summary").assertTextContains("1 songs, 2.00 GB") }.isSuccess
        }
    }

    @Test
    fun `the scan button scans and shows the result`() {
        show()

        composeTestRule.onNodeWithTag("scan_button").performScrollTo().performClick()

        composeTestRule.waitUntil(5_000) {
            runCatching { composeTestRule.onNodeWithTag("scan_message").assertIsDisplayed() }.isSuccess
        }
        composeTestRule.onNodeWithTag("scan_message").assertTextContains("Found 3 songs on this phone.")
        assertEquals(1, scans)
    }

    @Test
    fun `tapping a sound preset selects and remembers it`() {
        show()

        composeTestRule.onNodeWithTag("eq_BASS").performScrollTo().performClick()

        // The choice is saved, comes back through the screen's state and shows as selected.
        composeTestRule.waitUntil(5_000) {
            runCatching { composeTestRule.onNodeWithTag("eq_BASS").assertIsSelected() }.isSuccess
        }
        assertEquals(EqPreset.BASS, runBlocking { settings.eqPreset.first() })
    }
}
