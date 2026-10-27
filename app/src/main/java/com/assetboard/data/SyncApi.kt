package com.assetboard.data

import com.assetboard.domain.LedgerException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class RemoteUser(val id: String, val username: String)

@Serializable
data class ServerSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long = 0,
    val user: RemoteUser,
)

@Serializable
data class RemoteMeta(
    val revision: Long = 0,
    val contentHash: String = "",
    val formatVersion: Int = 0,
    val schemaVersion: Int = 0,
    val size: Long = 0,
    val updatedAt: Long = 0,
)

@Serializable
private data class LoginBody(val username: String, val password: String)

@Serializable
private data class RefreshBody(val refreshToken: String)

@Serializable
private data class UploadResult(val revision: Long)

@Serializable
private data class ErrorDetail(val code: String? = null, val message: String? = null)

@Serializable
private data class ErrorBody(val error: ErrorDetail? = null)

@Serializable
private data class ConflictBody(val error: ErrorDetail? = null, val revision: Long = 0)

/** A failed call with a message fit to show; [revision] is set for a 409. */
class ApiException(
    message: String,
    val status: Int = 0,
    val code: String? = null,
    val revision: Long? = null,
) : LedgerException(message)

/** The four calls the account manager makes, small enough to fake in a test. */
interface SyncGateway {
    suspend fun login(serverUrl: String, username: String, password: String): ServerSession
    suspend fun refresh(serverUrl: String, refreshToken: String): ServerSession
    suspend fun logout(serverUrl: String, refreshToken: String)
    suspend fun meta(serverUrl: String, access: String): RemoteMeta
    suspend fun download(serverUrl: String, access: String): String?
    suspend fun upload(serverUrl: String, access: String, baseRevision: Long, snapshotJson: String, contentHash: String): Long
}

/**
 * The self-hosted account and snapshot service. Plain [HttpURLConnection] and the app's existing
 * JSON setup on purpose: the project avoids another HTTP stack for four endpoints.
 */
class SyncApi(private val networkAllowed: () -> Boolean = { true }) : SyncGateway {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun login(serverUrl: String, username: String, password: String): ServerSession = withContext(Dispatchers.IO) {
        val raw = request(
            serverUrl, "POST", "/v1/auth/login",
            json.encodeToString(LoginBody.serializer(), LoginBody(username.trim(), password)), null,
        )
        if (raw.status != 200) fail(raw)
        decode(raw) { json.decodeFromString(ServerSession.serializer(), raw.body) }
    }

    override suspend fun refresh(serverUrl: String, refreshToken: String): ServerSession = withContext(Dispatchers.IO) {
        val raw = request(
            serverUrl, "POST", "/v1/auth/refresh",
            json.encodeToString(RefreshBody.serializer(), RefreshBody(refreshToken)), null,
        )
        if (raw.status != 200) fail(raw)
        decode(raw) { json.decodeFromString(ServerSession.serializer(), raw.body) }
    }

    override suspend fun logout(serverUrl: String, refreshToken: String) = withContext(Dispatchers.IO) {
        val raw = request(
            serverUrl, "POST", "/v1/auth/logout",
            json.encodeToString(RefreshBody.serializer(), RefreshBody(refreshToken)), null,
        )
        if (raw.status >= 500) fail(raw)
    }

    override suspend fun meta(serverUrl: String, access: String): RemoteMeta = withContext(Dispatchers.IO) {
        val raw = request(serverUrl, "GET", "/v1/sync/meta", null, access)
        if (raw.status == 404) return@withContext RemoteMeta()
        if (raw.status != 200) fail(raw)
        decode(raw) { json.decodeFromString(RemoteMeta.serializer(), raw.body) }
    }

    /** The snapshot, or null when the account has none yet. */
    override suspend fun download(serverUrl: String, access: String): String? = withContext(Dispatchers.IO) {
        val raw = request(serverUrl, "GET", "/v1/sync", null, access)
        when (raw.status) {
            200 -> raw.body
            404 -> null
            else -> fail(raw)
        }
    }

    override suspend fun upload(
        serverUrl: String,
        access: String,
        baseRevision: Long,
        snapshotJson: String,
        contentHash: String,
    ): Long = withContext(Dispatchers.IO) {
        val raw = request(
            serverUrl, "PUT", "/v1/sync", snapshotJson, access,
            mapOf("If-Match" to baseRevision.toString(), "X-Content-Hash" to contentHash),
        )
        if (raw.status == 409) {
            val conflict = runCatching { json.decodeFromString(ConflictBody.serializer(), raw.body) }.getOrNull()
            throw ApiException(
                "云端已有新的版本，需要先决定保留哪一边",
                status = 409,
                code = conflict?.error?.code ?: "conflict",
                revision = conflict?.revision?.takeIf { it > 0 },
            )
        }
        if (raw.status != 200) fail(raw)
        decode(raw) { json.decodeFromString(UploadResult.serializer(), raw.body).revision }
    }

    private inline fun <T> decode(raw: Raw, block: () -> T): T =
        try {
            block()
        } catch (e: Exception) {
            if (e is ApiException) throw e
            throw ApiException("服务器返回的数据无法识别（状态码 ${raw.status}）", raw.status)
        }

    private fun fail(raw: Raw): Nothing {
        val detail = runCatching { json.decodeFromString(ErrorBody.serializer(), raw.body).error }.getOrNull()
        val message = when {
            raw.status == 400 -> "请求无效，请更新 app 后再试"
            raw.status == 401 -> "登录已过期，请重新登录"
            raw.status == 403 -> "没有权限访问这个账号"
            raw.status == 413 -> "数据太大，服务器无法接收"
            raw.status == 429 -> "尝试太频繁，请稍后再试"
            raw.status >= 500 -> "服务器出错，请稍后再试"
            else -> detail?.message ?: "请求失败（状态码 ${raw.status}）"
        }
        throw ApiException(message, raw.status, detail?.code)
    }

    private data class Raw(val status: Int, val body: String)

    private fun request(
        serverUrl: String,
        method: String,
        path: String,
        body: String?,
        access: String?,
        extra: Map<String, String> = emptyMap(),
    ): Raw {
        if (!networkAllowed()) throw ApiException("已关闭联网，无法同步")
        val base = normalize(serverUrl)
        val connection = try {
            URL(base + path).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw ApiException("服务器地址无效")
        }
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            // A full snapshot over a slow mobile link; the server bounds its side at five minutes.
            connection.readTimeout = 120_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (access != null) connection.setRequestProperty("Authorization", "Bearer $access")
            for ((name, value) in extra) connection.setRequestProperty(name, value)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().decodeToString() }.orEmpty()
            return Raw(status, text)
        } catch (e: IOException) {
            throw ApiException("无法连接服务器，请检查网络（${e.message ?: "连接失败"}）")
        } finally {
            connection.disconnect()
        }
    }

    /** HTTPS everywhere except a loopback development server. */
    private fun normalize(serverUrl: String): String {
        val trimmed = serverUrl.trim().trimEnd('/')
        val url = try {
            URL(trimmed)
        } catch (e: Exception) {
            throw ApiException("服务器地址无效")
        }
        if (url.protocol != "https" && url.host !in setOf("localhost", "127.0.0.1", "10.0.2.2")) {
            throw ApiException("服务器地址必须使用 https")
        }
        return trimmed
    }
}