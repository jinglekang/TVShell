package com.example.tvshell.remote

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

internal data class HttpRequest(
    val method: String,
    val rawPath: String,
    val headers: Map<String, String>,
    val body: String
)

internal class HttpRequestException(val statusCode: Int, message: String) : Exception(message)

/** One HTTP request per connection. Headers and body share the same byte buffer. */
internal object HttpRequestReader {
    private const val MAX_HEADER_BYTES = 16 * 1024
    private const val MAX_BODY_BYTES = 256 * 1024

    fun read(source: InputStream): HttpRequest? {
        val input = BufferedInputStream(source)
        var headerBytes = 0
        fun readLine(): String? {
            val line = ByteArrayOutputStream()
            while (true) {
                val byte = input.read()
                if (byte == -1) {
                    if (line.size() == 0) return null
                    throw HttpRequestException(400, "Incomplete headers")
                }
                if (++headerBytes > MAX_HEADER_BYTES) {
                    throw HttpRequestException(431, "Headers too large")
                }
                if (byte == '\n'.code) {
                    return line.toString(StandardCharsets.ISO_8859_1.name()).removeSuffix("\r")
                }
                line.write(byte)
            }
        }

        val requestLine = readLine() ?: return null
        val parts = requestLine.split(" ")
        if (parts.size != 3 || parts[0].isBlank() || !parts[1].startsWith("/") ||
            parts[2] !in setOf("HTTP/1.0", "HTTP/1.1")
        ) {
            throw HttpRequestException(400, "Invalid request line")
        }
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine() ?: throw HttpRequestException(400, "Incomplete headers")
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) throw HttpRequestException(400, "Invalid header")
            val name = line.substring(0, colon).trim().lowercase(Locale.ROOT)
            val value = line.substring(colon + 1).trim()
            if (name == "content-length" && headers.containsKey(name)) {
                throw HttpRequestException(400, "Duplicate Content-Length")
            }
            headers[name] = value
        }
        if (headers.containsKey("transfer-encoding")) {
            throw HttpRequestException(400, "Transfer-Encoding is not supported")
        }
        val contentLength = headers["content-length"]?.let {
            it.toLongOrNull()?.takeIf { size -> size >= 0 }
                ?: throw HttpRequestException(400, "Invalid Content-Length")
        } ?: 0L
        if (contentLength > MAX_BODY_BYTES) {
            throw HttpRequestException(413, "Body too large")
        }
        val body = ByteArray(contentLength.toInt())
        var offset = 0
        while (offset < body.size) {
            val read = input.read(body, offset, body.size - offset)
            if (read == -1) throw HttpRequestException(400, "Incomplete body")
            offset += read
        }
        return HttpRequest(
            method = parts[0].uppercase(Locale.ROOT),
            rawPath = parts[1],
            headers = headers,
            body = String(body, StandardCharsets.UTF_8)
        )
    }
}
