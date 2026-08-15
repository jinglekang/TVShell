package com.example.tvshell.remote

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class RemoteServer(
    private val context: Context,
    private val port: Int = 8765,
    private val tokenProvider: () -> String,
    private val listener: RemoteCommandListener
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newCachedThreadPool()
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true
        executor.execute {
            try {
                serverSocket = ServerSocket(port)
                Log.i(TAG, "RemoteServer started on port $port")
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    executor.execute {
                        handleClient(socket)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Server socket error", e)
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        serverSocket = null
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 10000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val rawPath = parts[1]

            // Read Headers
            val headers = mutableMapOf<String, String>()
            var line: String?
            var contentLength = 0
            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrBlank()) break
                val colonIdx = line!!.indexOf(':')
                if (colonIdx != -1) {
                    val headerName = line!!.substring(0, colonIdx).trim().lowercase()
                    val headerVal = line!!.substring(colonIdx + 1).trim()
                    headers[headerName] = headerVal
                    if (headerName == "content-length") {
                        contentLength = headerVal.toIntOrNull() ?: 0
                    }
                }
            }

            // Parse URL & Query params
            val pathParts = rawPath.split("?", limit = 2)
            val path = pathParts[0]
            val queryParams = mutableMapOf<String, String>()
            if (pathParts.size > 1) {
                val queryPairs = pathParts[1].split("&")
                for (pair in queryPairs) {
                    val kv = pair.split("=", limit = 2)
                    if (kv.isNotEmpty()) {
                        val key = URLDecoder.decode(kv[0], "UTF-8")
                        val value = if (kv.size > 1) URLDecoder.decode(kv[1], "UTF-8") else ""
                        queryParams[key] = value
                    }
                }
            }

            // Read Body if POST/PUT
            var body = ""
            if (contentLength > 0) {
                val charArray = CharArray(contentLength)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val read = reader.read(charArray, totalRead, contentLength - totalRead)
                    if (read == -1) break
                    totalRead += read
                }
                body = String(charArray, 0, totalRead)
            }

            // Handle CORS preflight
            if (method == "OPTIONS") {
                sendResponse(output, 204, "No Content", "text/plain", ByteArray(0))
                return
            }

            // Route static assets or APIs
            when {
                path == "/" || path == "/index.html" -> {
                    serveAsset(output, "remote/index.html", "text/html; charset=utf-8")
                }
                path == "/style.css" -> {
                    serveAsset(output, "remote/style.css", "text/css; charset=utf-8")
                }
                path == "/remote.js" -> {
                    serveAsset(output, "remote/remote.js", "application/javascript; charset=utf-8")
                }
                path.startsWith("/api/") -> {
                    handleApi(output, method, path, queryParams, headers, body)
                }
                else -> {
                    sendJson(output, 404, JSONObject().put("error", "Not Found").toString())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client", e)
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun handleApi(
        output: OutputStream,
        method: String,
        path: String,
        queryParams: Map<String, String>,
        headers: Map<String, String>,
        body: String
    ) {
        val expectedToken = tokenProvider()
        val receivedToken = queryParams["token"] ?: headers["x-remote-token"]

        if (receivedToken.isNullOrBlank() || receivedToken != expectedToken) {
            sendJson(output, 403, JSONObject().put("error", "Invalid or missing token").toString())
            return
        }

        when (path) {
            "/api/status" -> {
                val status = listener.getStatus()
                val json = JSONObject().apply {
                    put("connected", status.connected)
                    put("currentUrl", status.currentUrl ?: "")
                    put("title", status.title ?: "")
                    put("loading", status.loading)
                    put("pageOpen", status.pageOpen)
                    put("language", status.language)
                }
                sendJson(output, 200, json.toString())
            }
            "/api/open" -> {
                if (method != "POST") {
                    sendJson(output, 405, JSONObject().put("error", "Method Not Allowed").toString())
                    return
                }
                val json = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
                val targetUrl = json.optString("url", "").trim()
                if (targetUrl.isNotEmpty()) {
                    mainHandler.post {
                        listener.onOpenUrl(targetUrl)
                    }
                    sendJson(output, 200, JSONObject().put("success", true).toString())
                } else {
                    sendJson(output, 400, JSONObject().put("error", "Missing url").toString())
                }
            }
            "/api/paste" -> {
                if (method != "POST") {
                    sendJson(output, 405, JSONObject().put("error", "Method Not Allowed").toString())
                    return
                }
                val json = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
                val text = json.optString("text", "")
                if (text.isEmpty()) {
                    sendJson(output, 400, JSONObject().put("error", "Missing text").toString())
                    return
                }
                mainHandler.post {
                    listener.onPasteText(text)
                }
                sendJson(output, 200, JSONObject().put("success", true).toString())
            }
            "/api/key" -> {
                if (method != "POST") {
                    sendJson(output, 405, JSONObject().put("error", "Method Not Allowed").toString())
                    return
                }
                val json = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
                val key = json.optString("key", "").trim().lowercase()
                val allowed = setOf("up", "down", "left", "right", "ok", "back")
                if (key !in allowed) {
                    sendJson(output, 400, JSONObject().put("error", "Invalid key").toString())
                    return
                }
                val action = json.optString("action", "down").trim().lowercase()
                val down = action != "up"
                mainHandler.post {
                    listener.onRemoteKey(key, down)
                }
                sendJson(output, 200, JSONObject().put("success", true).toString())
            }
            "/api/scroll" -> {
                if (method != "POST") {
                    sendJson(output, 405, JSONObject().put("error", "Method Not Allowed").toString())
                    return
                }
                val json = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
                val direction = json.optString("direction", "down")
                mainHandler.post {
                    listener.onScroll(direction)
                }
                sendJson(output, 200, JSONObject().put("success", true).toString())
            }
            "/api/history-back" -> {
                if (method != "POST") {
                    sendJson(output, 405, JSONObject().put("error", "Method Not Allowed").toString())
                    return
                }
                mainHandler.post {
                    listener.onHistoryBack()
                }
                sendJson(output, 200, JSONObject().put("success", true).toString())
            }
            "/api/history-forward" -> {
                if (method != "POST") {
                    sendJson(output, 405, JSONObject().put("error", "Method Not Allowed").toString())
                    return
                }
                mainHandler.post {
                    listener.onHistoryForward()
                }
                sendJson(output, 200, JSONObject().put("success", true).toString())
            }
            "/api/reload" -> {
                if (method != "POST") {
                    sendJson(output, 405, JSONObject().put("error", "Method Not Allowed").toString())
                    return
                }
                mainHandler.post {
                    listener.onReload()
                }
                sendJson(output, 200, JSONObject().put("success", true).toString())
            }
            "/api/show-menu" -> {
                if (method != "POST") {
                    sendJson(output, 405, JSONObject().put("error", "Method Not Allowed").toString())
                    return
                }
                mainHandler.post {
                    listener.onShowMenu()
                }
                sendJson(output, 200, JSONObject().put("success", true).toString())
            }
            "/api/show-home" -> {
                if (method != "POST") {
                    sendJson(output, 405, JSONObject().put("error", "Method Not Allowed").toString())
                    return
                }
                mainHandler.post {
                    listener.onShowHome()
                }
                sendJson(output, 200, JSONObject().put("success", true).toString())
            }
            "/api/show-settings" -> {
                if (method != "POST") {
                    sendJson(output, 405, JSONObject().put("error", "Method Not Allowed").toString())
                    return
                }
                mainHandler.post {
                    listener.onShowSettings()
                }
                sendJson(output, 200, JSONObject().put("success", true).toString())
            }
            else -> {
                sendJson(output, 404, JSONObject().put("error", "Unknown endpoint").toString())
            }
        }
    }

    private fun serveAsset(output: OutputStream, assetPath: String, contentType: String) {
        try {
            val assetManager = context.assets
            val inputStream: InputStream = assetManager.open(assetPath)
            val buffer = ByteArray(8192)
            val baos = ByteArrayOutputStream()
            var read: Int
            while (inputStream.read(buffer).also { read = it } != -1) {
                baos.write(buffer, 0, read)
            }
            inputStream.close()
            val data = baos.toByteArray()
            sendResponse(output, 200, "OK", contentType, data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load asset $assetPath", e)
            sendResponse(output, 404, "Not Found", "text/plain", "File not found".toByteArray())
        }
    }

    private fun sendJson(output: OutputStream, statusCode: Int, json: String) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        sendResponse(output, statusCode, if (statusCode == 200) "OK" else "Error", "application/json; charset=utf-8", bytes)
    }

    private fun sendResponse(
        output: OutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        data: ByteArray
    ) {
        val headerBuilder = StringBuilder()
        headerBuilder.append("HTTP/1.1 $statusCode $statusText\r\n")
        headerBuilder.append("Content-Type: $contentType\r\n")
        headerBuilder.append("Content-Length: ${data.size}\r\n")
        headerBuilder.append("Access-Control-Allow-Origin: *\r\n")
        headerBuilder.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        headerBuilder.append("Access-Control-Allow-Headers: Content-Type, X-Remote-Token\r\n")
        headerBuilder.append("Referrer-Policy: no-referrer\r\n")
        headerBuilder.append("Cache-Control: no-cache, no-store, must-revalidate\r\n")
        headerBuilder.append("Connection: close\r\n")
        headerBuilder.append("\r\n")

        output.write(headerBuilder.toString().toByteArray(StandardCharsets.UTF_8))
        if (data.isNotEmpty()) {
            output.write(data)
        }
        output.flush()
    }

    companion object {
        private const val TAG = "RemoteServer"
    }
}
