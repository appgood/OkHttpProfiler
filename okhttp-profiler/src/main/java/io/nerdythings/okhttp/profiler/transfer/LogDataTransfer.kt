package io.nerdythings.okhttp.profiler.transfer

import android.annotation.SuppressLint
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log

/**
 * 通过 Android logcat 输出 profiler 事件，兼容旧版 Android Studio 插件解析协议。
 */
class LogDataTransfer : DataTransfer {
    private val handlerThread = HandlerThread(
        "OkHttpProfiler",
        Process.THREAD_PRIORITY_BACKGROUND
    ).apply {
        start()
    }
    private val handler = Handler(handlerThread.looper)

    @SuppressLint("LogNotTimber")
    override fun send(id: String, type: MessageType, message: String) {
        val tag = LOG_PREFIX + DELIMITER + id + DELIMITER + type.text
        val parts = splitMessage(message)
        val shouldSlowDown = parts.size > SLOW_DOWN_PARTS_AFTER
        for (part in parts) {
            handler.post {
                if (shouldSlowDown) {
                    sleepBeforeLargeLogPart()
                }
                Log.v(tag, part)
            }
        }
    }

    private fun splitMessage(message: String): List<String> {
        if (message.length <= LOG_LENGTH) return listOf(message)

        val parts = mutableListOf<String>()
        var start = 0
        while (start < message.length) {
            val end = minOf(start + LOG_LENGTH, message.length)
            parts += message.substring(start, end)
            start = end
        }
        return parts
    }

    private fun sleepBeforeLargeLogPart() {
        try {
            Thread.sleep(LARGE_LOG_PART_DELAY_MILLIS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val LOG_LENGTH = 4000
        private const val SLOW_DOWN_PARTS_AFTER = 20
        private const val LARGE_LOG_PART_DELAY_MILLIS = 5L
        private const val LOG_PREFIX = "OKPRFL"
        private const val DELIMITER = "_"
    }
}
