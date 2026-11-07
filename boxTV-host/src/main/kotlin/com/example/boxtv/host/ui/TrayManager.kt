package com.example.boxtv.host.ui

import java.awt.Color
import java.awt.Graphics2D
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage

/**
 * 系统托盘模块（ui 层）
 *
 * 对应细则 6.4 节「托盘」行为：
 * - 窗口关闭时最小化到托盘常驻（由 Main 依据 closeToTray 设置决定）；
 * - 托盘菜单：显示主窗口 / 退出；
 * - 双击托盘图标唤起主窗口。
 *
 * 托盘图标使用程序化绘制的「电视」图案，避免外部图标文件依赖。
 */
object TrayManager {

    /** 安装托盘图标；系统不支持托盘时返回 false（UI 据此禁用相关设置项） */
    fun install(window: Window, showWindow: () -> Unit, exitApp: () -> Unit): Boolean {
        if (!SystemTray.isSupported()) return false
        return runCatching {
            val tray = SystemTray.getSystemTray()

            // 构建右键菜单（AWT EDT 线程回调）
            val popup = PopupMenu().apply {
                add(MenuItem("显示主窗口").apply {
                    addActionListener { showWindow() }
                })
                addSeparator()
                add(MenuItem("退出").apply {
                    addActionListener { exitApp() }
                })
            }

            val icon = TrayIcon(createIconImage(), "boxTV 授权后台", popup).apply {
                isImageAutoSize = true
                // 双击托盘图标唤起主窗口
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        if (e.clickCount == 2) showWindow()
                    }
                })
            }
            tray.add(icon)
            true
        }.getOrDefault(false)
    }

    /** 程序化绘制 32×32 的「电视机」托盘图标 */
    private fun createIconImage(): Image {
        val img = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
        val g: Graphics2D = img.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        // 机身（深蓝圆角矩形）
        g.color = Color(21, 101, 192)
        g.fillRoundRect(2, 4, 28, 20, 8, 8)
        // 屏幕（青色圆角矩形）
        g.color = Color(0, 229, 255)
        g.fillRoundRect(5, 7, 22, 14, 5, 5)
        // 底座
        g.color = Color(21, 101, 192)
        g.fillRect(13, 26, 6, 3)
        g.fillRect(9, 29, 14, 2)
        g.dispose()
        return img
    }
}
