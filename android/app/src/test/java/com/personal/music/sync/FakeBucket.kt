package com.personal.music.sync

import com.personal.music.b2.B2Client
import com.personal.music.settings.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import java.net.URLDecoder
import java.util.Collections

/** A stand-in for Backblaze B2: the authorize call plus file downloads from a map. */
class FakeBucket : Dispatcher() {
    val server = MockWebServer()
    val files = mutableMapOf<String, ByteArray>()

    /** Names of files requested, in order. */
    val requested: MutableList<String> = Collections.synchronizedList(mutableListOf())
    @Volatile var authorizeCalls = 0

    @Volatile var badKey = false
    /** Song downloads allowed before the bucket answers "download cap exceeded". */
    @Volatile var songDownloadsBeforeRefusal: Int? = null
    /** Files whose body is cut off half-way. */
    @Volatile var cutBodyOf: Set<String> = emptySet()

    private var songDownloads = 0

    init {
        server.dispatcher = this
        server.start()
    }

    fun client(credentials: suspend () -> Credentials? = { Credentials("id", "key", BUCKET) }) = B2Client(
        http = OkHttpClient(),
        credentials = credentials,
        authUrl = server.url("/b2api/v3/b2_authorize_account").toString(),
    )

    fun songRequests(): List<String> = requested.filter { it.startsWith("songs/") }

    fun stop() = server.shutdown()

    @Synchronized
    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        if (path.startsWith("/b2api/")) {
            authorizeCalls++
            if (badKey) return error(401, "bad_auth_token")
            val base = server.url("/").toString().removeSuffix("/")
            return MockResponse().setBody(
                """{"accountId":"a","authorizationToken":"tok","apiInfo":{"storageApi":{"downloadUrl":"$base"}}}"""
            )
        }
        if (request.getHeader("Authorization") != "tok") return error(401, "bad_auth_token")

        val name = URLDecoder.decode(path.removePrefix("/file/$BUCKET/"), "UTF-8")
        requested += name
        val body = files[name] ?: return error(404, "not_found")

        if (name.startsWith("songs/")) {
            val limit = songDownloadsBeforeRefusal
            if (limit != null && songDownloads >= limit) return error(403, "download_cap_exceeded")
            songDownloads++
        }
        val response = MockResponse().setBody(Buffer().write(body))
        if (name in cutBodyOf) response.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        return response
    }

    private fun error(status: Int, code: String) = MockResponse()
        .setResponseCode(status)
        .setBody("""{"code":"$code","message":"","status":$status}""")

    companion object {
        const val BUCKET = "bucket"
    }
}
