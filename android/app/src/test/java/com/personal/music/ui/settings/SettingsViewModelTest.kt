package com.personal.music.ui.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.music.catalog.RemoteCatalog
import com.personal.music.catalog.RemoteSong
import com.personal.music.db.MusicDatabase
import com.personal.music.db.SongEntity
import com.personal.music.library.LibraryRepository
import com.personal.music.settings.Credentials
import com.personal.music.settings.SecretCipher
import com.personal.music.settings.SettingsStore
import com.personal.music.settings.SyncOutcome
import com.personal.music.settings.SyncSummary
import com.personal.music.sync.FakeBucket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    private fun createViewModel(scope: CoroutineScope, onScanLocal: (suspend () -> Int)? = null): SettingsViewModel {
        return SettingsViewModel(
            settings = settings,
            library = library,
            b2 = bucket.client { settings.credentials() },
            onSyncNow = { syncNowCalls++ },
            coroutineScope = scope,
            onScanLocal = onScanLocal,
        )
    }

    @Test
    fun `save and test stores the key and reports success when the catalog is readable`() = runTest {
        val catalogJson = """
            {"version": 1, "updatedAt": "2026-11-15T10:00:00Z", "songs": [
             {"id":"s1","title":"Song 1","artist":"Artist","album":"Album","albumArtist":"Artist",
              "track":1,"disc":1,"year":2024,"durationMs":1000,"sizeBytes":1000,"format":"opus",
              "file":"songs/s1.opus","cover":null},
             {"id":"s2","title":"Song 2","artist":"Artist","album":"Album","albumArtist":"Artist",
              "track":2,"disc":1,"year":2024,"durationMs":1000,"sizeBytes":1000,"format":"opus",
              "file":"songs/s2.opus","cover":null}
            ]}
        """.trimIndent()
        bucket.files["catalog.json"] = catalogJson.toByteArray(Charsets.UTF_8)

        val viewModel = createViewModel(backgroundScope)
        viewModel.save(" myKeyId ", " myAppKey ", " ${FakeBucket.BUCKET} ").join()

        val creds = settings.credentials()
        assertNotNull(creds)
        assertEquals("myKeyId", creds?.keyId)
        assertEquals("myAppKey", creds?.applicationKey)
        assertEquals(FakeBucket.BUCKET, creds?.bucket)

        val ui = viewModel.ui.filter { it.testMessage != null }.first()
        assertEquals("Connected. 2 songs in your library.", ui.testMessage)
        assertEquals(1, syncNowCalls)
    }

    @Test
    fun `save and test with a rejected key shows the BAD_KEY message and does not sync`() = runTest {
        bucket.badKey = true

        val viewModel = createViewModel(backgroundScope)
        viewModel.save("myKeyId", "myAppKey", "myBucket").join()

        val creds = settings.credentials()
        assertNotNull(creds)
        assertEquals("myKeyId", creds?.keyId)
        assertEquals("myAppKey", creds?.applicationKey)
        assertEquals("myBucket", creds?.bucket)

        val ui = viewModel.ui.filter { it.testMessage != null }.first()
        assertEquals(
            "Backblaze rejected the key. Check the key ID, key and bucket name.",
            ui.testMessage
        )
        assertEquals(0, syncNowCalls)
    }

    @Test
    fun `fields are trimmed before saving`() = runTest {
        val catalogJson = """{"version": 0, "updatedAt": "", "songs": []}"""
        bucket.files["catalog.json"] = catalogJson.toByteArray(Charsets.UTF_8)

        val viewModel = createViewModel(backgroundScope)
        viewModel.save("  trimmedId  ", "  trimmedKey  ", "  ${FakeBucket.BUCKET}  ").join()

        val creds = settings.credentials()
        assertNotNull(creds)
        assertEquals("trimmedId", creds?.keyId)
        assertEquals("trimmedKey", creds?.applicationKey)
        assertEquals(FakeBucket.BUCKET, creds?.bucket)

        viewModel.save("   ", "key", FakeBucket.BUCKET).join()
        val blankUi = viewModel.ui.filter { it.testMessage?.contains("blank", ignoreCase = true) == true }.first()
        assertTrue(blankUi.testMessage?.contains("blank", ignoreCase = true) == true)
    }

    @Test
    fun `each outcome maps to its message`() {
        assertEquals("Up to date.", outcomeMessage(SyncSummary(1000L, SyncOutcome.UP_TO_DATE, "", 0)))
        assertEquals(
            "Downloaded. Everything is on your phone.",
            outcomeMessage(SyncSummary(1000L, SyncOutcome.DOWNLOADED, "", 0))
        )
        assertEquals(
            "Backblaze paused downloads for now. 5 songs will download later.",
            outcomeMessage(SyncSummary(1000L, SyncOutcome.WAITING, "", 5))
        )
        assertEquals(
            "Backblaze rejected the key. Check the key ID, key and bucket name.",
            outcomeMessage(SyncSummary(1000L, SyncOutcome.BAD_KEY, "", 0))
        )
        assertEquals(
            "No Wi-Fi. Sync will run when Wi-Fi is back.",
            outcomeMessage(SyncSummary(1000L, SyncOutcome.NO_NETWORK, "", 0))
        )
        assertEquals(
            "Your phone is out of space. 3 songs are waiting.",
            outcomeMessage(SyncSummary(1000L, SyncOutcome.STORAGE_FULL, "", 3))
        )
        assertEquals(
            "Storage cap reached. Raise the cap to download 10 more songs.",
            outcomeMessage(SyncSummary(1000L, SyncOutcome.CAP_REACHED, "", 10))
        )
        assertEquals(
            "Add your Backblaze key to start.",
            outcomeMessage(SyncSummary(1000L, SyncOutcome.NOT_SET_UP, "", 0))
        )
        assertEquals(
            "Sync failed: timeout occurred",
            outcomeMessage(SyncSummary(1000L, SyncOutcome.ERROR, "timeout occurred", 0))
        )
    }

    @Test
    fun `cap slider stores gigabytes as bytes`() = runTest {
        val viewModel = createViewModel(backgroundScope)

        viewModel.setCapGb(20).join()
        assertEquals(20_000_000_000L, settings.capBytes.first())

        viewModel.setCapGb(0).join()
        assertEquals(1_000_000_000L, settings.capBytes.first())

        viewModel.setCapGb(50).join()
        assertEquals(40_000_000_000L, settings.capBytes.first())
    }

    @Test
    fun `scanning phone music reports how many songs were found`() = runTest {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val vm = createViewModel(scope, onScanLocal = { 58 })

        vm.scanLocal().join()

        val ui = vm.ui.filter { it.localMessage != null }.first()
        assertEquals("Found 58 songs on this phone.", ui.localMessage)
        assertEquals(false, ui.isScanningLocal)
        scope.cancel()
    }

    @Test
    fun `scanning an empty folder says so`() = runTest {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val vm = createViewModel(scope, onScanLocal = { 0 })

        vm.scanLocal().join()

        assertEquals("No songs found in that folder yet.", vm.ui.filter { it.localMessage != null }.first().localMessage)
        scope.cancel()
    }

    @Test
    fun `a failing scan shows the reason instead of crashing`() = runTest {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val vm = createViewModel(scope, onScanLocal = { error("disk unreadable") })

        vm.scanLocal().join()

        assertTrue(vm.ui.filter { it.localMessage != null }.first().localMessage!!.contains("disk unreadable"))
        scope.cancel()
    }
}
