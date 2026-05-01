package io.nerdythings.okhttp.profiler.transfer

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.Process
import android.util.Log
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.Sink
import okio.Timeout
import okio.buffer
import java.io.IOException
import java.nio.charset.Charset

class LogDataTransfer : DataTransfer {
    private lateinit var mHandler: Handler

    init {
        val handlerThread: HandlerThread =
            object : HandlerThread("OkHttpProfiler", Process.THREAD_PRIORITY_BACKGROUND) {
                override fun onLooperPrepared() {
                    mHandler = LogBodyHandler(this.looper)
                }
            }
        handlerThread.start()
    }

    @Throws(IOException::class)
    override fun sendRequest(id: String, request: Request) {
        fastLog(id, MessageType.REQUEST_METHOD, request.method)
        fastLog(id, MessageType.REQUEST_URL, request.url.toString())
        fastLog(id, MessageType.REQUEST_TIME, System.currentTimeMillis().toString())

        val body = request.newBuilder().build().body

        body?.let {
            body.contentType()?.let { type ->
                fastLog(
                    id = id,
                    type = MessageType.REQUEST_HEADER,
                    message = CONTENT_TYPE + HEADER_DELIMITER + SPACE + type.toString()
                )
            }
            val contentLength = body.contentLength()
            if (contentLength != -1L) {
                fastLog(
                    id = id,
                    type = MessageType.REQUEST_HEADER,
                    message = CONTENT_LENGTH + HEADER_DELIMITER + SPACE + contentLength
                )
            }
        }

        val headers = request.headers
        for (name in headers.names()) {
            //We have logged them before
            if (CONTENT_TYPE.equals(name, ignoreCase = true)
                || CONTENT_LENGTH.equals(name, ignoreCase = true)
            ) {
                continue
            }
            fastLog(
                id = id,
                type = MessageType.REQUEST_HEADER,
                message = name + HEADER_DELIMITER + SPACE + headers[name]
            )
        }

        body?.let {
            if (!body.isDuplex() && !body.isOneShot()) {
                largeLog(id, MessageType.REQUEST_BODY, readRequestBody(body))
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
        largeLog(id, MessageType.RESPONSE_BODY, bodyText)

        val headers = response.headers
        logWithHandler(id, MessageType.RESPONSE_STATUS, response.code.toString(), 0)
        for (name in headers.names()) {
            logWithHandler(
                id,
                MessageType.RESPONSE_HEADER,
                name + HEADER_DELIMITER + headers[name],
                0
            )
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

    override fun sendException(id: String, response: Exception) {
        logWithHandler(
            id,
            MessageType.RESPONSE_ERROR,
            response.localizedMessage ?: "Unknown exception",
            0
        )
    }

    override fun sendDuration(id: String, duration: Long) {
        logWithHandler(id, MessageType.RESPONSE_TIME, duration.toString(), 0)
        logWithHandler(id, MessageType.RESPONSE_END, "-->", 0)
    }

    @SuppressLint("LogNotTimber")
    private fun fastLog(id: String, type: MessageType, message: String?) {
        message?.let {
            val tag = LOG_PREFIX + DELIMITER + id + DELIMITER + type.text
            Log.v(tag, it)
        }
    }

    private fun logWithHandler(id: String?, type: MessageType, message: String, partsCount: Int) {
        if (!this::mHandler.isInitialized) return
        val handlerMessage = mHandler.obtainMessage()
        val tag = LOG_PREFIX + DELIMITER + id + DELIMITER + type.text
        val bundle = Bundle().apply {
            putString(KEY_TAG, tag)
            putString(KEY_VALUE, message)
            putInt(KEY_PARTS_COUNT, partsCount)
        }
        handlerMessage.data = bundle
        mHandler.sendMessage(handlerMessage)
    }

    private fun largeLog(id: String?, type: MessageType, content: String) {
        val contentLength = content.length
        if (contentLength > LOG_LENGTH) {
            val parts = contentLength / LOG_LENGTH
            for (i in 0..parts) {
                val start = i * LOG_LENGTH
                var end = start + LOG_LENGTH
                if (end > contentLength) {
                    end = contentLength
                }
                logWithHandler(id, type, content.substring(start, end), parts)
            }
        } else {
            logWithHandler(id, type, content, 0)
        }
    }


    private class LogBodyHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            msg.data?.let { bundle ->
                val partsCount = bundle.getInt(KEY_PARTS_COUNT, 0)
                if (partsCount > SLOW_DOWN_PARTS_AFTER) {
                    try {
                        Thread.sleep(5L)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }
                }
                val data = bundle.getString(KEY_VALUE)
                val key = bundle.getString(KEY_TAG)
                if (data != null && key != null) {
                    Log.v(key, data)
                }
            }
        }
    }

    companion object {
        private const val LOG_LENGTH = 4000
        private const val SLOW_DOWN_PARTS_AFTER = 20
        private const val BODY_BUFFER_SIZE = 1024 * 1024
        private const val LOG_PREFIX = "OKPRFL"
        private const val DELIMITER = "_"
        private const val HEADER_DELIMITER = ':'
        private const val SPACE = ' '
        private const val KEY_TAG = "TAG"
        private const val KEY_VALUE = "VALUE"
        private const val KEY_PARTS_COUNT = "PARTS_COUNT"
        private const val CONTENT_TYPE = "Content-Type"
        private const val CONTENT_LENGTH = "Content-Length"
    }
}
