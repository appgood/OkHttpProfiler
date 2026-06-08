package io.nerdythings.okhttp.profiler.transfer

/**
 * OkHttp Profiler 事件发送接口。
 *
 * 拦截器只负责采集请求与响应数据，具体发送到 logcat 还是 local-service 由实现类决定。
 */
interface DataTransfer {
    /**
     * 发送单条 profiler 事件。
     *
     * @param id 同一次请求生命周期内共享的请求 id。
     * @param type 事件类型，插件端依赖该值还原请求。
     * @param message 已完成文本化处理的事件内容。
     */
    fun send(id: String, type: MessageType, message: String)
}
