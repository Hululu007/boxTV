package com.example.boxtv.host.core

import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Windows 防火墙引导（core 层）
 *
 * 对应细则风险预案：「Windows 防火墙拦截 8964/8965 → 电脑端检测并引导用户放行」。
 *
 * - 检测：netsh advfirewall firewall show rule 查询放行规则是否存在（查询无需管理员）；
 * - 放行：通过 PowerShell Start-Process -Verb RunAs 弹出 UAC 提权后执行 netsh
 *   添加入站规则（TCP HTTP 端口 + UDP 发现端口），由用户在 UAC 弹窗中确认授权。
 *
 * 说明：直接以普通权限添加防火墙规则会被系统拒绝，因此必须走提权路径；
 * 若用户在 UAC 中取消，规则不会添加，再次点击可重新发起。
 */
class FirewallManager(private val httpPort: Int, private val udpPort: Int) {

    private companion object {
        /** 防火墙规则名（固定，检测与添加保持一致） */
        const val RULE_NAME = "boxTV Host"

        /** netsh/PowerShell 进程超时（毫秒） */
        const val PROC_TIMEOUT_MS = 60_000L
    }

    /**
     * 检测入站放行规则是否已存在。
     * 规则不存在时 netsh 输出「没有与指定标准相匹配的防火墙规则」（中文系统）
     * 或 "No rules match the specified criteria"（英文系统）。
     */
    fun isAllowed(): Boolean = runCatching {
        val proc = ProcessBuilder(
            "netsh", "advfirewall", "firewall", "show", "rule", "name=$RULE_NAME"
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        val finished = proc.waitFor(10, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return false
        }
        val noMatch = out.contains("没有与指定标准相匹配") ||
            out.contains("No rules match", ignoreCase = true)
        !noMatch && out.contains(RULE_NAME)
    }.getOrDefault(false)

    /**
     * 一键放行：弹出 UAC 授权（用户确认）后添加 TCP/UDP 入站放行规则。
     * PowerShell 命令通过 -EncodedCommand（UTF-16LE Base64）传递，避免引号转义问题。
     * @return true 表示提权流程已执行完成（规则是否生效请再调 [isAllowed] 确认，
     *         用户取消 UAC 时 netsh 不会执行）
     */
    fun requestAllow(): Boolean = runCatching {
        // 两条 netsh：放行 TCP(HTTP 服务端口) 与 UDP(发现端口) 的入站流量
        val script = "netsh advfirewall firewall add rule " +
            "name=\"$RULE_NAME\" dir=in action=allow protocol=TCP localport=$httpPort; " +
            "netsh advfirewall firewall add rule " +
            "name=\"$RULE_NAME\" dir=in action=allow protocol=UDP localport=$udpPort"
        val encoded = Base64.getEncoder()
            .encodeToString(script.toByteArray(Charsets.UTF_16LE))
        // Start-Process -Verb RunAs 触发 UAC 提权窗口；-Wait 等待提权进程执行完毕
        val proc = ProcessBuilder(
            "powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command",
            "Start-Process powershell -Verb RunAs -Wait -WindowStyle Hidden " +
                "-ArgumentList '-NoProfile','-EncodedCommand','$encoded'"
        ).redirectErrorStream(true).start()
        proc.inputStream.readBytes() // 消费输出避免阻塞
        proc.waitFor(PROC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }.getOrDefault(false)
}
