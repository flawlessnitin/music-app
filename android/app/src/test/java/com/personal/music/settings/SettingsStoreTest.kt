package com.personal.music.settings

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {

    private lateinit var context: Context
    private lateinit var fakeCipher: SecretCipher
    private lateinit var store: SettingsStore

    private class XorSecretCipher(private val mask: Byte = 0x5A) : SecretCipher {
        override fun encrypt(plainText: String): String {
            val bytes = plainText.toByteArray(Charsets.UTF_8)
            val xor = bytes.map { (it.toInt() xor mask.toInt()).toByte() }.toByteArray()
            return Base64.getEncoder().encodeToString(xor)
        }

        override fun decrypt(cipherText: String): String {
            val bytes = Base64.getDecoder().decode(cipherText)
            val xor = bytes.map { (it.toInt() xor mask.toInt()).toByte() }.toByteArray()
            return String(xor, Charsets.UTF_8)
        }
    }

    @Before
    fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        fakeCipher = XorSecretCipher()
        store = SettingsStore(context, cipher = fakeCipher)
        store.clearCredentials()
    }

    @Test
    fun `credentials round trip and are not stored in plain text`() = runTest {
        val plainAppKey = "super-secret-b2-app-key-12345"
        val credentials = Credentials(
            keyId = "0059f2c1a7b3d4e0000000001",
            applicationKey = plainAppKey,
            bucket = "my-music-bucket"
        )

        store.saveCredentials(credentials)

        // 1. Credentials round trip correctly
        val loaded = store.credentials()
        assertNotNull(loaded)
        assertEquals("0059f2c1a7b3d4e0000000001", loaded?.keyId)
        assertEquals(plainAppKey, loaded?.applicationKey)
        assertEquals("my-music-bucket", loaded?.bucket)

        // 2. Read raw DataStore preference values to prove the plain application key text is absent
        val rawPrefs = context.settingsDataStore.data.first()
        val storedAppKeyVal = rawPrefs[SettingsStore.KEY_APPLICATION_KEY]
        assertNotNull(storedAppKeyVal)
        assertNotEquals(plainAppKey, storedAppKeyVal)
        assertFalse(storedAppKeyVal!!.contains(plainAppKey))

        // Check every preference stored: plainAppKey must not appear in any value
        rawPrefs.asMap().values.forEach { prefValue ->
            assertFalse(
                "Stored preference contains plain application key: $prefValue",
                prefValue.toString().contains(plainAppKey)
            )
        }
    }

    @Test
    fun `defaults are wifi only on and a 20 GB cap`() = runTest {
        assertEquals(true, store.wifiOnly.first())
        assertEquals(20_000_000_000L, store.capBytes.first())
        assertNull(store.lastSync.first())
    }

    @Test
    fun `clearCredentials removes them`() = runTest {
        val credentials = Credentials(
            keyId = "keyId123",
            applicationKey = "appKey123",
            bucket = "bucket123"
        )

        store.saveCredentials(credentials)
        assertNotNull(store.credentials())

        store.clearCredentials()
        assertNull(store.credentials())
    }

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
}
