package com.assetboard.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SyncApiTest {
    private data class Recorded(
        val method: String,
        val path: String,
        val headers: Map<String, List<String>>,
        val body: String,
    ) {
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    }

    private class FakeServer(private val respond: (HttpExchange) -> Unit) {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val executor: ExecutorService = Executors.newFixedThreadPool(2)
        val requests = CopyOnWriteArrayList<Recorded>()

        init {
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.readBytes().decodeToString()
                requests += Recorded(exchange.requestMethod, exchange.requestURI.path, exchange.requestHeaders, body)
                respond(exchange)
            }
            server.executor = executor
            server.start()
        }

        val url: String get() = "http://127.0.0.1:${server.address.port}"

        fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun HttpExchange.reply(status: Int, body: String = "") {
        val bytes = body.toByteArray()
        responseHeaders.set("Content-Type", "application/json")
        if (bytes.isEmpty()) {
            sendResponseHeaders(status, -1)
        } else {
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
    }

    private fun withServer(respond: (HttpExchange) -> Unit, block: (FakeServer) -> Unit) {
        val server = FakeServer(respond)
        try {
            block(server)
        } finally {
            server.close()
        }
    }

    @Test fun probeMeasuresTheHealthEndpoint() = withServer(
        { it.reply(200, """{"status":"ok","version":"0.1.0"}""") },
    ) { server ->
        val latency = runBlocking { SyncApi().probe(server.url) }
        assertTrue(latency >= 0)
        val request = server.requests.single()
        assertEquals("GET", request.method)
        assertEquals("/healthz", request.path)
    }

    @Test fun probeRejectsAnAddressThatIsNotThisServer() = withServer(
        { it.reply(200, "<html><body>some other site</body></html>") },
    ) { server ->
        try {
            runBlocking { SyncApi().probe(server.url) }
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertTrue(e.message!!.contains("不是日盈服务器"))
        }
    }

    @Test fun loginParsesTheSessionAndSendsTheCredentials() = withServer(
        { it.reply(200, """{"accessToken":"access","refreshToken":"refresh","expiresIn":900,"user":{"id":"u1","username":"alice"}}""") },
    ) { server ->
        val session = runBlocking { SyncApi().login(server.url, "alice", "password") }
        assertEquals("access", session.accessToken)
        assertEquals("refresh", session.refreshToken)
        assertEquals("alice", session.user.username)
        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/auth/login", request.path)
        assertTrue(request.body.contains("\"username\":\"alice\""))
        assertTrue(request.body.contains("\"password\":\"password\""))
    }

    @Test fun uploadSendsIfMatchAndContentHash() = withServer(
        { it.reply(200, """{"revision":4}""") },
    ) { server ->
        val hash = "ab".repeat(32)
        val revision = runBlocking {
            SyncApi().upload(server.url, "token", 3, """{"formatVersion":14,"schemaVersion":13}""", hash)
        }
        assertEquals(4L, revision)
        val request = server.requests.single()
        assertEquals("PUT", request.method)
        assertEquals("/v1/sync", request.path)
        assertEquals("Bearer token", request.header("Authorization"))
        assertEquals("3", request.header("If-Match"))
        assertEquals(hash, request.header("X-Content-Hash"))
        assertEquals("""{"formatVersion":14,"schemaVersion":13}""", request.body)
    }

    @Test fun conflictCarriesTheCurrentRevision() = withServer(
        { it.reply(409, """{"error":{"code":"conflict","message":"changed"},"revision":7}""") },
    ) { server ->
        try {
            runBlocking { SyncApi().upload(server.url, "token", 6, """{"formatVersion":14}""", "ab".repeat(32)) }
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(409, e.status)
            assertEquals(7L, e.revision)
            assertEquals("conflict", e.code)
        }
    }

    @Test fun metaParsesAndDownloadCanBeEmpty() = withServer(
        { exchange ->
            when (exchange.requestURI.path) {
                "/v1/sync/meta" -> exchange.reply(
                    200,
                    """{"revision":5,"contentHash":"abc","formatVersion":14,"schemaVersion":13,"size":42,"updatedAt":100}""",
                )
                "/v1/sync" -> exchange.reply(404, """{"error":{"code":"not_found","message":"none"}}""")
                else -> exchange.reply(404)
            }
        },
    ) { server ->
        val api = SyncApi()
        val meta = runBlocking { api.meta(server.url, "token") }
        assertEquals(5L, meta.revision)
        assertEquals("abc", meta.contentHash)
        assertEquals(42L, meta.size)
        assertNull(runBlocking { api.download(server.url, "token") })
    }

    @Test fun unauthorizedBecomesAFriendlyMessage() = withServer(
        { it.reply(401, """{"error":{"code":"unauthorized","message":"authentication required"}}""") },
    ) { server ->
        try {
            runBlocking { SyncApi().meta(server.url, "expired") }
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(401, e.status)
            assertEquals("登录已过期，请重新登录", e.message)
        }
    }

    @Test fun disabledNetworkMakesNoRequest() {
        val api = SyncApi { false }
        try {
            runBlocking { api.login("https://example.com", "alice", "password") }
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals("已关闭联网，无法同步", e.message)
        }
    }

    @Test fun changePasswordSendsBothPasswordsAndParsesTheNewSession() = withServer(
        { it.reply(200, """{"accessToken":"new-access","refreshToken":"new-refresh","expiresIn":900,"user":{"id":"u1","username":"alice"}}""") },
    ) { server ->
        val session = runBlocking {
            SyncApi().changePassword(server.url, "old-access", "old-password", "new-password-456")
        }
        assertEquals("new-access", session.accessToken)
        assertEquals("new-refresh", session.refreshToken)
        assertEquals("alice", session.user.username)
        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/auth/password", request.path)
        assertEquals("Bearer old-access", request.header("Authorization"))
        assertTrue(request.body.contains("\"oldPassword\":\"old-password\""))
        assertTrue(request.body.contains("\"newPassword\":\"new-password-456\""))
    }

    @Test fun aWrongOldPasswordGetsItsOwnMessage() = withServer(
        { it.reply(401, """{"error":{"code":"invalid_old_password","message":"old password is incorrect"}}""") },
    ) { server ->
        try {
            runBlocking { SyncApi().changePassword(server.url, "access", "wrong", "new-password-456") }
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(401, e.status)
            assertEquals("invalid_old_password", e.code)
            assertEquals("旧密码不正确", e.message)
        }
    }

    @Test fun plainHttpIsOnlyAllowedOnLoopback() {
        try {
            runBlocking { SyncApi().login("http://example.com", "alice", "password") }
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals("服务器地址必须使用 https", e.message)
        }
    }
}