package io.nerdythings.okhttp.profiler

import io.nerdythings.okhttp.profiler.transfer.DataTransfer
import io.nerdythings.okhttp.profiler.transfer.LocalServiceDataTransfer
import io.nerdythings.okhttp.profiler.transfer.LogDataTransfer

/**
 * OkHttp Profiler 数据传输方式。
 */
enum class ProfilerTransport {
    /**
     * 通过 Android Studio 插件启动的本地 HTTP 服务发送事件。
     */
    LOCAL_SERVICE,

    /**
     * 通过 logcat 输出旧版 `OKPRFL` 协议事件。
     */
    LOGCAT;

    internal fun createDataTransfer(): DataTransfer {
        return when (this) {
            LOCAL_SERVICE -> LocalServiceDataTransfer()
            LOGCAT -> LogDataTransfer()
        }
    }
}
