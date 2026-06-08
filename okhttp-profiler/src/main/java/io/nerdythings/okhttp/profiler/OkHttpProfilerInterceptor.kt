package io.nerdythings.okhttp.profiler

import io.nerdythings.okhttp.profiler.transfer.DataTransfer
import io.nerdythings.okhttp.profiler.transfer.MessageType
import okhttp3.Interceptor
import okhttp3.Interceptor.Chain
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.Sink
import okio.Timeout
import okio.buffer
import java.io.IOException
import java.nio.charset.Charset
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * @author itkacher
 * @since 9/25/18
 *
 * OkHttp Profiler 通用拦截器。
 *
 * 默认使用 local-service 协议，调用方可以通过 [ProfilerTransport] 显式切换到 logcat：
 * `OkHttpProfilerInterceptor(ProfilerTransport.LOGCAT)`。
 */
class OkHttpProfilerInterceptor @JvmOverloads constructor(
    transport: ProfilerTransport = DEFAULT_TRANSPORT
) : Interceptor {
    private val dataTransfer: DataTransfer = transport.createDataTransfer()
    private val format: DateFormat = SimpleDateFormat("ddhhmmssSSS", Locale.US)
    private val previousTime = AtomicLong()

    @Throws(IOException::class)
    override fun intercept(chain: Chain): Response {
        val id = generateId()
        val startTime = System.currentTimeMillis()
        trySend {
            sendRequest(id, chain.request())
        }
        try {
            val response = chain.proceed(chain.request())
            trySend {
                sendResponse(id, response)
            }
            return response
        } catch (e: Exception) {
            trySend {
                sendException(id, e)
            }
            throw e
        } finally {
            trySend {
                sendDuration(id, System.currentTimeMillis() - startTime)
            }
        }
    }

    private fun trySend(action: () -> Unit) {
        try {
            action()
        } catch (_: Exception) {
        }
    }

    @Throws(IOException::class)
    private fun sendRequest(id: String, request: Request) {
        sendEvent(id, MessageType.REQUEST_METHOD, request.method)
        sendEvent(id, MessageType.REQUEST_URL, request.url.toString())
        sendEvent(id, MessageType.REQUEST_TIME, System.currentTimeMillis().toString())

        val body = request.newBuilder().build().body
        body?.let {
            body.contentType()?.let { type ->
                sendEvent(
                    id = id,
                    type = MessageType.REQUEST_HEADER,
                    message = CONTENT_TYPE + HEADER_DELIMITER + SPACE + type.toString()
                )
            }
            val contentLength = body.contentLength()
            if (contentLength != -1L) {
                sendEvent(
                    id = id,
                    type = MessageType.REQUEST_HEADER,
                    message = CONTENT_LENGTH + HEADER_DELIMITER + SPACE + contentLength
                )
            }
        }

        val headers = request.headers
        for (name in headers.names()) {
            // Content-Type 和 Content-Length 已从 RequestBody 单独取过，避免重复展示。
            if (CONTENT_TYPE.equals(name, ignoreCase = true)
                || CONTENT_LENGTH.equals(name, ignoreCase = true)
            ) {
                continue
            }
            sendEvent(
                id = id,
                type = MessageType.REQUEST_HEADER,
                message = name + HEADER_DELIMITER + SPACE + headers[name]
            )
        }

        body?.let {
            if (!body.isDuplex() && !body.isOneShot()) {
                sendEvent(id, MessageType.REQUEST_BODY, readRequestBody(body))
            }
        }
    }

    @Throws(IOException::class)
    private fun sendResponse(id: String, response: Response) {
        val responseBodyCopy = response.peekBody(BODY_BUFFER_SIZE.toLong())
        val bodyText = appendTruncatedMessageIfNeeded(
            responseBodyCopy.string(),
            responseBodyCopy.contentLength(),
            response.body?.contentLength() ?: -1L
        )
        sendEvent(id, MessageType.RESPONSE_BODY, bodyText)

        val headers = response.headers
        sendEvent(id, MessageType.RESPONSE_STATUS, response.code.toString())
        for (name in headers.names()) {
            sendEvent(
                id,
                MessageType.RESPONSE_HEADER,
                name + HEADER_DELIMITER + headers[name]
            )
        }
    }

    private fun sendException(id: String, response: Exception) {
        sendEvent(id, MessageType.RESPONSE_ERROR, response.localizedMessage ?: UNKNOWN_EXCEPTION)
    }

    private fun sendDuration(id: String, duration: Long) {
        sendEvent(id, MessageType.RESPONSE_TIME, duration.toString())
        sendEvent(id, MessageType.RESPONSE_END, REQUEST_END_MARKER)
    }

    private fun sendEvent(id: String, type: MessageType, message: String) {
        dataTransfer.send(id, type, message)
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
            bodyText + DOUBLE_LINE_BREAK + bodyTruncatedMessage(capturedBytes, contentLength)
        } else {
            bodyText
        }
    }

    private fun appendTruncatedMessageIfNeeded(body: String, capturedBytes: Long, totalBytes: Long): String {
        if (totalBytes < 0 || totalBytes <= BODY_BUFFER_SIZE) return body
        return body + DOUBLE_LINE_BREAK + bodyTruncatedMessage(capturedBytes, totalBytes)
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

    /**
     * 根据当前日期时间生成请求 id。
     *
     * 同一毫秒内出现多个请求时递增上一值，避免插件端把请求事件串到同一个生命周期里。
     *
     * @return 字符串格式的请求 id。
     */
    @Synchronized
    private fun generateId(): String {
        var currentTime = format.format(Date()).toLong()
        var previousTime = previousTime.get()
        if (currentTime <= previousTime) {
            currentTime = ++previousTime
        }
        this.previousTime.set(currentTime)
        return currentTime.toString(Character.MAX_RADIX.coerceIn(2, 36))
    }

    companion object {
        /**
         * 默认使用 local-service，避免高频请求在 logcat 中被分片、截断或延迟解析。
         */
        @JvmField
        val DEFAULT_TRANSPORT: ProfilerTransport = ProfilerTransport.LOCAL_SERVICE

        private const val BODY_BUFFER_SIZE = 1024 * 1024
        private const val HEADER_DELIMITER = ':'
        private const val SPACE = ' '
        private const val CONTENT_TYPE = "Content-Type"
        private const val CONTENT_LENGTH = "Content-Length"
        private const val UNKNOWN_EXCEPTION = "Unknown exception"
        private const val REQUEST_END_MARKER = "-->"
        private const val DOUBLE_LINE_BREAK = "\n\n"
    }
}
