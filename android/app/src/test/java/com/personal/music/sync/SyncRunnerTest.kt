package com.personal.music.sync

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.music.db.MusicDatabase
import com.personal.music.library.LibraryRepository
import com.personal.music.settings.Credentials
import com.personal.music.settings.SecretCipher
import com.personal.music.settings.SettingsStore
import com.personal.music.settings.SyncOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
class SyncRunnerTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var bucket: FakeBucket
    private lateinit var db: MusicDatabase
    private lateinit var library: LibraryRepository
    private lateinit var settings: SettingsStore
    private lateinit var songsDir: File
    private lateinit var coversDir: File
    private lateinit var storeScope: CoroutineScope

    private val net = object : NetworkState {
        override var connected = true
        override var metered = false
    }
    private var freeBytes = 40_000_000_000L

    private object PlainCipher : SecretCipher {
        override fun encrypt(plainText: String) = plainText.reversed()
        override fun decrypt(cipherText: String) = cipherText.reversed()
    }

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        bucket = FakeBucket()
        db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).allowMainThreadQueries().build()
        library = LibraryRepository(db)
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val prefsFile = File(tmp.newFolder("prefs"), "settings.preferences_pb")
        settings = SettingsStore(
            context,
            cipher = PlainCipher,
            dataStore = PreferenceDataStoreFactory.create(scope = storeScope) { prefsFile },
        )
        settings.saveCredentials(Credentials("id", "key", FakeBucket.BUCKET))
        songsDir = tmp.newFolder("songs")
        coversDir = tmp.newFolder("covers")
    }

    @After
    fun tearDown() {
        storeScope.cancel()
        db.close()
        bucket.stop()
    }

    private fun runner(
        downloader: Downloader = Downloader(bucket.client { settings.credentials() }, songsDir, coversDir),
    ) = SyncRunner(
        settings = settings,
        library = library,
        b2 = bucket.client { settings.credentials() },
        downloader = downloader,
        network = net,
        freeSpace = FreeSpace { freeBytes },
        now = { 1234L },
    )

    /** Puts songs "a", "b", ... in the bucket, each 1,000 bytes, with a catalog at [version]. */
    private fun publish(version: Int, vararg ids: String, sharedCover: String? = null) {
        val songs = ids.joinToString(",") { id ->
            val cover = sharedCover ?: "covers/$id.jpg"
            bucket.files["songs/$id.opus"] = ByteArray(1_000) { id[0].code.toByte() }
            bucket.files[cover] = byteArrayOf(9)
            """{"id":"$id","title":"Song $id","artist":"A","album":"B","albumArtist":"A","track":null,
               "disc":null,"year":null,"durationMs":1000,"sizeBytes":1000,"format":"opus",
               "file":"songs/$id.opus","cover":"$cover"}"""
        }
        bucket.files["catalog.json"] =
            """{"version":$version,"updatedAt":"x","songs":[$songs]}""".toByteArray()
    }

    private fun unpublish(id: String) {
        bucket.files.remove("songs/$id.opus")
    }

    private fun songFiles() = songsDir.list()!!.sorted()
    private fun coverFiles() = coversDir.list()!!.sorted()

    @Test
    fun `first sync downloads every song and reports DOWNLOADED`() = runBlocking {
        publish(1, "a", "b", "c")

        val summary = runner().run()

        assertEquals(SyncOutcome.DOWNLOADED, summary.outcome)
        assertEquals(0, summary.pendingSongs)
        assertEquals(1234L, summary.finishedAtMillis)
        assertEquals(listOf("a.opus", "b.opus", "c.opus"), songFiles())
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), coverFiles())
        assertTrue(library.songs().first().all { it.downloaded })
        assertEquals(1, library.catalogVersion())
        assertEquals(summary, settings.lastSync.first())
    }

    @Test
    fun `unchanged catalog with everything downloaded makes one request and reports UP_TO_DATE`() = runBlocking {
        publish(1, "a", "b")
        runner().run()
        bucket.requested.clear()

        val summary = runner().run()

        assertEquals(SyncOutcome.UP_TO_DATE, summary.outcome)
        assertEquals(listOf("catalog.json"), bucket.requested.toList())
    }

    @Test
    fun `a song removed from the catalog loses its row file and unused cover`() = runBlocking {
        publish(1, "a", "b")
        runner().run()

        publish(2, "a")
        unpublish("b")
        val summary = runner().run()

        assertEquals(SyncOutcome.UP_TO_DATE, summary.outcome)
        assertNull(library.song("b"))
        assertEquals(listOf("a.opus"), songFiles())
        assertEquals(listOf("a.jpg"), coverFiles())
    }

    @Test
    fun `a cover shared with a remaining song is kept`() = runBlocking {
        publish(1, "a", "b", sharedCover = "covers/shared.jpg")
        runner().run()

        publish(2, "a", sharedCover = "covers/shared.jpg")
        runner().run()

        assertEquals(listOf("a.opus"), songFiles())
        assertEquals(listOf("shared.jpg"), coverFiles())
    }

    @Test
    fun `refusal part way keeps finished songs and reports WAITING with the pending count`() = runBlocking {
        publish(1, "a", "b", "c")
        bucket.songDownloadsBeforeRefusal = 1

        val summary = runner().run()

        assertEquals(SyncOutcome.WAITING, summary.outcome)
        assertEquals(2, summary.pendingSongs)
        assertEquals(1, songFiles().size)
        assertEquals(1, library.songs().first().count { it.downloaded })
    }

    @Test
    fun `the next run after a refusal downloads only what is left`() = runBlocking {
        publish(1, "a", "b", "c")
        bucket.songDownloadsBeforeRefusal = 1
        runner().run()
        val already = songFiles().single().removeSuffix(".opus")
        bucket.songDownloadsBeforeRefusal = null
        bucket.requested.clear()

        val summary = runner().run()

        assertEquals(SyncOutcome.DOWNLOADED, summary.outcome)
        assertEquals(0, summary.pendingSongs)
        assertEquals(listOf("a.opus", "b.opus", "c.opus"), songFiles())
        assertFalse("songs/$already.opus" in bucket.songRequests())
        assertEquals(2, bucket.songRequests().size)
    }

    @Test
    fun `bad key reports BAD_KEY and touches nothing on disk`() = runBlocking {
        publish(1, "a")
        runner().run()
        bucket.badKey = true
        publish(2, "a", "b")

        val summary = runner().run()

        assertEquals(SyncOutcome.BAD_KEY, summary.outcome)
        assertEquals(listOf("a.opus"), songFiles())
        assertEquals(1, library.catalogVersion())
        assertTrue(library.song("a")!!.downloaded)
    }

    @Test
    fun `metered connection with wifi only makes no request`() = runBlocking {
        publish(1, "a")
        net.metered = true

        val summary = runner().run()

        assertEquals(SyncOutcome.NO_NETWORK, summary.outcome)
        assertEquals(0, bucket.authorizeCalls)
        assertEquals(emptyList<String>(), bucket.requested.toList())
    }

    @Test
    fun `metered connection syncs once wifi only is switched off`() = runBlocking {
        publish(1, "a")
        net.metered = true
        settings.setWifiOnly(false)

        assertEquals(SyncOutcome.DOWNLOADED, runner().run().outcome)
    }

    @Test
    fun `no connection makes no request`() = runBlocking {
        publish(1, "a")
        net.connected = false

        assertEquals(SyncOutcome.NO_NETWORK, runner().run().outcome)
        assertEquals(0, bucket.authorizeCalls)
    }

    @Test
    fun `out of space reports STORAGE_FULL and leaves no partial file`() = runBlocking {
        publish(1, "a")
        val full = Downloader(bucket.client { settings.credentials() }, songsDir, coversDir, openOutput = { file ->
            file.createNewFile()
            object : OutputStream() {
                override fun write(b: Int) = throw IOException("write failed: ENOSPC (No space left on device)")
            }
        })

        val summary = runner(full).run()

        assertEquals(SyncOutcome.STORAGE_FULL, summary.outcome)
        assertEquals(1, summary.pendingSongs)
        assertEquals(emptyList<String>(), songFiles())
    }

    @Test
    fun `too little free space downloads nothing and reports STORAGE_FULL`() = runBlocking {
        publish(1, "a")
        freeBytes = 100_000_000

        val summary = runner().run()

        assertEquals(SyncOutcome.STORAGE_FULL, summary.outcome)
        assertEquals(emptyList<String>(), bucket.songRequests())
    }

    @Test
    fun `songs past the storage cap are left waiting and reported as CAP_REACHED`() = runBlocking {
        publish(1, "a", "b", "c")
        settings.setCapBytes(2_500)

        val summary = runner().run()

        assertEquals(SyncOutcome.CAP_REACHED, summary.outcome)
        assertEquals(1, summary.pendingSongs)
        assertEquals(2, songFiles().size)
    }

    @Test
    fun `a song whose file vanished is downloaded again`() = runBlocking {
        publish(1, "a", "b")
        runner().run()
        File(songsDir, "a.opus").delete()
        bucket.requested.clear()

        val summary = runner().run()

        assertEquals(SyncOutcome.DOWNLOADED, summary.outcome)
        assertEquals(listOf("songs/a.opus"), bucket.songRequests())
        assertEquals(listOf("a.opus", "b.opus"), songFiles())
    }

    @Test
    fun `priority song is downloaded first`() = runBlocking {
        publish(1, "a", "b", "c")

        runner().run(prioritySongId = "c")

        assertEquals("songs/c.opus", bucket.songRequests().first())
    }

    @Test
    fun `a song listed in the catalog but missing from the bucket is skipped`() = runBlocking {
        publish(1, "a", "b")
        unpublish("a")

        val summary = runner().run()

        assertEquals(listOf("b.opus"), songFiles())
        assertEquals(1, summary.pendingSongs)
    }

    @Test
    fun `a half written file from a killed sync is cleared and fetched again`() = runBlocking {
        publish(1, "a")
        File(songsDir, "a.opus.part").writeBytes(byteArrayOf(1, 2, 3))

        runner().run()

        assertEquals(listOf("a.opus"), songFiles())
        assertEquals(1_000L, File(songsDir, "a.opus").length())
    }

    @Test
    fun `no credentials reports NOT_SET_UP`() = runBlocking {
        settings.clearCredentials()

        val summary = runner().run()

        assertEquals(SyncOutcome.NOT_SET_UP, summary.outcome)
        assertEquals(0, bucket.authorizeCalls)
    }

    @Test
    fun `an empty bucket reports an error that says to add songs`() = runBlocking {
        val summary = runner().run()

        assertEquals(SyncOutcome.ERROR, summary.outcome)
        assertTrue(summary.message.contains("musictool"))
    }

    @Test
    fun `progress is reported per downloaded song`() = runBlocking {
        publish(1, "a", "b")
        val seen = mutableListOf<Pair<Int, Int>>()

        runner().run(onProgress = { done, total -> seen += done to total })

        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), seen)
    }
}
