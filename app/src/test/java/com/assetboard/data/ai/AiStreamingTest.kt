package com.assetboard.data.ai

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.assetboard.data.SettingsStore
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AiStreamingTest {
    private fun service(server: HttpServer): AiService {
        val settings = SettingsStore(ApplicationProvider.getApplicationContext())
        settings.setBool(settings.networkEnabled, true)
        settings.setAiApiKey("test-key")
        settings.setAiBaseUrl("http://127.0.0.1:${server.address.port}/v1")
        return AiService(settings)
    }

    @Test fun textArrivesBeforeTheResponseFinishes() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val release = CountDownLatch(1)
        var streaming = false
        server.createContext("/v1/chat/completions") { e ->
            streaming = Json.parseToJsonElement(e.requestBody.readBytes().decodeToString()).jsonObject["stream"]?.jsonPrimitive?.content == "true"
            e.responseHeaders.set("Content-Type", "text/event-stream")
            e.sendResponseHeaders(200, 0)
            e.responseBody.use { out ->
                out.write(": heartbeat\n\ndata: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n".toByteArray())
                out.flush()
                release.await(5, TimeUnit.SECONDS)
                out.write("data: {\"choices\":[{\"delta\":{\"content\":\"世界\"}}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".toByteArray())
            }
        }
        server.start()
        try {
            val first = CompletableDeferred<String>()
            val answer = async { service(server).stream("system", "user") { first.complete(it) } }
            assertEquals("你好", withTimeout(5_000) { first.await() })
            assertTrue(streaming)
            assertTrue(!answer.isCompleted)
            release.countDown()
            assertEquals("你好世界", withTimeout(5_000) { answer.await() })
        } finally { release.countDown(); server.stop(0) }
    }

    @Test fun aTruncatedStreamCannotBecomeASuccessfulEdit() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { e ->
            e.requestBody.close()
            e.sendResponseHeaders(200, 0)
            e.responseBody.use { it.write("data: {\"choices\":[{\"delta\":{\"content\":\"半段正文\"}}]}\n\n".toByteArray()) }
        }
        server.start()
        try {
            val error = runCatching { service(server).complete("system", "user") }.exceptionOrNull()
            assertTrue(error is AiException)
            assertTrue(error!!.message.orEmpty().contains("中断"))
        } finally { server.stop(0) }
    }

    @Test fun cancellingStopsAReadWhileTheServerIsStillWaiting() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val release = CountDownLatch(1)
        server.createContext("/v1/chat/completions") { e ->
            e.requestBody.close()
            e.sendResponseHeaders(200, 0)
            try {
                e.responseBody.use {
                    it.write("data: {\"choices\":[{\"delta\":{\"content\":\"开头\"}}]}\n\n".toByteArray())
                    it.flush()
                    release.await(5, TimeUnit.SECONDS)
                }
            } catch (_: java.io.IOException) { /* The cancelled client closes its connection. */ }
        }
        server.start()
        try {
            val first = CompletableDeferred<Unit>()
            val request = async { service(server).stream("system", "user") { first.complete(Unit) } }
            withTimeout(5_000) { first.await() }
            withTimeout(2_000) { request.cancelAndJoin() }
            assertTrue(request.isCancelled)
        } finally { release.countDown(); server.stop(0) }
    }
}
