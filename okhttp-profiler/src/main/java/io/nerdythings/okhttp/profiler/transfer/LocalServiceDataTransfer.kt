package io.nerdythings.okhttp.profiler.transfer

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Base64
import org.json.JSONObject
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 通过 Android Studio 插件本地 HTTP 服务发送 profiler 事件。
 *
 * 插件会为当前设备配置 adb reverse，App 侧只需要访问设备上的 127.0.0.1 固定端口范围。
 */
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

    override fun send(id: String, type: MessageType, message: String) {
        handler.post {
            try {
                postEvent(id, type, message)
            } catch (_: Exception) {
            }
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
        private const val SPACE = ' '
        private val PORTS = 28937..28941
    }
}
