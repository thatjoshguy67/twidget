package com.tjg.twidget

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** Shared HTTP helpers for short-lived JSON requests across Twidget clients. */
internal object HttpTransport {
    data class Response(val code: Int, val body: String)

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
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.requestMethod = method
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Connection", "keep-alive")
        userAgent?.let { connection.setRequestProperty("User-Agent", it) }
        headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.let { BufferedReader(InputStreamReader(it)).use { reader -> reader.readText() } }.orEmpty()
        return Response(code, text)
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
