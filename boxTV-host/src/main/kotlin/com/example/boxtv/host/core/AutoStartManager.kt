package com.example.boxtv.host.core

import java.util.concurrent.TimeUnit

/**
 * 开机自启管理模块（core 层）
 *
 * 对应细则 6.2 节设置项「[√] 开机自启」：
 * 通过写注册表 HKCU\Software\Microsoft\Windows\CurrentVersion\Run 实现
 * 当前用户级开机自启，无需管理员权限。
 *
 * 说明：M1-M4 开发阶段通过 Gradle run 启动，注册表中记录的启动命令为
 * 当前进程的 java + classpath（开发模式下的尽力而为）；M5 打包安装后
 * 将以正式 exe 路径写入（届时自动生效为生产形态）。
 */
object AutoStartManager {

    /** 当前用户的 Run 注册表键 */
    private const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"

    /** 注册表值名称 */
    private const val VALUE_NAME = "boxTV"

    /** 查询当前是否已开启开机自启 */
    fun isEnabled(): Boolean {
        val result = runReg("reg", "query", RUN_KEY, "/v", VALUE_NAME) ?: return false
        // reg 输出在中文系统为 GBK，但此处只匹配 ASCII 关键字，与编码无关
        return result.second == 0 &&
            result.first.contains(VALUE_NAME, ignoreCase = true) &&
            result.first.contains("REG_SZ", ignoreCase = true)
    }

    /** 开启开机自启（写入注册表），成功返回 true */
    fun enable(): Boolean {
        val command = buildLaunchCommand() ?: return false
        val result = runReg("reg", "add", RUN_KEY, "/v", VALUE_NAME, "/t", "REG_SZ", "/d", command, "/f")
        return result?.second == 0
    }

    /** 关闭开机自启（删除注册表值），成功返回 true */
    fun disable(): Boolean {
        val result = runReg("reg", "delete", RUN_KEY, "/v", VALUE_NAME, "/f")
        return result?.second == 0
    }

    /**
     * 构建启动命令行：
     * 当前 java 可执行文件 + 完整 classpath + 主类。
     * classpath 含分号分隔的多个路径，必须整体加引号防止路径空格截断。
     */
    private fun buildLaunchCommand(): String? {
        val javaBin = ProcessHandle.current().info().command().orElse(null) ?: return null
        val classpath = System.getProperty("java.class.path") ?: return null
        return "\"$javaBin\" -Dfile.encoding=UTF-8 -cp \"$classpath\" com.example.boxtv.host.MainKt"
    }

    /** 执行 reg 命令，返回 (输出文本, 退出码)；执行失败返回 null */
    private fun runReg(vararg args: String): Pair<String, Int>? {
        return try {
            val process = ProcessBuilder(*args)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
            process.waitFor(5, TimeUnit.SECONDS)
            output to process.exitValue()
        } catch (_: Exception) {
            null
        }
    }
}
