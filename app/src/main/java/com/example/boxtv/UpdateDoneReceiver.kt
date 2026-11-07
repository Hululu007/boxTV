package com.example.boxtv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.example.boxtv.ui.main.MainActivity

/**
 * 应用更新完成接收器（2026-11-07 局域网更新功能）
 *
 * 电视端从电脑端下载安装包并触发系统安装器后，覆盖安装会杀掉本应用进程；
 * 系统在新版本安装完成时向本应用广播 MY_PACKAGE_REPLACED，
 * 此处自动重启应用（需求 6：安装完成后引导用户重启完成版本迭代），
 * 并在新版本首启 Toast 提示更新成功。
 *
 * 说明：Android 10+ 对后台启动 Activity 有严格限制，自动拉起失败时
 * 降级为 Toast 提示（用户手动打开即可），两路径均不阻塞安装流程。
 */
class UpdateDoneReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        // 安装包自动清理（2026-11-07）：新版覆盖安装成功后删除
        // filesDir/updates 下的旧版本 APK 与断点文件，
        // 避免残留旧包被后续更新检测误复用（触发「跳过下载装旧包」的更新逻辑异常）
        (context.applicationContext as? BoxTvApp)?.updateManager?.cleanupInstalledPackage()

        // 更新成功提示（后台限制下 Toast 总是可用）
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        Toast.makeText(
            context,
            "boxTV 已更新到 v${version ?: "新版本"}，正在启动…",
            Toast.LENGTH_LONG
        ).show()

        // 尝试自动重启到主页（新版本首启；失败时用户从桌面手动打开）
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(MainActivity.EXTRA_JUST_UPDATED, true)
                }
            )
        }
    }
}
