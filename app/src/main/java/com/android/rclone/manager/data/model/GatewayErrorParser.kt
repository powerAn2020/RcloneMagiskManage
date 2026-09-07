package com.android.rclone.manager.data.model

data class FriendlyGatewayError(
    val title: String,
    val suggestion: String,
    val rawDetails: String,
    val isKnown: Boolean = true
)

object GatewayErrorParser {
    fun parse(rawError: String): FriendlyGatewayError {
        val trimmed = rawError.trim()
        val lower = trimmed.lowercase()

        return when {
            // 1. Root 权限未获得或被拒绝
            trimmed.contains("未获得 ROOT 权限") ||
            (lower.contains("permission denied") && (lower.contains("su") || lower.contains("root") || lower.contains("superuser"))) -> {
                FriendlyGatewayError(
                    title = "未获得 ROOT 权限",
                    suggestion = "本应用需要 Root 权限以管理网关及模块。请在 Magisk / KernelSU / APatch 中为本应用授予超级用户 (ROOT) 授权。",
                    rawDetails = trimmed
                )
            }

            // 2. Magisk 模块或核心可执行程序缺失
            lower.contains("inaccessible or not found") ||
            (lower.contains("no such file or directory") && (lower.contains("rclone-gateway") || lower.contains("service.sh") || lower.contains("modules/rclone-manager"))) -> {
                FriendlyGatewayError(
                    title = "未检测到 Magisk 模块核心组件",
                    suggestion = "系统未找到 /data/adb/modules/rclone-manager 组件。请确认已在 Magisk / KernelSU 中正确刷入并启用了 rclone-manager 模块。",
                    rawDetails = trimmed
                )
            }

            // 3. 网关服务未运行 / 离线 / 连接拒绝
            lower.contains("connection refused") || lower.contains("os error 111") -> {
                FriendlyGatewayError(
                    title = "Gateway 网关服务未运行 (离线)",
                    suggestion = "后台网关守护进程处于停止状态。请点击上方的【启动】或【重启】按钮尝试拉起服务。",
                    rawDetails = trimmed
                )
            }

            // 4. 通信 Socket 文件未就绪
            lower.contains("os error 2") || (lower.contains("no such file or directory") && lower.contains("gateway.sock")) || (lower.contains("gateway.sock") && lower.contains("not found")) -> {
                FriendlyGatewayError(
                    title = "网关通信套接字 (Socket) 未就绪",
                    suggestion = "未找到通信套接字 gateway.sock。网关服务可能尚未启动或正在初始化，可尝试点击上方的【启动】或【重启】。",
                    rawDetails = trimmed
                )
            }

            // 5. 权限受限 / SELinux 拦截
            lower.contains("permission denied") || lower.contains("os error 13") -> {
                FriendlyGatewayError(
                    title = "套接字或模块访问权限受限",
                    suggestion = "系统权限不足或被 SELinux 拦截。请检查 Magisk 模块目录权限与 SELinux 状态设置。",
                    rawDetails = trimmed
                )
            }

            // 6. 连接重置 / 管道破裂
            lower.contains("connection reset") || lower.contains("broken pipe") || lower.contains("os error 32") || lower.contains("os error 104") -> {
                FriendlyGatewayError(
                    title = "网关通信连接被重置",
                    suggestion = "与网关的连接意外断开，后台守护进程可能发生了异常退出，建议点击上方的【重启】按钮。",
                    rawDetails = trimmed
                )
            }

            // 7. 请求超时
            lower.contains("timed out") || lower.contains("timeout") || lower.contains("os error 110") -> {
                FriendlyGatewayError(
                    title = "网关请求响应超时",
                    suggestion = "网关服务未在预定时限内响应，可能正在执行高负荷任务或挂起，建议尝试点击【重启】。",
                    rawDetails = trimmed
                )
            }

            // 8. 令牌失效 / 鉴权失败
            lower.contains("unauthorized") || lower.contains("invalid token") || lower.contains("missing bearer") || lower.contains("http 401") || lower.contains("http 403") -> {
                FriendlyGatewayError(
                    title = "网关访问凭证已失效",
                    suggestion = "Token 凭证鉴权未通过。请在下方“安全凭据”区域点击【更换】重新配对凭据。",
                    rawDetails = trimmed
                )
            }

            // 9. 兜底未知异常
            else -> {
                FriendlyGatewayError(
                    title = "网关通信异常",
                    suggestion = "网关通信发生异常。可先尝试点击上方的【启动】或【重启】；如持续异常，请复制下方原始日志进行排查。",
                    rawDetails = trimmed,
                    isKnown = false
                )
            }
        }
    }
}
