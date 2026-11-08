package com.example.boxtv.host.core

import java.util.concurrent.TimeUnit

/**
 * 开机自启管理模块（core 层）
 *
 * 对应细则 6.2 节设置项「[√] 开机自启」：
 * 通过写注册表 HKCU\Software\Microsoft\Windows\CurrentVersion\Run 实现
 * 当前用户级开机自启，无需管理员权限。
 *
 * 2026-11-08 行为修正（仅安装模式写注册表）：
 * 开发模式下（gradlew run / IDE 直接运行）写注册表会把「java.exe + 完整 classpath
 * （含 H 盘开发目录与 gradle 缓存）」写进 Run 键——该条目在卸载 MSI 时不会被清理，
 * 且指向的开发文件随时可能被移动/删除，成为无效残留（实际问题：卸载后残留
 * "boxTV"=java.exe -cp H:\Android\boxTV\... 的启动项）。
 * 因此现在仅在 jpackage 打包形态（exe 启动器运行）下允许开启自启，
 * 开发模式下 enable() 直接拒绝，UI 置灰并提示；disable() 在任意形态下都可用，
 * 便于清理历史遗留的开发模式启动项（幂等删除）。
 *
 * 打包模式下写入的是 exe 启动器路径本身（如 E:\boxTV\boxTVHost.exe），
 * 而非 java -cp 长命令行：路径短且稳定。
 * 2026-11-09 便携化说明：程序为免安装绿色版（无 MSI），Run 键不会随「卸载」
 * 自动清理——用户删除解压目录前建议先在设置中关闭自启；若目录被移动/删除，
 * 该条目指向的路径失效，Windows 启动时会静默忽略，重新开启自启即可更新为新路径。
 */
object AutoStartManager {

    /** 当前用户的 Run 注册表键 */
    private const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"

    /** 注册表值名称 */
    private const val VALUE_NAME = "boxTV"

    /**
     * 是否为 jpackage 打包运行（exe 启动器进程）。
     * 判据：当前进程可执行文件名为 boxTVHost.exe。
     * 开发模式（gradlew run / IDE）下进程名是 java.exe / javaw.exe，返回 false。
     */
    val isPackaged: Boolean by lazy {
        val command = ProcessHandle.current().info().command().orElse("")
        val exeName = command.substringAfterLast('\\').substringAfterLast('/')
        exeName.equals("boxTVHost.exe", ignoreCase = true)
    }

    /** 查询当前是否已开启开机自启（仅打包模式有实际意义） */
    fun isEnabled(): Boolean {
        if (!isPackaged) return false
        val result = runReg("reg", "query", RUN_KEY, "/v", VALUE_NAME) ?: return false
        // reg 输出在中文系统为 GBK，但此处只匹配 ASCII 关键字，与编码无关
        return result.second == 0 &&
            result.first.contains(VALUE_NAME, ignoreCase = true) &&
            result.first.contains("REG_SZ", ignoreCase = true)
    }

    /** 开启开机自启（写入注册表）。开发模式直接返回 false（不写注册表）。 */
    fun enable(): Boolean {
        // 开发模式禁止写入：避免把 java -cp 开发路径写入 Run 键造成卸载残留
        if (!isPackaged) return false
        val command = buildLaunchCommand() ?: return false
        val result = runReg("reg", "add", RUN_KEY, "/v", VALUE_NAME, "/t", "REG_SZ", "/d", command, "/f")
        return result?.second == 0
    }

    /** 关闭开机自启（删除注册表值）。任意运行形态均可调用（幂等清理遗留条目）。 */
    fun disable(): Boolean {
        val result = runReg("reg", "delete", RUN_KEY, "/v", VALUE_NAME, "/f")
        return result?.second == 0
    }

    /**
     * 构建启动命令行（仅打包模式调用）：
     * 直接使用当前 exe 启动器路径（jpackage 应用镜像根目录下的 boxTVHost.exe），
     * 双引号包裹防止安装目录含空格时截断。
     */
    private fun buildLaunchCommand(): String? {
        val exePath = ProcessHandle.current().info().command().orElse(null) ?: return null
        return "\"$exePath\""
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
