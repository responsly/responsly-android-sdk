package com.responsly.sdk.internal.net

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The HTTP client against a real local server. */
class HttpSdkApiTest {
    private class Recorded(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private lateinit var server: HttpServer
    private val requests = ArrayList<Recorded>()
    private var respond: (HttpExchange) -> Unit = { it.send(204) }

    private val host: String get() = "http://127.0.0.1:${server.address.port}"

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val headers = exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            synchronized(requests) {
                requests.add(Recorded(exchange.requestMethod, exchange.requestURI.toString(), headers, body))
            }
            respond(exchange)
            exchange.close()
        }
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun HttpExchange.send(status: Int, body: String? = null, etag: String? = null) {
        etag?.let { responseHeaders.add("ETag", it) }
        if (body == null) {
            sendResponseHeaders(status, -1)
        } else {
            val bytes = body.toByteArray(Charsets.UTF_8)
            responseHeaders.add("Content-Type", "application/json")
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
    }

    private fun api(apiHost: String = host) = HttpSdkApi(apiHost, "rsk_test_key", "com.acme.app", "1.0.0")

    @Test
    fun fetchesTheConfigurationWithTheProtocolHeaders() {
        respond = { it.send(200, """{"surveys":[],"name":"zażółć"}""", etag = "\"v1\"") }
        val response = api().fetchConfig(null) as ConfigResponse.Ok
        assertEquals("""{"surveys":[],"name":"zażółć"}""", response.body)
        assertEquals("\"v1\"", response.etag)

        val request = requests.single()
        assertEquals("GET", request.method)
        assertEquals("/sdk/v1/config", request.path)
        assertEquals("rsk_test_key", request.headers["x-responsly-sdk-key"])
        assertEquals("android", request.headers["x-responsly-platform"])
        assertEquals("1.0.0", request.headers["x-responsly-sdk-version"])
        assertEquals("com.acme.app", request.headers["x-responsly-app-id"])
        assertEquals("application/json", request.headers["accept"])
        assertNull(request.headers["if-none-match"])
        // No device model or OS build in the user agent.
        assertEquals("ResponslySDK/1.0.0 (android)", request.headers["user-agent"])
    }

    @Test
    fun sendsTheEtagAndUnderstandsNotModified() {
        respond = { it.send(304) }
        assertTrue(api().fetchConfig("\"v1\"") is ConfigResponse.NotModified)
        assertEquals("\"v1\"", requests.single().headers["if-none-match"])
    }

    @Test
    fun unauthorizedMeansAnInvalidKey() {
        respond = { it.send(401, """{"error":"invalid_sdk_key"}""") }
        assertTrue(api().fetchConfig(null) is ConfigResponse.InvalidKey)
    }

    @Test
    fun otherStatusesAndNetworkErrorsAreFailures() {
        respond = { it.send(503, "busy") }
        assertEquals("HTTP 503", (api().fetchConfig(null) as ConfigResponse.Failed).reason)
        respond = { it.send(404) }
        assertTrue(api().fetchConfig(null) is ConfigResponse.Failed)

        server.stop(0)
        assertTrue(api().fetchConfig(null) is ConfigResponse.Failed)
        assertTrue(api("not a url").fetchConfig(null) is ConfigResponse.Failed)
        assertTrue(api("ftp://example.invalid").fetchConfig(null) is ConfigResponse.Failed)
    }

    @Test
    fun aTrailingSlashInTheHostIsHarmless() {
        respond = { it.send(200, "{}") }
        assertTrue(api("$host/").fetchConfig(null) is ConfigResponse.Ok)
        assertEquals("/sdk/v1/config", requests.single().path)
    }

    @Test
    fun postsEvents() {
        val body = """{"events":[{"type":"displayed","survey_id":"aBcD1234"}]}"""
        assertEquals(PostResult.SENT, api().postEvents(body))

        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("/sdk/v1/events", request.path)
        assertEquals(body, request.body)
        assertTrue(request.headers.getValue("content-type").startsWith("application/json"))
        assertEquals("rsk_test_key", request.headers["x-responsly-sdk-key"])
        assertEquals("android", request.headers["x-responsly-platform"])
        assertEquals("com.acme.app", request.headers["x-responsly-app-id"])
    }

    @Test
    fun classifiesTheAnswerToAPost() {
        fun result(status: Int): PostResult {
            respond = { it.send(status) }
            return api().postEvents("{}")
        }
        assertEquals(PostResult.SENT, result(204))
        assertEquals(PostResult.SENT, result(200))
        assertEquals(PostResult.REJECTED, result(400))
        assertEquals(PostResult.REJECTED, result(401))
        assertEquals(PostResult.REJECTED, result(422))
        assertEquals(PostResult.RETRY, result(429))
        assertEquals(PostResult.RETRY, result(500))
        assertEquals(PostResult.RETRY, result(503))
        server.stop(0)
        assertEquals(PostResult.RETRY, api().postEvents("{}"))
    }
}
