package io.github.poweran2020.rclone.manager

import android.util.Base64
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import io.github.poweran2020.rclone.manager.data.model.LocalFileItem

/** Fixed typed bridge. No raw rclone RC, shell, or user supplied flags. */
class GatewayClient(private val socket: String = "/data/adb/rclone-manage/runtime/gateway.sock") {
    suspend fun health(): Result<String> = request("GET", "/api/v1/system/health")
    suspend fun info(token: String): Result<String> = request("GET", "/api/v1/system/info", token)
    suspend fun safeMode(token: String): Result<String> = request("GET", "/api/v1/system/safe-mode", token)
    suspend fun setSafeMode(enabled: Boolean, token: String): Result<String> =
        request("PUT", "/api/v1/system/safe-mode", token, JSONObject().put("enabled", enabled))
    suspend fun remotes(token: String): Result<String> = request("GET", "/api/v1/remotes", token)
    suspend fun remote(id: String, token: String): Result<String> = request("GET", "/api/v1/remotes/${encode(id)}", token)
    suspend fun jobs(token: String): Result<String> = request("GET", "/api/v1/jobs", token)
    suspend fun mounts(token: String): Result<String> = request("GET", "/api/v1/mounts", token)
    suspend fun auditLogs(token: String): Result<String> = request("GET", "/api/v1/logs/audit", token)
    suspend fun clearLogs(token: String): Result<String> = request("POST", "/api/v1/system/logs/clear", token)
    suspend fun backups(token: String): Result<String> = request("GET", "/api/v1/system/backups", token)
    suspend fun createRemote(name: String, type: String, endpoint: String?, secret: JSONObject?, token: String): Result<String> =
        request("POST", "/api/v1/remotes", token, JSONObject().put("name", name).put("type", type).apply { if (endpoint != null) put("endpoint", endpoint); if (secret != null) put("secret", secret) })

    suspend fun remoteProviders(token: String): Result<String> =
        request("GET", "/api/v1/remotes/providers", token)

    suspend fun importRemoteConfig(configText: String, token: String): Result<String> =
        request("POST", "/api/v1/remotes/import", token, JSONObject().put("config", configText))

    suspend fun importRemote(name: String, type: String, endpoint: String?, token: String): Result<String> =
        request("POST", "/api/v1/remotes/import", token, JSONObject().put("name", name).put("type", type).apply { if (endpoint != null) put("endpoint", endpoint) })

    suspend fun updateRemote(id: String, name: String, type: String, endpoint: String?, token: String, secret: JSONObject? = null): Result<String> =
        request("PUT", "/api/v1/remotes/${encode(id)}", token, JSONObject().put("name", name).put("type", type).apply { if (endpoint != null) put("endpoint", endpoint); if (secret != null) put("secret", secret) })

    suspend fun createJob(type: String, source: String, destination: String, token: String, schedule: String? = null, networkPolicy: String? = null, batteryPolicy: String? = null, dryRun: Boolean = false, options: JSONObject? = null): Result<String> =
        request("POST", "/api/v1/jobs", token, JSONObject().put("type", type).put("source", source).put("destination", destination).put("dryRun", dryRun).apply { if (!schedule.isNullOrBlank()) put("schedule", schedule); if (!networkPolicy.isNullOrBlank()) put("networkPolicy", networkPolicy); if (!batteryPolicy.isNullOrBlank()) put("batteryPolicy", batteryPolicy); if (options != null) put("options", options) })

    suspend fun job(id: String, token: String): Result<String> =
        request("GET", "/api/v1/jobs/${encode(id)}", token)

    suspend fun deleteJob(id: String, token: String): Result<String> =
        request("DELETE", "/api/v1/jobs/${encode(id)}", token)

    suspend fun jobRuns(id: String, token: String): Result<String> =
        request("GET", "/api/v1/jobs/${encode(id)}/runs", token)

    suspend fun jobLog(id: String, token: String): Result<String> =
        request("GET", "/api/v1/jobs/${encode(id)}/log", token)

    suspend fun createMount(name: String, remoteId: String, mountPoint: String, token: String, remotePath: String? = null, cacheDir: String? = null, readOnly: Boolean = false, cacheMode: String? = null, cacheMaxSize: String? = null, cacheMaxAge: String? = null): Result<String> =
        request("POST", "/api/v1/mounts", token, JSONObject().put("name", name).put("remoteId", remoteId).put("mountPoint", mountPoint).put("readOnly", readOnly).apply {
            if (!remotePath.isNullOrBlank()) put("remotePath", remotePath)
            if (!cacheDir.isNullOrBlank()) put("cacheDir", cacheDir)
            if (!cacheMode.isNullOrBlank()) put("cacheMode", cacheMode)
            if (!cacheMaxSize.isNullOrBlank()) put("cacheMaxSize", cacheMaxSize)
            if (!cacheMaxAge.isNullOrBlank()) put("cacheMaxAge", cacheMaxAge)
        })

    suspend fun listFiles(remoteId: String, path: String, token: String): Result<String> =
        request("GET", "/api/v1/files?remoteId=${java.net.URLEncoder.encode(remoteId, "UTF-8")}&path=${java.net.URLEncoder.encode(path, "UTF-8")}", token)

    suspend fun mkdir(remoteId: String, path: String, token: String): Result<String> =
        request("POST", "/api/v1/files/mkdir", token, JSONObject().put("remoteId", remoteId).put("path", path))

    suspend fun upload(localPath: String, remoteTarget: String, token: String): Result<String> =
        request("POST", "/api/v1/files/upload", token, JSONObject().put("source", localPath).put("destination", remoteTarget))

    suspend fun download(remoteTarget: String, localPath: String, token: String): Result<String> =
        request("POST", "/api/v1/files/download", token, JSONObject().put("source", remoteTarget).put("destination", localPath))

    suspend fun copy(source: String, destination: String, token: String): Result<String> =
        request("POST", "/api/v1/files/copy", token, JSONObject().put("source", source).put("destination", destination))

    suspend fun move(source: String, destination: String, token: String): Result<String> =
        request("POST", "/api/v1/files/move", token, JSONObject().put("source", source).put("destination", destination))

    suspend fun createCrypt(name: String, remoteId: String, remotePath: String?, password: String?, token: String): Result<String> =
        request("POST", "/api/v1/crypt", token, JSONObject().put("name", name).put("remoteId", remoteId).apply { if (!remotePath.isNullOrBlank()) put("remotePath", remotePath); if (!password.isNullOrBlank()) put("password", password) })

    suspend fun crypts(token: String): Result<String> = request("GET", "/api/v1/crypt", token)
    suspend fun cryptTest(id: String, token: String): Result<String> = request("POST", "/api/v1/crypt/${encode(id)}/test", token)

    suspend fun testRemote(id: String, token: String): Result<String> =
        request("POST", "/api/v1/remotes/${java.net.URLEncoder.encode(id, "UTF-8")}/test", token)

    suspend fun createBackup(token: String): Result<String> = request("POST", "/api/v1/system/backups", token)
    suspend fun settings(token: String): Result<String> = request("GET", "/api/v1/system/settings", token)
    suspend fun updateSettings(settings: JSONObject, token: String): Result<String> = request("PUT", "/api/v1/system/settings", token, settings)
    suspend fun migrationStatus(token: String): Result<String> = request("GET", "/api/v1/system/migration", token)

    suspend fun deletePreview(remoteId: String, path: String, token: String): Result<String> =
        request("POST", "/api/v1/files/delete", token, JSONObject().put("remoteId", remoteId).put("path", path).put("dryRun", true))

    suspend fun deleteConfirmed(remoteId: String, path: String, confirmationToken: String, token: String): Result<String> =
        request("POST", "/api/v1/files/delete", token, JSONObject().put("remoteId", remoteId).put("path", path).put("dryRun", false).put("confirmationToken", confirmationToken))

    suspend fun pairingComplete(code: String, name: String, publicKey: String): Result<String> =
        request("POST", "/api/v1/security/pairing/complete", body = JSONObject().put("pairingCode", code).put("clientName", name).put("publicKey", publicKey))
    suspend fun pairingStart(): Result<String> = request("POST", "/api/v1/security/pairing/start")

    suspend fun jobAction(id: String, action: String, token: String): Result<String> =
        request("POST", "/api/v1/jobs/${encode(id)}/${requireAction(action, setOf("start", "pause", "resume", "cancel", "retry"))}", token)

    suspend fun mountAction(id: String, action: String, token: String): Result<String> =
        request("POST", "/api/v1/mounts/${encode(id)}/${requireAction(action, setOf("start", "stop", "enable", "disable"))}", token)

    suspend fun getMount(id: String, token: String): Result<String> =
        request("GET", "/api/v1/mounts/${encode(id)}", token)

    suspend fun updateMount(
        id: String,
        name: String,
        remoteId: String,
        mountPoint: String,
        token: String,
        remotePath: String? = null,
        cacheDir: String? = null,
        readOnly: Boolean = false,
        cacheMode: String? = null,
        cacheMaxSize: String? = null,
        cacheMaxAge: String? = null
    ): Result<String> =
        request("PUT", "/api/v1/mounts/${encode(id)}", token, JSONObject().put("name", name).put("remoteId", remoteId).put("mountPoint", mountPoint).put("readOnly", readOnly).apply {
            if (!remotePath.isNullOrBlank()) put("remotePath", remotePath)
            if (!cacheDir.isNullOrBlank()) put("cacheDir", cacheDir)
            if (!cacheMode.isNullOrBlank()) put("cacheMode", cacheMode)
            if (!cacheMaxSize.isNullOrBlank()) put("cacheMaxSize", cacheMaxSize)
            if (!cacheMaxAge.isNullOrBlank()) put("cacheMaxAge", cacheMaxAge)
        })

    suspend fun deleteMount(id: String, token: String): Result<String> =
        request("DELETE", "/api/v1/mounts/${encode(id)}", token)

    /** First call returns a short-lived confirmation token; pass it back to commit deletion. */
    suspend fun remoteDelete(id: String, token: String, confirmationToken: String? = null): Result<String> =
        request("DELETE", "/api/v1/remotes/${java.net.URLEncoder.encode(id, "UTF-8")}", token,
            confirmationToken?.let { JSONObject().put("confirmationToken", it) })

    suspend fun remoteAction(id: String, action: String, token: String): Result<String> =
        request("POST", "/api/v1/remotes/${encode(id)}/${requireAction(action, setOf("enable", "disable"))}", token)

    suspend fun exportRemote(id: String, token: String): Result<String> =
        request("GET", "/api/v1/remotes/${encode(id)}/export", token)

    suspend fun clients(token: String): Result<String> = request("GET", "/api/v1/security/clients", token)
    suspend fun deleteClient(clientId: String, token: String): Result<String> =
        request("DELETE", "/api/v1/security/clients/${encode(clientId)}", token)
    suspend fun enableClient(clientId: String, token: String): Result<String> =
        request("POST", "/api/v1/security/clients/${encode(clientId)}/enable", token)
    suspend fun disableClient(clientId: String, token: String): Result<String> =
        request("POST", "/api/v1/security/clients/${encode(clientId)}/disable", token)
    suspend fun rotateToken(clientId: String, token: String): Result<String> =
        request("POST", "/api/v1/security/clients/${encode(clientId)}/rotate-token", token)
    suspend fun grants(clientId: String, token: String): Result<String> =
        request("GET", "/api/v1/security/clients/${encode(clientId)}/grants", token)
    suspend fun grant(clientId: String, scope: String, resource: String, token: String): Result<String> =
        request("POST", "/api/v1/security/clients/${encode(clientId)}/grants", token, JSONObject().put("scope", scope).put("resource", resource))
    suspend fun revokeGrant(clientId: String, grantId: Long, token: String): Result<String> =
        request("DELETE", "/api/v1/security/clients/${encode(clientId)}/grants/$grantId", token)
    suspend fun remoteAcl(clientId: String, remoteId: String, permissions: String, prefix: String, token: String): Result<String> {
        val permArray = org.json.JSONArray()
        permissions.split(',').map { it.trim() }.filter { it.isNotBlank() }.forEach { permArray.put(it) }
        val body = JSONObject()
            .put("remoteId", remoteId)
            .put("permissions", permArray)
            .put("allowedPrefix", prefix)
        return request("POST", "/api/v1/security/clients/${encode(clientId)}/remote-acl", token, body)
    }

    suspend fun startGatewayService(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val start = Shell.cmd("sh /data/adb/modules/rclone-manager/service.sh").exec()
            check(start.isSuccess) { start.err.joinToString("\n").ifBlank { "启动 Gateway 服务失败" } }
            "已启动 Gateway 守护进程"
        }
    }

    suspend fun stopGatewayService(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cmd = listOf(
                "/data/adb/modules/rclone-manager/bin/rclone-gateway stop --root /data/adb/rclone-manage 2>/dev/null || true",
                "kill $(cat /data/adb/rclone-manage/runtime/gateway-watchdog.pid 2>/dev/null) 2>/dev/null || true",
                "kill -9 $(cat /data/adb/rclone-manage/runtime/gateway.pid 2>/dev/null) 2>/dev/null || true",
                "pkill -9 -f 'rclone-gateway serve' 2>/dev/null || true",
                "rm -f /data/adb/rclone-manage/runtime/gateway.sock /data/adb/rclone-manage/runtime/gateway.pid /data/adb/rclone-manage/runtime/gateway-watchdog.pid"
            ).joinToString("; ")
            val res = Shell.cmd(cmd).exec()
            check(res.isSuccess) { res.err.joinToString("\n").ifBlank { "停止 Gateway 服务失败" } }
            "已停止 Gateway 服务"
        }
    }

    suspend fun restartGatewayService(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            stopGatewayService()
            kotlinx.coroutines.delay(600)
            val start = Shell.cmd("sh /data/adb/modules/rclone-manager/service.sh").exec()
            check(start.isSuccess) { start.err.joinToString("\n").ifBlank { "重启 Gateway 服务失败" } }
            "已重启 Gateway 服务"
        }
    }

    suspend fun ensureServiceRunning(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val check = Shell.cmd("ps -A | grep rclone-gateway").exec()
            if (!check.isSuccess || check.out.isEmpty()) {
                val start = Shell.cmd("sh /data/adb/modules/rclone-manager/service.sh").exec()
                check(start.isSuccess) { start.err.joinToString("\n").ifBlank { "启动 Gateway 服务失败" } }
                "已成功拉起 Gateway 守护进程"
            } else {
                "Gateway 守护进程运行中"
            }
        }
    }

    suspend fun autoPair(clientName: String = "RcloneManagerApp"): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val startRes = pairingStart().getOrThrow()
            val code = JSONObject(startRes).getString("pairingCode")
            val completeRes = pairingComplete(code, clientName, "device").getOrThrow()
            val token = JSONObject(completeRes).getString("token")
            token
        }
    }

    suspend fun listLocalDirectory(dirPath: String): List<LocalFileItem> = withContext(Dispatchers.IO) {
        val cleanPath = if (dirPath.isBlank()) "/data/media/0/Download" else dirPath.trimEnd('/').ifEmpty { "/" }
        
        // 1. Try standard Java File
        val dir = java.io.File(cleanPath)
        val files = runCatching { dir.listFiles() }.getOrNull()
        if (files != null) {
            return@withContext files.map { f ->
                LocalFileItem(
                    name = f.name,
                    path = f.absolutePath,
                    isDirectory = f.isDirectory,
                    size = if (f.isDirectory) 0L else f.length(),
                    lastModified = f.lastModified()
                )
            }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        }

        // 2. Fallback to root shell
        val outList = mutableListOf<String>()
        val cmd = "ls -la ${quote(cleanPath)}"
        val result = Shell.cmd(cmd).to(outList).exec()
        if (!result.isSuccess) return@withContext emptyList()

        val list = mutableListOf<LocalFileItem>()
        for (line in outList) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("total ")) continue
            val parts = trimmed.split("\\s+".toRegex())
            if (parts.size >= 8) {
                val perms = parts[0]
                val isDir = perms.startsWith("d")
                val size = parts[4].toLongOrNull() ?: 0L
                val name = parts.subList(7, parts.size).joinToString(" ")
                if (name == "." || name == "..") continue
                val fullPath = if (cleanPath == "/") "/$name" else "$cleanPath/$name"
                list.add(
                    LocalFileItem(
                        name = name,
                        path = fullPath,
                        isDirectory = isDir,
                        size = if (isDir) 0L else size,
                        lastModified = 0L
                    )
                )
            }
        }
        list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
    private suspend fun request(method: String, path: String, token: String? = null, body: JSONObject? = null): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(method in setOf("GET", "POST", "PUT", "DELETE"))
            require(path.startsWith("/api/v1/") && !path.contains("..") && !path.any { it == '\u0000' || it == '\r' || it == '\n' })
            require(body == null || body.toString().toByteArray().size <= 64 * 1024)
            val args = mutableListOf("/data/adb/modules/rclone-manager/bin/rclone-gateway", "request", "--socket", socket, "--method", method, "--path", path)
            if (token != null) { require(!token.any { it == '\u0000' || it == '\r' || it == '\n' }); args += listOf("--token", token) }
            if (body != null) args += listOf("--body-base64", Base64.encodeToString(body.toString().toByteArray(), Base64.NO_WRAP))
            val outList = mutableListOf<String>()
            val errList = mutableListOf<String>()
            val result = Shell.cmd(args.joinToString(" ") { quote(it) }).to(outList, errList).exec()
            val output = outList.joinToString("\n")
            val err = errList.joinToString("\n")
            android.util.Log.i("RcloneGateway", "req: $method $path -> code=${result.code}, out len=${output.length}, err=$err")
            val errMsg = if (output.isNotBlank()) {
                val parsed = runCatching {
                    val obj = JSONObject(output)
                    obj.optString("message").ifBlank { obj.optString("code") }
                }.getOrNull()
                if (!parsed.isNullOrBlank()) parsed else err.ifBlank { output }
            } else {
                if (Shell.getCachedShell()?.isRoot == false) {
                    "未获得 ROOT 权限，请在 Magisk / KernelSU 中为本应用开启授权"
                } else {
                    err.ifBlank { "gateway request failed (code=${result.code})" }
                }
            }
            check(result.isSuccess) { errMsg }
            output
        }
    }

    private fun requireAction(action: String, allowed: Set<String>): String =
        action.trim().also { require(it in allowed) }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    data class LanConfig(
        val enabled: Boolean,
        val host: String = "0.0.0.0",
        val port: Int = 8443,
        val certPath: String = "/data/adb/rclone-manage/keys/server.pem",
        val keyPath: String = "/data/adb/rclone-manage/keys/server.key",
        val rawConf: String = ""
    )

    suspend fun getLanConfig(): LanConfig = withContext(Dispatchers.IO) {
        val confFile = "/data/adb/rclone-manage/runtime/lan.conf"
        val check = Shell.cmd("[ -f '$confFile' ] && cat '$confFile'").exec()
        if (check.isSuccess && check.out.isNotEmpty()) {
            val content = check.out.joinToString("\n")
            var addr = "0.0.0.0:8443"
            var cert = ""
            var key = ""
            check.out.forEach { line ->
                val parts = line.split('=', limit = 2)
                if (parts.size == 2) {
                    when (parts[0].trim()) {
                        "LAN_ADDR" -> addr = parts[1].trim()
                        "TLS_CERT" -> cert = parts[1].trim()
                        "TLS_KEY" -> key = parts[1].trim()
                    }
                }
            }
            val port = addr.substringAfterLast(':', "8443").toIntOrNull() ?: 8443
            val host = addr.substringBeforeLast(':', "0.0.0.0")
            LanConfig(enabled = true, host = host, port = port, certPath = cert, keyPath = key, rawConf = content)
        } else {
            LanConfig(enabled = false)
        }
    }

    suspend fun updateLanConfig(enabled: Boolean, port: Int = 8443): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val confPath = "/data/adb/rclone-manage/runtime/lan.conf"
            val keysDir = "/data/adb/rclone-manage/keys"
            val certPath = "$keysDir/server.pem"
            val keyPath = "$keysDir/server.key"

            if (enabled) {
                // Ensure keys exist
                val checkKeys = Shell.cmd("[ -f '$certPath' ] && [ -f '$keyPath' ]").exec()
                if (!checkKeys.isSuccess || checkKeys.out.isEmpty()) {
                    val certPair = io.github.poweran2020.rclone.manager.util.TlsCertUtil.generateSelfSignedCert()
                    Shell.cmd("mkdir -p '$keysDir' && chmod 700 '$keysDir'").exec()
                    val writeCertCmd = "cat << 'EOF' > '$certPath'\n${certPair.certPem}\nEOF\nchmod 600 '$certPath'"
                    val writeKeyCmd = "cat << 'EOF' > '$keyPath'\n${certPair.keyPem}\nEOF\nchmod 600 '$keyPath'"
                    Shell.cmd(writeCertCmd).exec()
                    Shell.cmd(writeKeyCmd).exec()
                }
                val confContent = "LAN_ADDR=0.0.0.0:$port\nTLS_CERT=$certPath\nTLS_KEY=$keyPath\n"
                val writeConfCmd = "cat << 'EOF' > '$confPath'\n$confContent\nEOF\nchmod 600 '$confPath'"
                val writeRes = Shell.cmd(writeConfCmd).exec()
                check(writeRes.isSuccess) { "写入 lan.conf 失败" }
                restartGatewayService()
                "已成功启用 LAN 局域网监听 (端口: $port, TLS已加密)，Gateway 已自动重启生效"
            } else {
                Shell.cmd("rm -f '$confPath'").exec()
                restartGatewayService()
                "已关闭 LAN 局域网监听，Gateway 已重启生效"
            }
        }
    }

    suspend fun getDeviceIpAddresses(): List<String> = withContext(Dispatchers.IO) {
        val ips = linkedSetOf<String>()

        // 1. Java NetworkInterface with defensive exception handling
        runCatching {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                for (intf in interfaces) {
                    val isLoopback = runCatching { intf.isLoopback }.getOrDefault(false)
                    if (isLoopback) continue
                    val isUp = runCatching { intf.isUp }.getOrDefault(true)
                    if (!isUp) continue
                    val addrs = runCatching { intf.inetAddresses }.getOrNull() ?: continue
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            val host = addr.hostAddress
                            if (!host.isNullOrBlank() && host != "0.0.0.0" && !host.startsWith("127.")) {
                                ips.add(host)
                            }
                        }
                    }
                }
            }
        }

        // 2. Root shell fallback (ip -4 -o addr show)
        if (ips.isEmpty()) {
            runCatching {
                val res = Shell.cmd("ip -4 -o addr show").exec()
                if (res.isSuccess) {
                    for (line in res.out) {
                        val match = Regex("""inet\s+([0-9.]+)/\d+""").find(line)
                        if (match != null) {
                            val ip = match.groupValues[1]
                            if (ip != "127.0.0.1" && ip != "0.0.0.0") {
                                ips.add(ip)
                            }
                        }
                    }
                }
            }
        }

        // 3. Routing fallback (ip route get 1.1.1.1)
        if (ips.isEmpty()) {
            runCatching {
                val res = Shell.cmd("ip route get 1.1.1.1").exec()
                if (res.isSuccess) {
                    for (line in res.out) {
                        val match = Regex("""src\s+([0-9.]+)""").find(line)
                        if (match != null) {
                            val ip = match.groupValues[1]
                            if (ip != "127.0.0.1" && ip != "0.0.0.0") {
                                ips.add(ip)
                            }
                        }
                    }
                }
            }
        }

        ips.toList()
    }
}
