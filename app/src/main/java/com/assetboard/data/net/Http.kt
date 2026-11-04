package com.assetboard.data.net

import java.net.HttpURLConnection
import java.net.URL

/**
 * The one place that opens an [HttpURLConnection] for a small request/response exchange:
 * timeouts, optional headers and body in, status and body out. Mapping the outcome to a
 * user-readable message belongs to each service (they are different domains), and callers
 * that stream or chase redirects — the updater's APK download and mirror probe — keep their
 * own connections.
 */
internal object Http {
    /** Header names are lower-cased; [header] is case-insensitive. */
    data class Response(val status: Int, val body: String, val headers: Map<String, String> = emptyMap()) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    fun call(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        connectTimeout: Int = 10_000,
        readTimeout: Int = 15_000,
        followRedirects: Boolean = true,
    ): Response {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = connectTimeout
            c.readTimeout = readTimeout
            c.instanceFollowRedirects = followRedirects
            for ((name, value) in headers) c.setRequestProperty(name, value)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = c.responseCode
            // The error body is read too: APIs say what went wrong in it (bad key, unknown model).
            val text = (if (status in 200..299) c.inputStream else c.errorStream)
                ?.use { it.readBytes().decodeToString() }.orEmpty()
            // The sync headers carry the revision and hash of exactly this body, and Date is the
            // server's clock; losing them here is what made the old code trust a stale metadata
            // read (AccountManager.kt's download used the meta revision).
            val headers = c.headerFields.entries
                .filter { it.key != null }
                .associate { it.key.lowercase() to (it.value?.firstOrNull() ?: "") }
            return Response(status, text, headers)
        } finally {
            c.disconnect()
        }
    }
}
