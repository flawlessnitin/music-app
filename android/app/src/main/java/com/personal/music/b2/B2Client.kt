package com.personal.music.b2

import com.personal.music.settings.Credentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

sealed class B2Exception(message: String? = null, cause: Throwable? = null) : IOException(message, cause) {
    class BadKey(message: String = "Bad or missing key", cause: Throwable? = null) : B2Exception(message, cause)
    class Refused(val code: String, message: String = "Refused: $code", cause: Throwable? = null) : B2Exception(message, cause)
    class NotFound(message: String = "Not found", cause: Throwable? = null) : B2Exception(message, cause)
    class Network(cause: Throwable) : B2Exception(cause.message, cause)
}

@Serializable
private data class B2AuthorizeResponse(
    val authorizationToken: String,
    val apiInfo: B2ApiInfo
)

@Serializable
private data class B2ApiInfo(
    val storageApi: B2StorageApi
)

@Serializable
private data class B2StorageApi(
    val downloadUrl: String
)

@Serializable
private data class B2ErrorResponse(
    val code: String? = null,
    val message: String? = null,
    val status: Int? = null
)

class B2Client(
    private val http: OkHttpClient,
    private val credentials: suspend () -> Credentials?,
    private val authUrl: String = "https://api.backblazeb2.com/b2api/v3/b2_authorize_account"
) {
    private data class AuthState(
        val token: String,
        val downloadUrl: String,
        val bucket: String
    )

    private val authMutex = Mutex()
    @Volatile
    private var cachedAuth: AuthState? = null

    private val json = Json { ignoreUnknownKeys = true }

    fun invalidate() {
        cachedAuth = null
    }

    private suspend fun getAuth(forceRefresh: Boolean = false): AuthState {
        if (!forceRefresh) {
            cachedAuth?.let { return it }
        }
        return authMutex.withLock {
            val cached = if (forceRefresh) null else cachedAuth
            cached ?: run {
                val creds = credentials() ?: throw B2Exception.BadKey("No credentials configured")
                if (creds.keyId.isBlank() || creds.applicationKey.isBlank() || creds.bucket.isBlank()) {
                    throw B2Exception.BadKey("Credentials are empty")
                }
                // Blocking network call: keep it off the caller's thread.
                val newAuth = withContext(Dispatchers.IO) { authorize(creds) }
                cachedAuth = newAuth
                newAuth
            }
        }
    }

    private fun authorize(creds: Credentials): AuthState {
        val basicAuth = "Basic " + Base64.getEncoder().encodeToString(
            "${creds.keyId}:${creds.applicationKey}".toByteArray(Charsets.UTF_8)
        )
        val request = Request.Builder()
            .url(authUrl)
            .get()
            .header("Authorization", basicAuth)
            .build()

        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw if (e is B2Exception) e else B2Exception.Network(e)
        }

        val body = response.body.string()
        if (!response.isSuccessful) {
            handleError(response.code, body)
        }

        return try {
            val authResp = json.decodeFromString<B2AuthorizeResponse>(body)
            AuthState(
                token = authResp.authorizationToken,
                downloadUrl = authResp.apiInfo.storageApi.downloadUrl.trimEnd('/'),
                bucket = creds.bucket
            )
        } catch (e: Exception) {
            if (e is B2Exception) throw e
            throw IOException("Failed to parse B2 authorize response: ${e.message}", e)
        }
    }

    private fun encodePathSegment(segment: String): String {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
    }

    private fun buildDownloadUrl(downloadUrl: String, bucket: String, name: String): String {
        val encodedBucket = encodePathSegment(bucket)
        val encodedName = name.split("/").joinToString("/") { encodePathSegment(it) }
        return "${downloadUrl.trimEnd('/')}/file/$encodedBucket/$encodedName"
    }

    suspend fun open(name: String): Response = withContext(Dispatchers.IO) { openBlocking(name) }

    private suspend fun openBlocking(name: String): Response {
        var auth = getAuth()
        val url = buildDownloadUrl(auth.downloadUrl, auth.bucket, name)

        var request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", auth.token)
            .build()

        var response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw if (e is B2Exception) e else B2Exception.Network(e)
        }

        if (response.code == 401) {
            response.close()
            invalidate()
            auth = getAuth(forceRefresh = true)
            val retryUrl = buildDownloadUrl(auth.downloadUrl, auth.bucket, name)
            val retryRequest = Request.Builder()
                .url(retryUrl)
                .get()
                .header("Authorization", auth.token)
                .build()

            response = try {
                http.newCall(retryRequest).execute()
            } catch (e: IOException) {
                throw if (e is B2Exception) e else B2Exception.Network(e)
            }
        }

        if (!response.isSuccessful) {
            val body = response.body.string()
            handleError(response.code, body, name)
        }

        return response
    }

    suspend fun readText(name: String): String {
        val response = open(name)
        return withContext(Dispatchers.IO) {
            try {
                response.use { res ->
                    res.body.string()
                }
            } catch (e: IOException) {
                throw if (e is B2Exception) e else B2Exception.Network(e)
            }
        }
    }

    suspend fun streamUrl(name: String): String {
        val auth = getAuth()
        return buildDownloadUrl(auth.downloadUrl, auth.bucket, name)
    }

    suspend fun authHeader(): String {
        return getAuth().token
    }

    private fun handleError(code: Int, body: String, name: String? = null): Nothing {
        val errorResponse = try {
            json.decodeFromString<B2ErrorResponse>(body)
        } catch (e: Exception) {
            null
        }
        val errCode = errorResponse?.code ?: ""

        when {
            code == 401 -> throw B2Exception.BadKey("Unauthorized: $body")
            code == 404 -> throw B2Exception.NotFound(errCode.ifEmpty { "File not found: ${name ?: ""}" })
            code == 403 -> {
                if (errCode in listOf("download_cap_exceeded", "transaction_cap_exceeded", "storage_cap_exceeded")) {
                    throw B2Exception.Refused(errCode)
                } else {
                    // Any other 403 (for example code "access_denied" or "unauthorized") must be reported as BadKey
                    throw B2Exception.BadKey("Access denied: $errCode")
                }
            }
            code == 429 -> throw B2Exception.Refused(errCode.ifEmpty { "rate_limited" })
            code == 503 -> throw B2Exception.Refused(errCode.ifEmpty { "service_unavailable" })
            else -> throw IOException("B2 request failed with HTTP $code: $body")
        }
    }
}
