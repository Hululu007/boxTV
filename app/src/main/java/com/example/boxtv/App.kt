package com.example.boxtv

import android.app.Application
import com.example.boxtv.data.local.LocalStore
import com.example.boxtv.data.remote.HostApi
import com.example.boxtv.data.repository.HostRepository
import com.example.boxtv.data.update.UpdateManager
import kotlinx.coroutines.runBlocking

/**
 * 电视端应用入口（全局依赖容器）
 *
 * 持有 HostRepository 单例（细则 5.2 节：UI 层不直接接触网络，
 * 统一通过 repository 访问数据）。启动时加载本地持久化数据
 * （配对主机列表 / 设备 UUID / 播放进度）。
 * 2026-11-07 局域网更新：新增 UpdateManager 单例（下载进度状态跨对话框共享）。
 */
class BoxTvApp : Application() {

    lateinit var repository: HostRepository
        private set

    lateinit var updateManager: UpdateManager
        private set

    override fun onCreate() {
        super.onCreate()
        val store = LocalStore(this)
        // 本地 JSON 文件读取（首启生成默认数据），数据量小，主线程阻塞可接受
        runBlocking { store.load() }
        repository = HostRepository(HostApi(), store)
        updateManager = UpdateManager(this)
    }
}
