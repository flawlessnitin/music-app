package com.personal.music.ui.settings

import android.content.Context
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: MusicDatabase
    private lateinit var library: LibraryRepository
    private lateinit var settings: SettingsStore
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        library = LibraryRepository(db)
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val prefsFile = File(tmp.newFolder("prefs"), "settings.preferences_pb")
        settings = SettingsStore(
            context,
            PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) { prefsFile },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun song(id: String, size: Long) = SongEntity(
        id = id, title = "T$id", artist = "A", album = "Al", albumArtist = "A",
        track = 1, disc = 1, year = 2024, durationMs = 1000, sizeBytes = size,
        format = "opus", file = "/music/$id.opus", cover = null,
    )

    private fun viewModel(scan: suspend () -> Int = { 0 }) =
        SettingsViewModel(settings, library, scan, coroutineScope = scope)

    @Test
    fun `shows how many songs are on the phone and how much space they take`() = runTest {
        library.replaceSongs(listOf(song("1", 1_000_000), song("2", 2_000_000)))

        val ui = viewModel().ui.filter { it.songs == 2 }.first()

        assertEquals(3_000_000L, ui.usedBytes)
    }

    @Test
    fun `the sound preset is saved and shown`() = runTest {
        val vm = viewModel()

        vm.setEqPreset(EqPreset.BASS).join()

        assertEquals(EqPreset.BASS, vm.ui.filter { it.eqPreset == EqPreset.BASS }.first().eqPreset)
        assertEquals(EqPreset.BASS, settings.eqPreset.first())
    }

    @Test
    fun `scanning reports how many songs were found`() = runTest {
        val vm = viewModel(scan = { 58 })

        vm.scan().join()

        val ui = vm.ui.filter { it.scanMessage != null }.first()
        assertEquals("Found 58 songs on this phone.", ui.scanMessage)
        assertEquals(false, ui.isScanning)
    }

    @Test
    fun `scanning an empty folder says so`() = runTest {
        val vm = viewModel(scan = { 0 })

        vm.scan().join()

        assertEquals("No songs found in that folder yet.", vm.ui.filter { it.scanMessage != null }.first().scanMessage)
    }

    @Test
    fun `a failing scan shows the reason instead of crashing`() = runTest {
        val vm = viewModel(scan = { error("disk unreadable") })

        vm.scan().join()

        assertTrue(vm.ui.filter { it.scanMessage != null }.first().scanMessage!!.contains("disk unreadable"))
    }
}
