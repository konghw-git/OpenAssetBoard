package com.assetboard.data.ai

import com.assetboard.data.SettingsStore
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The chat endpoint behind the AI editor helpers; [AiService] talks to Bailian, tests to a fake. */
interface AiGateway {
    /** The assistant's reply to one system+user exchange. Throws with a user-readable message. */
    suspend fun complete(system: String, user: String): String

    /**
     * The assistant's reply to a running conversation (user/assistant turns after the system).
     * The default folds the transcript into one [complete] call, which is all a plain chat
     * endpoint or a test fake needs; [AiService] sends the real message list.
     */
    suspend fun chat(system: String, history: List<ChatTurn>): String {
        val user = history.joinToString("\n\n") { turn ->
            if (turn.role == "assistant") "助手：${turn.content}" else turn.content
        }
        return complete(system, user)
    }

    /** Cumulative text as it arrives; the returned value is the complete reply. */
    suspend fun stream(system: String, user: String, onText: suspend (String) -> Unit): String =
        complete(system, user).also { onText(it) }

    suspend fun streamChat(system: String, history: List<ChatTurn>, onText: suspend (String) -> Unit): String =
        chat(system, history).also { onText(it) }

    /** A tiny exchange to prove the key, URL and model work; returns the model's greeting. */
    suspend fun testConnection(onText: suspend (String) -> Unit = {}): String = stream("只回复两个字：正常", "正常", onText)
}

/** One message of an agent conversation; [role] is "user" or "assistant". */
data class ChatTurn(val role: String, val content: String)

@Serializable
private data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ChatRequest(val model: String, val messages: List<ChatMessage>, val stream: Boolean)

@Serializable
private data class ChatChoice(val delta: ChatDelta? = null, val finish_reason: String? = null)

@Serializable
private data class ChatDelta(val content: String? = null)

@Serializable
private data class ChatResponse(val choices: List<ChatChoice> = emptyList(), val error: ApiErrorDetail? = null)

@Serializable
private data class ApiErrorBody(val error: ApiErrorDetail? = null)

@Serializable
private data class ApiErrorDetail(val message: String? = null)

/**
 * Chat completions against an OpenAI-compatible endpoint (Aliyun Bailian's compatible mode by
 * default). The key, base URL and model come from the device's [SettingsStore]; the key never
 * leaves the device except as the request's bearer token.
 */
class AiService(private val settings: SettingsStore) : AiGateway {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    override suspend fun complete(system: String, user: String): String =
        chat(system, listOf(ChatTurn("user", user)))

    override suspend fun chat(system: String, history: List<ChatTurn>): String = streamChat(system, history) {}

    override suspend fun stream(system: String, user: String, onText: suspend (String) -> Unit): String =
        streamChat(system, listOf(ChatTurn("user", user)), onText)

    override suspend fun streamChat(
        system: String, history: List<ChatTurn>, onText: suspend (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val key = settings.aiApiKey.value.trim()
        if (key.isEmpty()) throw AiException("未配置 API Key，请到设置里填写")
        if (!settings.networkEnabled.value) throw AiException("已关闭联网，请到设置里开启")
        val base = settings.aiBaseUrl.value.trim().trimEnd('/').ifEmpty { SettingsStore.DEFAULT_AI_BASE_URL }
        val body = json.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(
                settings.aiModel.value.trim().ifEmpty { SettingsStore.DEFAULT_AI_MODEL },
                listOf(ChatMessage("system", system)) + history.map { ChatMessage(it.role, it.content) },
                stream = true,
            ),
        )
        val call = client.newCall(Request.Builder()
            .url("$base/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build())
        // Disconnect a blocked read when its screen/job is cancelled, including before the first token.
        val disconnect = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw apiError(response.code, response.body.string())
                val answer = StringBuilder()
                var finished = false
                var done = false
                response.body.charStream().buffered().use { reader ->
                    val event = StringBuilder()
                    suspend fun consume() {
                        val data = event.toString().trim()
                        event.setLength(0)
                        if (data.isEmpty()) return
                        if (data == "[DONE]") { done = true; return }
                        val reply = try { json.decodeFromString(ChatResponse.serializer(), data) }
                        catch (e: Exception) { throw AiException("AI 服务返回了无法识别的流式内容", e) }
                        reply.error?.let {
                            throw AiException(it.message ?: "AI 服务中断了输出")
                        }
                        val choice = reply.choices.firstOrNull() ?: return
                        choice.delta?.content?.takeIf { it.isNotEmpty() }?.let {
                            answer.append(it)
                            onText(answer.toString())
                        }
                        if (choice.finish_reason != null) {
                            if (choice.finish_reason == "length") throw AiException("AI 输出达到长度限制，请缩短内容后重试")
                            if (choice.finish_reason == "content_filter") throw AiException("AI 服务未能完成这次输出")
                            finished = true
                        }
                    }
                    while (!done) {
                        currentCoroutineContext().ensureActive()
                        val line = reader.readLine() ?: break
                        when {
                            line.isEmpty() -> consume()
                            line.startsWith("data:") -> {
                                if (event.isNotEmpty()) event.append('\n')
                                event.append(line.substring(5).removePrefix(" "))
                            }
                        }
                    }
                    if (!done && event.isNotEmpty()) consume()
                }
                if (!done && !finished) throw AiException("AI 输出意外中断，请重试")
                answer.toString().trim('\r', '\n').takeIf { it.isNotEmpty() }
                    ?: throw AiException("AI 没有返回内容")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            throw AiException("网络连接失败，请检查网络", e)
        } finally {
            disconnect.cancelAndJoin()
            call.cancel()
        }
    }

    private fun apiError(status: Int, body: String): AiException {
        val detail = runCatching { json.decodeFromString(ApiErrorBody.serializer(), body).error?.message }
            .getOrNull()?.takeIf { it.isNotBlank() }
        return AiException(when (status) {
            401, 403 -> "API Key 无效或已过期"
            404 -> "接口地址或模型不存在，请检查设置"
            429 -> "请求太频繁或额度不足，请稍后再试"
            else -> detail ?: "AI 服务出错（HTTP $status）"
        })
    }
}

/** A failure meant to be shown to the user as it is; [message] is already user-readable. */
class AiException(message: String, cause: Throwable? = null) : Exception(message, cause)
