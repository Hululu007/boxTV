package com.example.boxtv.ui.common

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

/**
 * 当前网络名称解析工具（ui/common 层）。
 *
 * 主页头部 / 浏览页头部 / 设置页共用：
 * - 优先读取 Wi-Fi SSID（需系统授权，电视盒子上常取不到）；
 * - 取不到（占位符 / 异常 / 未连 Wi-Fi）时退化为网络类型描述
 *   （以太网 / Wi-Fi / 移动网络），保证状态栏始终有意义；
 * - 全程 try 包裹，权限缺失等异常不影响页面。
 */
object NetworkName {

    /** 解析当前网络名称（同步 IO 级调用，调用方需在子线程执行） */
    fun resolve(context: Context): String {
        return try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val ssid = wifi?.connectionInfo?.ssid?.removeSurrounding("\"")?.trim()
            if (!ssid.isNullOrBlank() && !ssid.contains("unknown", ignoreCase = true)) ssid
            else networkTypeName(context)
        } catch (e: Exception) {
            networkTypeName(context)
        }
    }

    /** 网络类型兜底描述：以太网（电视盒子常见）/ Wi-Fi / 移动网络 / 未联网 */
    private fun networkTypeName(context: Context): String = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> "未联网"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            else -> "移动网络"
        }
    } catch (e: Exception) {
        "未知"
    }
}
