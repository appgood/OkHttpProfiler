package io.nerdythings.okhttp.profiler.transfer

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Base64
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.Sink
import okio.Timeout
import okio.buffer
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset

class LocalServiceDataTransfer : DataTransfer {
    private val handlerThread = HandlerThread(
        "OkHttpProfilerLocalService",
        Process.THREAD_PRIORITY_BACKGROUND
    ).apply {
        start()
    }
    private val handler = Handler(handlerThread.looper)

    @Volatile
    private var activePort: Int? = null

    @Volatile
    private var retryAfterMillis: Long = 0L

    @Throws(IOException::class)
    override fun sendRequest(id: String, request: Request) {
        enqueue(id, MessageType.REQUEST_METHOD, request.method)
        enqueue(id, MessageType.REQUEST_URL, request.url.toString())
        enqueue(id, MessageType.REQUEST_TIME, System.currentTimeMillis().toString())

        val body = request.newBuilder().build().body

        body?.let {
            body.contentType()?.let { type ->
                enqueue(
                    id = id,
                    type = MessageType.REQUEST_HEADER,
                    message = CONTENT_TYPE + HEADER_DELIMITER + SPACE + type.toString()
                )
            }
            val contentLength = body.contentLength()
            if (contentLength != -1L) {
                enqueue(
                    id = id,
                    type = MessageType.REQUEST_HEADER,
                    message = CONTENT_LENGTH + HEADER_DELIMITER + SPACE + contentLength
                )
            }
        }

        val headers = request.headers
        for (name in headers.names()) {
            if (CONTENT_TYPE.equals(name, ignoreCase = true)
                || CONTENT_LENGTH.equals(name, ignoreCase = true)
            ) {
                continue
            }
            enqueue(
                id = id,
                type = MessageType.REQUEST_HEADER,
                message = name + HEADER_DELIMITER + SPACE + headers[name]
            )
        }

        body?.let {
            if (!body.isDuplex() && !body.isOneShot()) {
                enqueue(id, MessageType.REQUEST_BODY, readRequestBody(body))
            }
        }
    }

    @Throws(IOException::class)
    override fun sendResponse(id: String, response: Response) {
        val responseBodyCopy = response.peekBody(BODY_BUFFER_SIZE.toLong())
        val bodyText = appendTruncatedMessageIfNeeded(
            responseBodyCopy.string(),
            responseBodyCopy.contentLength(),
            response.body?.contentLength() ?: -1L
        )
        enqueue(id, MessageType.RESPONSE_BODY, bodyText)

        val headers = response.headers
        enqueue(id, MessageType.RESPONSE_STATUS, response.code.toString())
        for (name in headers.names()) {
            enqueue(
                id,
                MessageType.RESPONSE_HEADER,
                name + HEADER_DELIMITER + headers[name]
            )
        }
    }

    override fun sendException(id: String, response: Exception) {
        enqueue(id, MessageType.RESPONSE_ERROR, response.localizedMessage ?: "Unknown exception")
    }

    override fun sendDuration(id: String, duration: Long) {
        enqueue(id, MessageType.RESPONSE_TIME, duration.toString())
        enqueue(id, MessageType.RESPONSE_END, "-->")
    }

    private fun enqueue(id: String, type: MessageType, message: String?) {
        if (message == null) return
        handler.post {
            try {
                postEvent(id, type, message)
            } catch (_: Exception) {
            }
        }
    }

    private fun readRequestBody(body: RequestBody): String {
        val contentLength = body.contentLength()
        if (contentLength > BODY_BUFFER_SIZE) {
            return bodyTooLargeMessage(contentLength)
        }

        val captureBuffer = Buffer()
        var capturedBytes = 0L
        var truncated = false
        val limitedSink = object : Sink {
            override fun write(source: Buffer, byteCount: Long) {
                val remainingBytes = BODY_BUFFER_SIZE - capturedBytes
                if (remainingBytes > 0L) {
                    val bytesToCapture = minOf(byteCount, remainingBytes)
                    source.copyTo(captureBuffer, 0, bytesToCapture)
                    capturedBytes += bytesToCapture
                }
                if (byteCount > remainingBytes) {
                    truncated = true
                }
                source.skip(byteCount)
            }

            override fun flush() {
            }

            override fun timeout(): Timeout {
                return Timeout.NONE
            }

            override fun close() {
            }
        }.buffer()

        body.writeTo(limitedSink)
        limitedSink.flush()

        val bodyText = captureBuffer.readString(Charset.defaultCharset())
        return if (truncated) {
            bodyText + "\n\n" + bodyTruncatedMessage(capturedBytes, contentLength)
        } else {
            bodyText
        }
    }

    private fun appendTruncatedMessageIfNeeded(body: String, capturedBytes: Long, totalBytes: Long): String {
        if (totalBytes < 0 || totalBytes <= BODY_BUFFER_SIZE) return body
        return body + "\n\n" + bodyTruncatedMessage(capturedBytes, totalBytes)
    }

    private fun bodyTooLargeMessage(totalBytes: Long): String {
        return "OkHttp Profiler: Body is too large to capture without slowing the app ($totalBytes bytes)."
    }

    private fun bodyTruncatedMessage(capturedBytes: Long, totalBytes: Long): String {
        return if (totalBytes >= 0) {
            "OkHttp Profiler: Body capture truncated to $capturedBytes of $totalBytes bytes."
        } else {
            "OkHttp Profiler: Body capture truncated to $capturedBytes bytes."
        }
    }

    private fun postEvent(id: String, type: MessageType, message: String) {
        activePort?.let { port ->
            if (sendToPort(port, id, type, message)) {
                return
            }
            activePort = null
        }

        val now = SystemClock.uptimeMillis()
        if (now < retryAfterMillis) {
            return
        }

        for (port in PORTS) {
            if (sendToPort(port, id, type, message)) {
                activePort = port
                retryAfterMillis = 0L
                return
            }
        }

        retryAfterMillis = now + RETRY_DELAY_MILLIS
    }

    private fun sendToPort(port: Int, id: String, type: MessageType, message: String): Boolean {
        var requestWritten = false
        return try {
            val payload = Base64.encodeToString(message.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val body = JSONObject()
                .put("protocolVersion", PROTOCOL_VERSION)
                .put("id", id)
                .put("type", type.text)
                .put("encoding", ENCODING_BASE64)
                .put("payload", payload)
                .toString()
                .toByteArray(Charsets.UTF_8)

            Socket().use { socket ->
                socket.connect(InetSocketAddress(LOCALHOST, port), CONNECT_TIMEOUT_MILLIS)
                socket.soTimeout = READ_TIMEOUT_MILLIS
                val header = buildHttpHeader(port, body.size).toByteArray(Charsets.US_ASCII)
                val output = socket.getOutputStream()
                output.write(header)
                output.write(body)
                output.flush()
                requestWritten = true
                val statusCode = readStatusCode(socket.getInputStream())
                // 请求已经写出后，响应状态读取失败属于未知投递结果；不重发，避免插件端已入库后重复显示。
                statusCode == null || statusCode in 200..299
            }
        } catch (_: Exception) {
            requestWritten
        }
    }

    private fun buildHttpHeader(port: Int, contentLength: Int): String {
        return "POST $EVENTS_PATH HTTP/1.1\r\n" +
                "Host: $LOCALHOST:$port\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: $contentLength\r\n" +
                "Connection: close\r\n" +
                "\r\n"
    }

    private fun readStatusCode(input: InputStream): Int? {
        val statusLine = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte == -1) return null
            if (byte == '\n'.code) break
            if (byte != '\r'.code) {
                statusLine.append(byte.toChar())
            }
        }
        return statusLine.toString().split(SPACE).getOrNull(1)?.toIntOrNull()
    }

    companion object {
        private const val PROTOCOL_VERSION = 2
        private const val LOCALHOST = "127.0.0.1"
        private const val EVENTS_PATH = "/okhttp-profiler/events"
        private const val ENCODING_BASE64 = "base64"
        private const val CONNECT_TIMEOUT_MILLIS = 200
        private const val READ_TIMEOUT_MILLIS = 500
        private const val RETRY_DELAY_MILLIS = 2_000L
        private const val BODY_BUFFER_SIZE = 1024 * 1024
        private const val HEADER_DELIMITER = ':'
        private const val SPACE = ' '
        private const val CONTENT_TYPE = "Content-Type"
        private const val CONTENT_LENGTH = "Content-Length"
        private val PORTS = 28937..28941
    }
}
