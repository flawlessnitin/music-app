package com.personal.music.b2

import com.personal.music.settings.Credentials
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.Base64

class B2ClientTest {

    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient

    private val defaultCredentials = Credentials(
        keyId = "005testKeyId",
        applicationKey = "K005testAppKey",
        bucket = "test-music-bucket"
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        httpClient = OkHttpClient.Builder().build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun authSuccessJson(
        downloadUrl: String = server.url("/").toString().removeSuffix("/"),
        token: String = "auth-token-42"
    ): String = """
        {
          "accountId": "acc005",
          "authorizationToken": "$token",
          "apiInfo": {
            "storageApi": {
              "apiUrl": "https://api005.backblazeb2.com",
              "downloadUrl": "$downloadUrl",
              "s3ApiUrl": "https://s3.us-west-005.backblazeb2.com",
              "capabilities": ["readFiles"],
              "bucketId": null,
              "bucketName": null,
              "namePrefix": null,
              "absoluteMinimumPartSize": 5000000,
              "recommendedPartSize": 100000000,
              "infoType": "storageApi"
            }
          },
          "applicationKeyExpirationTimestamp": null
        }
    """.trimIndent()

    private fun createClient(
        credentialsProvider: suspend () -> Credentials? = { defaultCredentials }
    ): B2Client {
        return B2Client(
            http = httpClient,
            credentials = credentialsProvider,
            authUrl = server.url("/b2api/v3/b2_authorize_account").toString()
        )
    }

    @Test
    fun `authorizes with basic auth then downloads with the token`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(authSuccessJson(token = "valid-token-123")))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"version":1,"songs":[]}"""))

        val client = createClient()
        val text = client.readText("catalog.json")
        assertEquals("""{"version":1,"songs":[]}""", text)

        assertEquals(2, server.requestCount)

        // 1. Authorization request
        val authRequest = server.takeRequest()
        assertEquals("/b2api/v3/b2_authorize_account", authRequest.path)
        assertEquals("GET", authRequest.method)
        val expectedBasic = "Basic " + Base64.getEncoder().encodeToString(
            "${defaultCredentials.keyId}:${defaultCredentials.applicationKey}".toByteArray(Charsets.UTF_8)
        )
        assertEquals(expectedBasic, authRequest.getHeader("Authorization"))

        // 2. Download request
        val downloadRequest = server.takeRequest()
        assertEquals("/file/test-music-bucket/catalog.json", downloadRequest.path)
        assertEquals("GET", downloadRequest.method)
        // Authorization token with no Bearer prefix
        assertEquals("valid-token-123", downloadRequest.getHeader("Authorization"))
    }

    @Test
    fun `reuses the token across downloads`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(authSuccessJson(token = "reused-token")))
        server.enqueue(MockResponse().setResponseCode(200).setBody("song-one-bytes"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("song-two-bytes"))

        val client = createClient()

        // Two downloads in sequence and/or concurrent
        val download1 = async { client.readText("songs/1.opus") }
        val download2 = async { client.readText("songs/2.opus") }

        assertEquals("song-one-bytes", download1.await())
        assertEquals("song-two-bytes", download2.await())

        // Exactly 1 authorize request and 2 download requests
        assertEquals(3, server.requestCount)

        val req1 = server.takeRequest() // Auth
        assertEquals("/b2api/v3/b2_authorize_account", req1.path)

        val req2 = server.takeRequest() // Download 1
        assertEquals("reused-token", req2.getHeader("Authorization"))

        val req3 = server.takeRequest() // Download 2
        assertEquals("reused-token", req3.getHeader("Authorization"))
    }

    @Test
    fun `expired token re-authorizes once and retries`() = runTest {
        // First authorize returns token-1
        server.enqueue(MockResponse().setResponseCode(200).setBody(authSuccessJson(token = "expired-token")))
        // First download attempt gets 401
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"code":"expired_auth_token","message":"Token expired","status":401}""")
        )
        // Re-authorize returns token-2
        server.enqueue(MockResponse().setResponseCode(200).setBody(authSuccessJson(token = "fresh-token")))
        // Retried download succeeds
        server.enqueue(MockResponse().setResponseCode(200).setBody("success-after-retry"))

        val client = createClient()
        val content = client.readText("songs/retry.opus")
        assertEquals("success-after-retry", content)

        assertEquals(4, server.requestCount)
        server.takeRequest() // Initial auth
        val initialDownload = server.takeRequest()
        assertEquals("expired-token", initialDownload.getHeader("Authorization"))
        server.takeRequest() // Re-auth
        val retriedDownload = server.takeRequest()
        assertEquals("fresh-token", retriedDownload.getHeader("Authorization"))
    }

    @Test
    fun `bad key gives BadKey`() = runTest {
        // Case 1: 401 during authorize itself
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"code":"bad_auth_token","message":"bad key","status":401}""")
        )

        val client = createClient()
        try {
            client.readText("catalog.json")
            fail("Expected BadKey on 401 from authorize")
        } catch (e: B2Exception.BadKey) {
            // Expected
        }

        // Case 2: 403 access_denied or unauthorized on download
        server.enqueue(MockResponse().setResponseCode(200).setBody(authSuccessJson(token = "tok-403")))
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"code":"access_denied","message":"Unauthorized access","status":403}""")
        )

        client.invalidate()
        try {
            client.readText("catalog.json")
            fail("Expected BadKey on 403 access_denied from download")
        } catch (e: B2Exception.BadKey) {
            // Expected
        }
    }

    @Test
    fun `cap exceeded rate limit and 503 give Refused`() = runTest {
        val client = createClient()

        // 1. Cap exceeded (403 download_cap_exceeded)
        server.enqueue(MockResponse().setResponseCode(200).setBody(authSuccessJson()))
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"code":"download_cap_exceeded","message":"Daily cap reached","status":403}""")
        )
        try {
            client.readText("songs/capped.opus")
            fail("Expected Refused for download_cap_exceeded")
        } catch (e: B2Exception.Refused) {
            assertEquals("download_cap_exceeded", e.code)
        }

        // 2. Rate limit (429)
        server.enqueue(MockResponse().setResponseCode(200).setBody(authSuccessJson()))
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody("""{"code":"rate_limited","message":"Too many requests","status":429}""")
        )
        client.invalidate()
        try {
            client.readText("songs/rate.opus")
            fail("Expected Refused for 429")
        } catch (e: B2Exception.Refused) {
            assertEquals("rate_limited", e.code)
        }

        // 3. Service overload (503)
        server.enqueue(MockResponse().setResponseCode(200).setBody(authSuccessJson()))
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setBody("""{"code":"service_unavailable","message":"Server overloaded","status":503}""")
        )
        client.invalidate()
        try {
            client.readText("songs/overload.opus")
            fail("Expected Refused for 503")
        } catch (e: B2Exception.Refused) {
            assertEquals("service_unavailable", e.code)
        }
    }

    @Test
    fun `missing file gives NotFound`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(authSuccessJson()))
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("""{"code":"not_found","message":"File does not exist","status":404}""")
        )

        val client = createClient()
        try {
            client.readText("songs/missing.opus")
            fail("Expected NotFound for 404")
        } catch (e: B2Exception.NotFound) {
            // Expected
        }
    }

    @Test
    fun `connection failure gives Network`() = runTest {
        // Point to an address that refuses connection
        val deadUrl = "http://127.0.0.1:59999/b2api/v3/b2_authorize_account"
        val client = B2Client(
            http = httpClient,
            credentials = { defaultCredentials },
            authUrl = deadUrl
        )

        try {
            client.readText("catalog.json")
            fail("Expected Network exception for connection failure")
        } catch (e: B2Exception.Network) {
            assertTrue(e.cause is IOException)
        }
    }

    @Test
    fun `no saved credentials gives BadKey without any request`() = runTest {
        val client = createClient(credentialsProvider = { null })

        try {
            client.readText("catalog.json")
            fail("Expected BadKey when credentials are null")
        } catch (e: B2Exception.BadKey) {
            // Expected
        }

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `song names are url encoded in the download path`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(authSuccessJson(token = "stream-token")))
        server.enqueue(MockResponse().setResponseCode(200).setBody("media-bytes"))

        val client = createClient()
        val complexName = "songs/Artist & Band - Song #1 [Remix] (2024)?.opus"

        val response = client.open(complexName)
        response.close()

        server.takeRequest() // Auth
        val downloadRequest = server.takeRequest()

        // Path segments should be encoded, but '/' separators preserved
        val expectedPath = "/file/test-music-bucket/songs/Artist%20%26%20Band%20-%20Song%20%231%20%5BRemix%5D%20%282024%29%3F.opus"
        assertEquals(expectedPath, downloadRequest.path)

        // Also check streamUrl and authHeader for player use
        val expectedStreamUrl = "${server.url("/").toString().removeSuffix("/")}$expectedPath"
        assertEquals(expectedStreamUrl, client.streamUrl(complexName))
        assertEquals("stream-token", client.authHeader())
    }
}
