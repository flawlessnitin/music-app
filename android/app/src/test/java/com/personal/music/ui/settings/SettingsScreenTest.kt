package com.personal.music.ui.settings

import android.content.Context
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.music.db.MusicDatabase
import com.personal.music.db.SongEntity
import com.personal.music.library.LibraryRepository
import com.personal.music.settings.Credentials
import com.personal.music.settings.SecretCipher
import com.personal.music.settings.SettingsStore
import com.personal.music.sync.FakeBucket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
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

    private lateinit var bucket: FakeBucket
    private lateinit var db: MusicDatabase
    private lateinit var library: LibraryRepository
    private lateinit var settings: SettingsStore
    private lateinit var storeScope: CoroutineScope
    private var syncNowCalls = 0

    private object PlainCipher : SecretCipher {
        override fun encrypt(plainText: String) = plainText.reversed()
        override fun decrypt(cipherText: String) = cipherText.reversed()
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        bucket = FakeBucket()
        db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        library = LibraryRepository(db)
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val prefsFile = File(tmp.newFolder("prefs"), "settings.preferences_pb")
        settings = SettingsStore(
            context,
            cipher = PlainCipher,
            dataStore = PreferenceDataStoreFactory.create(scope = storeScope) { prefsFile },
        )
        syncNowCalls = 0
    }

    @After
    fun tearDown() {
        storeScope.cancel()
        db.close()
        bucket.stop()
    }

    private fun createViewModel(): SettingsViewModel {
        return SettingsViewModel(
            settings = settings,
            library = library,
            b2 = bucket.client { settings.credentials() },
            onSyncNow = { syncNowCalls++ },
        )
    }

    @Test
    fun `application key is hidden until the show toggle`() {
        val viewModel = createViewModel()
        composeTestRule.setContent {
            SettingsScreen(viewModel = viewModel)
        }

        composeTestRule.onNodeWithContentDescription("Show application key").assertIsDisplayed()
        composeTestRule.onNodeWithTag("app_key_input").performTextInput("superSecretKey")
        composeTestRule.onNodeWithTag("app_key_input").assert(hasText("•".repeat(14)))

        composeTestRule.onNodeWithTag("toggle_key_visibility").performClick()
        composeTestRule.onNodeWithContentDescription("Hide application key").assertIsDisplayed()
        composeTestRule.onNodeWithTag("app_key_input").assert(hasText("superSecretKey"))
    }

    @Test
    fun `sync now is disabled until a key is saved`() {
        val viewModel = createViewModel()
        composeTestRule.setContent {
            SettingsScreen(viewModel = viewModel)
        }

        composeTestRule.onNodeWithText("Sync now").assertIsNotEnabled()

        runBlocking {
            settings.saveCredentials(Credentials("keyId", "appKey", "bucket"))
        }

        composeTestRule.waitUntil(5000) {
            try {
                composeTestRule.onNodeWithText("Sync now").assertIsEnabled()
                true
            } catch (e: AssertionError) {
                false
            }
        }
        composeTestRule.onNodeWithText("Sync now").assertIsEnabled()
    }

    @Test
    fun `shows used space and waiting count`() {
        runBlocking {
            db.songDao().insertOrUpdate(
                listOf(
                    SongEntity(
                        id = "1",
                        title = "Song 1",
                        artist = "Artist",
                        album = "Album",
                        albumArtist = "Artist",
                        track = 1,
                        disc = 1,
                        year = 2024,
                        durationMs = 1000,
                        sizeBytes = 1_500_000_000L,
                        format = "opus",
                        file = "songs/1.opus",
                        cover = null,
                        downloaded = true,
                    ),
                    SongEntity(
                        id = "2",
                        title = "Song 2",
                        artist = "Artist",
                        album = "Album",
                        albumArtist = "Artist",
                        track = 2,
                        disc = 1,
                        year = 2024,
                        durationMs = 1000,
                        sizeBytes = 500_000_000L,
                        format = "opus",
                        file = "songs/2.opus",
                        cover = null,
                        downloaded = false,
                    ),
                )
            )
        }

        val viewModel = createViewModel()
        composeTestRule.setContent {
            SettingsScreen(viewModel = viewModel)
        }

        composeTestRule.onNodeWithText("Used 1.5 GB of 20 GB, 1 songs on phone, 1 waiting")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
