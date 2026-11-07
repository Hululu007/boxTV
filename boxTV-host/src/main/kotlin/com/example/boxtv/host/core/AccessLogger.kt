package com.example.boxtv.host.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 访问日志模块（core 层）
 *
 * 对应细则 6.3 节 AccessLogger：记录电视端设备的所有访问行为
 * （配对/浏览/搜索/播放），内存保留最近 500 条，不落盘（本期）。
 * UI（访问日志 Tab）通过 [entries] StateFlow 订阅实时刷新。
 */
class AccessLogger {

    companion object {
        /** 内存环形缓冲上限：500 条 */
        private const val MAX_ENTRIES = 500
    }

    /**
     * 单条日志
     *
     * @param timestamp 时间戳（毫秒）
     * @param device    设备名（系统内部事件用「系统」）
     * @param action    动作类型：配对 / 浏览 / 搜索 / 播放 / 系统事件等
     * @param detail    详细信息（目标文件夹、文件名等）
     */
    data class AccessLogEntry(
        val timestamp: Long,
        val device: String,
        val action: String,
        val detail: String
    )

    /** 底层环形缓冲（最新条目插入头部） */
    private val deque = ArrayDeque<AccessLogEntry>(MAX_ENTRIES)

    /** 日志快照流，UI 订阅此流实时刷新 */
    private val _entries = MutableStateFlow<List<AccessLogEntry>>(emptyList())
    val entries: StateFlow<List<AccessLogEntry>> = _entries

    /** 写入一条日志（线程安全），超出上限时丢弃最旧条目 */
    fun log(device: String, action: String, detail: String) {
        val entry = AccessLogEntry(System.currentTimeMillis(), device, action, detail)
        synchronized(deque) {
            deque.addFirst(entry)
            while (deque.size > MAX_ENTRIES) {
                deque.removeLast()
            }
            _entries.value = deque.toList() // 发布不可变快照
        }
    }
}
