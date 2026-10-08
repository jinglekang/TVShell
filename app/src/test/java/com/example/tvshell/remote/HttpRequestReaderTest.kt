package com.example.tvshell.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HttpRequestReaderTest {
    private fun request(body: String): ByteArray {
        val bytes = body.toByteArray(Charsets.UTF_8)
        return "POST /api/paste?token=test HTTP/1.1\r\nContent-Length: ${bytes.size}\r\n\r\n"
            .toByteArray(Charsets.US_ASCII) + bytes
    }

    @Test
    fun unicodeBodyCompletesWhileClientKeepsConnectionOpen() {
        val body = "{\"text\":\"中文粘贴 😀 https://example.com/页面\"}"
        val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                val result = executor.submit<HttpRequest?> {
                    server.accept().use { socket ->
                        socket.soTimeout = 1000
                        HttpRequestReader.read(socket.getInputStream())
                    }
                }
                Socket(InetAddress.getLoopbackAddress(), server.localPort).use { client ->
                    client.getOutputStream().write(request(body))
                    client.getOutputStream().flush()
                    // No shutdownOutput(): a browser waits for the response before closing.
                    val parsed = result.get(2, TimeUnit.SECONDS)
                    assertNotNull(parsed)
                    assertEquals(body, parsed!!.body)
                    assertEquals("POST", parsed.method)
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun fragmentedHeadersAndUtf8BodyAreReadCompletely() {
        val body = "{\"text\":\"中文 😀\"}"
        val input = object : FilterInputStream(ByteArrayInputStream(request(body))) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                super.read(bytes, offset, minOf(length, 1))
        }
        assertEquals(body, HttpRequestReader.read(input)!!.body)
    }

    @Test
    fun requestWithoutBodyStillParses() {
        val input = "GET /api/status HTTP/1.1\r\nX-Remote-Token: test\r\n\r\n"
        val parsed = HttpRequestReader.read(input.byteInputStream())!!
        assertEquals("", parsed.body)
        assertEquals("test", parsed.headers["x-remote-token"])
    }

    @Test
    fun truncatedBodyIsRejectedInsteadOfDispatchingPartialJson() {
        assertRejected(400, "POST /api/paste HTTP/1.1\r\nContent-Length: 20\r\n\r\n{}")
    }

    @Test
    fun invalidAndOversizedLengthsAreRejectedBeforeAllocation() {
        listOf("-1", "invalid", "9223372036854775808").forEach { length ->
            assertRejected(400, "POST /api/paste HTTP/1.1\r\nContent-Length: $length\r\n\r\n")
        }
        assertRejected(413, "POST /api/paste HTTP/1.1\r\nContent-Length: 262145\r\n\r\n")
    }

    @Test
    fun ambiguousFramingAndOversizedHeadersAreRejected() {
        assertRejected(400, "POST /api/paste HTTP/1.1\r\nContent-Length: 2\r\nContent-Length: 2\r\n\r\n{}")
        assertRejected(400, "POST /api/paste HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n")
        assertRejected(431, "GET / HTTP/1.1\r\nX-Large: ${"a".repeat(16384)}\r\n\r\n")
        assertRejected(400, "GET / HTTP/1.1\r\nX-Incomplete: test\r\n")
    }

    private fun assertRejected(status: Int, input: String) {
        val error = assertThrows(HttpRequestException::class.java) {
            HttpRequestReader.read(input.byteInputStream())
        }
        assertEquals(status, error.statusCode)
    }
}
