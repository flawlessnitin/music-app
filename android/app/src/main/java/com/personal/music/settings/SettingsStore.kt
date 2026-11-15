package com.personal.music.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Credentials(val keyId: String, val applicationKey: String, val bucket: String)

enum class SyncOutcome {
    UP_TO_DATE,
    DOWNLOADED,
    WAITING,
    BAD_KEY,
    NO_NETWORK,
    STORAGE_FULL,
    CAP_REACHED,
    NOT_SET_UP,
    ERROR
}

data class SyncSummary(
    val finishedAtMillis: Long,
    val outcome: SyncOutcome,
    val message: String,
    val pendingSongs: Int
)

interface SecretCipher {
    fun encrypt(plainText: String): String
    fun decrypt(cipherText: String): String
}

class AndroidKeystoreSecretCipher(
    private val keyAlias: String = "b2_credentials_key"
) : SecretCipher {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        if (!keyStore.containsAlias(keyAlias)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )
            val parameterSpec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(parameterSpec)
            return keyGenerator.generateKey()
        }
        val entry = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
        return entry?.secretKey
            ?: throw IllegalStateException("KeyStore entry is not a SecretKeyEntry")
    }

    override fun encrypt(plainText: String): String {
        val secretKey = getOrCreateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(1 + iv.size + encrypted.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(encrypted, 0, combined, 1 + iv.size, encrypted.size)
        return Base64.getEncoder().encodeToString(combined)
    }

    override fun decrypt(cipherText: String): String {
        val secretKey = getOrCreateKey()
        val combined = Base64.getDecoder().decode(cipherText)
        val ivSize = combined[0].toInt()
        val iv = ByteArray(ivSize)
        System.arraycopy(combined, 1, iv, 0, ivSize)
        val encryptedSize = combined.size - 1 - ivSize
        val encrypted = ByteArray(encryptedSize)
        System.arraycopy(combined, 1 + ivSize, encrypted, 0, encryptedSize)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val decrypted = cipher.doFinal(encrypted)
        return String(decrypted, Charsets.UTF_8)
    }
}

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsStore(
    private val context: Context,
    private val cipher: SecretCipher = AndroidKeystoreSecretCipher(),
    private val dataStore: DataStore<Preferences> = context.settingsDataStore
) {
    val credentialsFlow: Flow<Credentials?> = dataStore.data.map { prefs ->
        val keyId = prefs[KEY_ID] ?: return@map null
        val encryptedAppKey = prefs[KEY_APPLICATION_KEY] ?: return@map null
        val bucket = prefs[KEY_BUCKET] ?: return@map null
        if (keyId.isBlank() || encryptedAppKey.isBlank() || bucket.isBlank()) return@map null
        val appKey = try {
            cipher.decrypt(encryptedAppKey)
        } catch (e: Exception) {
            return@map null
        }
        Credentials(keyId = keyId, applicationKey = appKey, bucket = bucket)
    }

    suspend fun credentials(): Credentials? {
        val prefs = dataStore.data.first()
        val keyId = prefs[KEY_ID] ?: return null
        val encryptedAppKey = prefs[KEY_APPLICATION_KEY] ?: return null
        val bucket = prefs[KEY_BUCKET] ?: return null
        if (keyId.isBlank() || encryptedAppKey.isBlank() || bucket.isBlank()) return null
        val appKey = try {
            cipher.decrypt(encryptedAppKey)
        } catch (e: Exception) {
            return null
        }
        return Credentials(keyId = keyId, applicationKey = appKey, bucket = bucket)
    }

    suspend fun saveCredentials(c: Credentials) {
        val encryptedKey = cipher.encrypt(c.applicationKey)
        dataStore.edit { prefs ->
            prefs[KEY_ID] = c.keyId
            prefs[KEY_APPLICATION_KEY] = encryptedKey
            prefs[KEY_BUCKET] = c.bucket
        }
    }

    suspend fun clearCredentials() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_ID)
            prefs.remove(KEY_APPLICATION_KEY)
            prefs.remove(KEY_BUCKET)
        }
    }

    val wifiOnly: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_WIFI_ONLY] ?: true
    }

    suspend fun setWifiOnly(v: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_WIFI_ONLY] = v
        }
    }

    val capBytes: Flow<Long> = dataStore.data.map { prefs ->
        prefs[KEY_CAP_BYTES] ?: 20_000_000_000L
    }

    suspend fun setCapBytes(v: Long) {
        dataStore.edit { prefs ->
            prefs[KEY_CAP_BYTES] = v
        }
    }

    val lastSync: Flow<SyncSummary?> = dataStore.data.map { prefs ->
        val finishedAt = prefs[KEY_LAST_SYNC_FINISHED_AT] ?: return@map null
        val outcomeName = prefs[KEY_LAST_SYNC_OUTCOME] ?: return@map null
        val outcome = try {
            SyncOutcome.valueOf(outcomeName)
        } catch (e: IllegalArgumentException) {
            SyncOutcome.ERROR
        }
        val message = prefs[KEY_LAST_SYNC_MESSAGE] ?: ""
        val pending = prefs[KEY_LAST_SYNC_PENDING_SONGS] ?: 0
        SyncSummary(
            finishedAtMillis = finishedAt,
            outcome = outcome,
            message = message,
            pendingSongs = pending
        )
    }

    suspend fun setLastSync(s: SyncSummary) {
        dataStore.edit { prefs ->
            prefs[KEY_LAST_SYNC_FINISHED_AT] = s.finishedAtMillis
            prefs[KEY_LAST_SYNC_OUTCOME] = s.outcome.name
            prefs[KEY_LAST_SYNC_MESSAGE] = s.message
            prefs[KEY_LAST_SYNC_PENDING_SONGS] = s.pendingSongs
        }
    }

    companion object {
        val KEY_ID = stringPreferencesKey("b2_key_id")
        val KEY_APPLICATION_KEY = stringPreferencesKey("b2_application_key")
        val KEY_BUCKET = stringPreferencesKey("b2_bucket")
        val KEY_WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val KEY_CAP_BYTES = longPreferencesKey("cap_bytes")
        val KEY_LAST_SYNC_FINISHED_AT = longPreferencesKey("last_sync_finished_at")
        val KEY_LAST_SYNC_OUTCOME = stringPreferencesKey("last_sync_outcome")
        val KEY_LAST_SYNC_MESSAGE = stringPreferencesKey("last_sync_message")
        val KEY_LAST_SYNC_PENDING_SONGS = intPreferencesKey("last_sync_pending_songs")
    }
}
