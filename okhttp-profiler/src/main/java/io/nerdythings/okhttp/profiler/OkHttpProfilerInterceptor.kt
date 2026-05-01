package io.nerdythings.okhttp.profiler

import io.nerdythings.okhttp.profiler.transfer.DataTransfer
import io.nerdythings.okhttp.profiler.transfer.LocalServiceDataTransfer
import io.nerdythings.okhttp.profiler.transfer.LogDataTransfer
import okhttp3.Interceptor
import okhttp3.Interceptor.Chain
import okhttp3.Response
import java.io.IOException
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * @author itkacher
 * @since 9/25/18
 */
class OkHttpProfilerInterceptor @JvmOverloads constructor(
    transport: ProfilerTransport = ProfilerTransport.LOCAL_SERVICE
) : Interceptor {
    private val dataTransfer: DataTransfer = when (transport) {
        ProfilerTransport.LOCAL_SERVICE -> LocalServiceDataTransfer()
        ProfilerTransport.LOGCAT -> LogDataTransfer()
    }
    private val format: DateFormat = SimpleDateFormat("ddhhmmssSSS", Locale.US)
    private val previousTime = AtomicLong()

    @Throws(IOException::class)
    override fun intercept(chain: Chain): Response {
        val id = generateId()
        val startTime = System.currentTimeMillis()
        trySend {
            dataTransfer.sendRequest(id, chain.request())
        }
        try {
            val response = chain.proceed(chain.request())
            trySend {
                dataTransfer.sendResponse(id, response)
            }
            return response
        } catch (e: Exception) {
            trySend {
                dataTransfer.sendException(id, e)
            }
            throw e
        } finally {
            trySend {
                dataTransfer.sendDuration(id, System.currentTimeMillis() - startTime)
            }
        }
    }

    private fun trySend(action: () -> Unit) {
        try {
            action()
        } catch (_: Exception) {
        }
    }

    /**
     * Generates unique string id via a day and time
     * Based on a current time.
     * @return string id
     */
    @Synchronized
    private fun generateId(): String {
        var currentTime = format.format(Date()).toLong()
        //Increase time if it the same, as previous (unique id)
        var previousTime = previousTime.get()
        if (currentTime <= previousTime) {
            currentTime = ++previousTime
        }
        this.previousTime.set(currentTime)
        return currentTime.toString(Character.MAX_RADIX.coerceIn(2, 36))
    }
}
