package com.example.tvshell.remote

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class RemoteServer(
    private val context: Context,
    private val port: Int = 8765,
    private val tokenProvider: () -> String,
    private val listener: RemoteCommandListener
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var acceptExecutor: ExecutorService? = null
    private var clientExecutor: ThreadPoolExecutor? = null
    private val clients = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false

    @Synchronized
    fun start() {
        if (isRunning) return
        val listeningSocket = try {
            ServerSocket().also { socket ->
                try {
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress(port))
                } catch (e: IOException) {
                    socket.close()
                    throw e
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to bind remote port $port", e)
            return
        }
        val workers = ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8)
        )
        val acceptor = Executors.newSingleThreadExecutor()
        serverSocket = listeningSocket
        clientExecutor = workers
        acceptExecutor = acceptor
        isRunning = true
        acceptor.execute {
            try {
                Log.i(TAG, "RemoteServer started on port $port")
                while (isRunning && serverSocket === listeningSocket) {
                    val socket = listeningSocket.accept()
                    synchronized(this) {
                        if (!isRunning || serverSocket !== listeningSocket) {
                            socket.close()
                        } else {
                            clients.add(socket)
                            try {
                                workers.execute { handleClient(socket) }
                            } catch (e: RejectedExecutionException) {
                                clients.remove(socket)
                                socket.close()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Server socket error", e)
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        serverSocket = null
        mainHandler.removeCallbacksAndMessages(null)
        clients.forEach { socket -> runCatching { socket.close() } }
        clients.clear()
        clientExecutor?.shutdownNow()
        acceptExecutor?.shutdownNow()
        clientExecutor = null
        acceptExecutor = null
    }

    private fun postCommand(command: () -> Unit) {
        mainHandler.post {
            if (isRunning) command()
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 5000
            val output = socket.getOutputStream()
            val request = try {
                HttpRequestReader.read(socket.getInputStream()) ?: return
            } catch (e: HttpRequestException) {
                sendJson(output, e.statusCode, JSONObject().put("error", e.message).toString())
                return
            } catch (e: SocketTimeoutException) {
                sendJson(output, 408, JSONObject().put("error", "Request timeout").toString())
                return
            }
            val method = request.method
            val rawPath = request.rawPath
            val headers = request.headers

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

            val body = request.body

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
            if (isRunning && !socket.isClosed) {
                Log.e(TAG, "Error handling client", e)
            }
        } finally {
            clients.remove(socket)
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
                    postCommand {
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
                postCommand {
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
                postCommand {
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
                postCommand {
                    listener.onScroll(direction)
                }
                sendJson(output, 200, JSONObject().put("success", true).toString())
            }
            "/api/history-back" -> {
                if (method != "POST") {
                    sendJson(output, 405, JSONObject().put("error", "Method Not Allowed").toString())
                    return
                }
                postCommand {
                    listener.onHistoryBack()
                }
                sendJson(output, 200, JSONObject().put("success", true).toString())
            }
            "/api/history-forward" -> {
                if (method != "POST") {
                    sendJson(output, 405, JSONObject().put("error", "Method Not Allowed").toString())
                    return
                }
                postCommand {
                    listener.onHistoryForward()
                }
                sendJson(output, 200, JSONObject().put("success", true).toString())
            }
            "/api/reload" -> {
                if (method != "POST") {
                    sendJson(output, 405, JSONObject().put("error", "Method Not Allowed").toString())
                    return
                }
                postCommand {
                    listener.onReload()
                }
                sendJson(output, 200, JSONObject().put("success", true).toString())
            }
            "/api/show-menu" -> {
                if (method != "POST") {
                    sendJson(output, 405, JSONObject().put("error", "Method Not Allowed").toString())
                    return
                }
                postCommand {
                    listener.onShowMenu()
                }
                sendJson(output, 200, JSONObject().put("success", true).toString())
            }
            "/api/show-home" -> {
                if (method != "POST") {
                    sendJson(output, 405, JSONObject().put("error", "Method Not Allowed").toString())
                    return
                }
                postCommand {
                    listener.onShowHome()
                }
                sendJson(output, 200, JSONObject().put("success", true).toString())
            }
            "/api/show-settings" -> {
                if (method != "POST") {
                    sendJson(output, 405, JSONObject().put("error", "Method Not Allowed").toString())
                    return
                }
                postCommand {
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
