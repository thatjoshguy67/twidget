package com.tjg.twidget.core

import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** Shared HTTP helpers for short-lived JSON requests across Twidget clients. */
internal object HttpTransport {
    init {
        // HttpURLConnection keep-alive is per host. Four avatar downloads need more than the default pool.
        System.setProperty("http.keepAlive", "true")
        System.setProperty("http.maxConnections", "8")
    }
    data class Response(
        val code: Int,
        val body: String,
        val headers: Map<String, List<String>> = emptyMap(),
    ) {
        fun headerValues(name: String): List<String> = headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value.orEmpty()
    }

    class HttpException(val code: Int, message: String) : IllegalStateException(message)

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 10_000,
        userAgent: String? = null,
    ): Response = request("GET", url, null, headers, connectTimeoutMs, readTimeoutMs, userAgent)

    fun post(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 10_000,
        userAgent: String? = null,
    ): Response = request("POST", url, body, headers, connectTimeoutMs, readTimeoutMs, userAgent)

    fun request(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        userAgent: String?,
    ): Response {
        val connection = openConnection(url, method, headers, connectTimeoutMs, readTimeoutMs, userAgent)
        var consumed = false
        val deadline = System.nanoTime() + (connectTimeoutMs.toLong() + readTimeoutMs) * 1_000_000L
        try {
            checkActive(deadline)
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            checkActive(deadline)
            val responseHeaders = connection.headerFields.filterKeys { it != null }.mapKeys { requireNotNull(it.key) }
            val limit = if (code in 200..299) MAX_RESPONSE_BYTES else MAX_ERROR_BYTES
            require(connection.contentLengthLong <= limit) { "HTTP response is too large" }
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { readUtf8(it, limit, deadline) }.orEmpty()
            consumed = true
            return Response(code, text, responseHeaders)
        } finally {
            // Closing a fully consumed stream releases its connection to the keep-alive pool.
            // Abort incomplete/error paths explicitly instead of pooling unread data.
            if (!consumed) connection.disconnect()
        }
    }

    private const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024
    private const val MAX_ERROR_BYTES = 64 * 1024

    internal fun readUtf8(input: InputStream, limit: Int, deadline: Long = Long.MAX_VALUE): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            checkActive(deadline)
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= limit) { "HTTP response is too large" }
            output.write(buffer, 0, count)
        }
        checkActive(deadline)
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private fun checkActive(deadline: Long) {
        if (Thread.currentThread().isInterrupted || System.nanoTime() >= deadline) {
            throw InterruptedIOException("HTTP request cancelled or timed out")
        }
    }

    fun requireSuccess(response: Response, errorPrefix: String): String {
        if (response.code !in 200..299) {
            throw HttpException(response.code, "$errorPrefix HTTP ${response.code}: ${response.body.take(300)}")
        }
        return response.body
    }

    /** Opens a configured connection for streaming/binary responses. Caller must disconnect. */
    fun openConnection(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 10_000,
        userAgent: String? = null,
    ): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.requestMethod = method
        connection.setRequestProperty("Accept", "*/*")
        connection.setRequestProperty("Connection", "keep-alive")
        userAgent?.let { connection.setRequestProperty("User-Agent", it) }
        headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
        return connection
    }
}
