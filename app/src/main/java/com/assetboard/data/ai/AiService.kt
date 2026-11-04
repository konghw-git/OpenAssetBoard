package com.assetboard.data.ai

import com.assetboard.data.SettingsStore
import com.assetboard.data.net.Http
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

    /** A tiny exchange to prove the key, URL and model work; returns the model's greeting. */
    suspend fun testConnection(): String = complete("只回复两个字：正常", "正常")
}

/** One message of an agent conversation; [role] is "user" or "assistant". */
data class ChatTurn(val role: String, val content: String)

@Serializable
private data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ChatRequest(val model: String, val messages: List<ChatMessage>)

@Serializable
private data class ChatChoice(val message: ChatMessage? = null)

@Serializable
private data class ChatResponse(val choices: List<ChatChoice> = emptyList())

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

    override suspend fun complete(system: String, user: String): String =
        chat(system, listOf(ChatTurn("user", user)))

    override suspend fun chat(system: String, history: List<ChatTurn>): String = withContext(Dispatchers.IO) {
        val key = settings.aiApiKey.value.trim()
        if (key.isEmpty()) throw AiException("未配置 API Key，请到设置里填写")
        if (!settings.networkEnabled.value) throw AiException("已关闭联网，请到设置里开启")
        val base = settings.aiBaseUrl.value.trim().trimEnd('/').ifEmpty { SettingsStore.DEFAULT_AI_BASE_URL }
        val body = json.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(
                settings.aiModel.value.trim().ifEmpty { SettingsStore.DEFAULT_AI_MODEL },
                listOf(ChatMessage("system", system)) + history.map { ChatMessage(it.role, it.content) },
            ),
        )
        val reply = post("$base/chat/completions", key, body)
        val content = runCatching { json.decodeFromString(ChatResponse.serializer(), reply) }
            .getOrElse { throw AiException("AI 服务返回了无法识别的内容") }
            .choices.firstOrNull()?.message?.content
        // Only line breaks around the reply go: trim() would eat a paragraph's 全角缩进 too.
        val answer = content?.trim('\r', '\n')
        if (answer.isNullOrEmpty()) throw AiException("AI 没有返回内容")
        answer
    }

    /** Reads the error body too: the service says why (bad key, unknown model) in its own words. */
    private fun post(url: String, key: String, body: String): String {
        val r = try {
            // A long note can take a while to rewrite.
            Http.call(
                url, "POST", body, mapOf("Authorization" to "Bearer $key"),
                connectTimeout = 10_000, readTimeout = 90_000,
            )
        } catch (e: Exception) {
            throw AiException("网络连接失败，请检查网络", e)
        }
        if (r.status in 200..299) return r.body
        val detail = runCatching {
            json.decodeFromString(ApiErrorBody.serializer(), r.body).error?.message
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val message = when (r.status) {
            401, 403 -> "API Key 无效或已过期"
            404 -> "接口地址或模型不存在，请检查设置"
            429 -> "请求太频繁或额度不足，请稍后再试"
            else -> detail ?: "AI 服务出错（HTTP ${r.status}）"
        }
        throw AiException(message)
    }
}

/** A failure meant to be shown to the user as it is; [message] is already user-readable. */
class AiException(message: String, cause: Throwable? = null) : Exception(message, cause)
