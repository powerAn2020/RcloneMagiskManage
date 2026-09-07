package io.github.poweran2020.rclone.manager.data.model

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun JSONObject.optNullableString(name: String): String? {
    if (!has(name) || isNull(name)) return null
    val v = optString(name).trim()
    return if (v.isEmpty() || v.equals("null", ignoreCase = true)) null else v
}

data class RemoteItem(
    val id: String,
    val name: String,
    val type: String,
    val endpoint: String,
    val enabled: Boolean,
    val secretRef: String? = null,
)

typealias RemoteSummary = RemoteItem

fun parseRemotes(raw: String): List<RemoteItem> = runCatching {
    val value = raw.trim()
    val array = when {
        value.startsWith("[") -> JSONArray(value)
        value.startsWith("{") -> {
            val obj = JSONObject(value)
            obj.optJSONArray("remotes") ?: obj.optJSONArray("items") ?: JSONArray()
        }
        else -> JSONArray()
    }
    buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val endpoint = listOf(item.optNullableString("endpoint"), item.optNullableString("path"))
                .firstOrNull { !it.isNullOrBlank() } ?: ""
            add(
                RemoteItem(
                    id = item.optString("id", item.optString("name")),
                    name = item.optString("name", "未命名"),
                    type = item.optString("type", "unknown"),
                    endpoint = endpoint,
                    enabled = item.optBoolean("enabled", true),
                    secretRef = item.optNullableString("secretRef"),
                )
            )
        }
    }
}.getOrDefault(emptyList())

data class FileItem(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modTime: String,
    val mimeType: String,
)

data class FileListResponse(
    val remoteId: String,
    val path: String,
    val items: List<FileItem>,
)

fun parseFileList(raw: String): FileListResponse = runCatching {
    val obj = JSONObject(raw.trim())
    val remoteId = obj.optString("remoteId")
    val path = obj.optString("path", "/")
    val itemsArray = obj.optJSONArray("items") ?: JSONArray()
    val items = buildList {
        for (i in 0 until itemsArray.length()) {
            val item = itemsArray.optJSONObject(i) ?: continue
            val isDir = item.optBoolean("IsDir", false)
            val name = item.optString("Name", item.optString("Path", "unnamed"))
            val itemPath = item.optString("Path", name)
            val size = item.optLong("Size", 0L)
            val modTime = item.optString("ModTime", "")
            val mime = item.optString("MimeType", "")
            add(
                FileItem(
                    name = name,
                    path = itemPath,
                    isDir = isDir,
                    size = size,
                    modTime = modTime,
                    mimeType = mime,
                )
            )
        }
    }
    FileListResponse(remoteId, path, items.sortedWith(compareByDescending<FileItem> { it.isDir }.thenBy { it.name.lowercase() }))
}.getOrElse {
    FileListResponse("", "/", emptyList())
}

data class JobItem(
    val id: String,
    val type: String,
    val status: String,
    val source: String,
    val destination: String,
    val dryRun: Boolean,
    val schedule: String?,
    val nextRunAt: Long?,
)

fun parseJobs(raw: String): List<JobItem> = runCatching {
    val value = raw.trim()
    val array = when {
        value.startsWith("[") -> JSONArray(value)
        value.startsWith("{") -> {
            val obj = JSONObject(value)
            obj.optJSONArray("jobs") ?: obj.optJSONArray("items") ?: JSONArray()
        }
        else -> JSONArray()
    }
    buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            add(
                JobItem(
                    id = item.optString("id"),
                    type = item.optString("type", "copy"),
                    status = item.optString("status", "UNKNOWN"),
                    source = item.optString("source"),
                    destination = item.optString("destination"),
                    dryRun = item.optBoolean("dryRun", false),
                    schedule = item.optNullableString("schedule"),
                    nextRunAt = if (item.has("nextRunAt") && !item.isNull("nextRunAt")) item.optLong("nextRunAt") else null,
                )
            )
        }
    }
}.getOrDefault(emptyList())

data class JobRunItem(
    val id: String,
    val state: String,
    val rcloneJobId: Long?,
    val pid: Long?,
    val startedAt: Long?,
    val finishedAt: Long?,
    val transferredBytes: Long?,
    val totalBytes: Long?,
    val transferredFiles: Long?,
    val totalFiles: Long?,
    val errorCount: Long?,
    val errorCode: String?,
    val errorMessage: String?,
)

fun parseJobRuns(raw: String): List<JobRunItem> = runCatching {
    val array = JSONArray(raw.trim())
    buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            add(
                JobRunItem(
                    id = item.optString("id"),
                    state = item.optString("state", "UNKNOWN"),
                    rcloneJobId = if (item.has("rcloneJobId") && !item.isNull("rcloneJobId")) item.optLong("rcloneJobId") else null,
                    pid = if (item.has("pid") && !item.isNull("pid")) item.optLong("pid") else null,
                    startedAt = if (item.has("startedAt") && !item.isNull("startedAt")) item.optLong("startedAt") else null,
                    finishedAt = if (item.has("finishedAt") && !item.isNull("finishedAt")) item.optLong("finishedAt") else null,
                    transferredBytes = if (item.has("transferredBytes") && !item.isNull("transferredBytes")) item.optLong("transferredBytes") else null,
                    totalBytes = if (item.has("totalBytes") && !item.isNull("totalBytes")) item.optLong("totalBytes") else null,
                    transferredFiles = if (item.has("transferredFiles") && !item.isNull("transferredFiles")) item.optLong("transferredFiles") else null,
                    totalFiles = if (item.has("totalFiles") && !item.isNull("totalFiles")) item.optLong("totalFiles") else null,
                    errorCount = if (item.has("errorCount") && !item.isNull("errorCount")) item.optLong("errorCount") else null,
                    errorCode = item.optNullableString("errorCode"),
                    errorMessage = item.optNullableString("errorMessage"),
                )
            )
        }
    }
}.getOrDefault(emptyList())

data class MountProfileItem(
    val id: String,
    val name: String,
    val remoteId: String,
    val remoteName: String? = null,
    val remotePath: String,
    val mountPoint: String,
    val cacheDir: String,
    val status: String,
    val pid: Long?,
    val readOnly: Boolean,
    val cacheMode: String,
    val cacheMaxSize: String,
    val cacheMaxAge: String,
    val enabled: Boolean,
)

fun parseMounts(raw: String): List<MountProfileItem> = runCatching {
    val array = JSONArray(raw.trim())
    buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            add(
                MountProfileItem(
                    id = item.optString("id"),
                    name = item.optString("name", "未命名挂载"),
                    remoteId = item.optString("remoteId"),
                    remoteName = item.optNullableString("remoteName"),
                    remotePath = item.optString("remotePath", "/"),
                    mountPoint = item.optString("mountPoint"),
                    cacheDir = item.optString("cacheDir"),
                    status = item.optString("status", "STOPPED"),
                    pid = if (item.has("pid") && !item.isNull("pid")) item.optLong("pid") else null,
                    readOnly = item.optBoolean("readOnly", false),
                    cacheMode = item.optString("cacheMode", "full"),
                    cacheMaxSize = item.optString("cacheMaxSize", "32G"),
                    cacheMaxAge = item.optString("cacheMaxAge", "36h"),
                    enabled = item.optBoolean("enabled", false),
                )
            )
        }
    }
}.getOrDefault(emptyList())

data class CryptProfileItem(
    val id: String,
    val name: String,
    val remoteId: String,
    val remoteName: String? = null,
    val remotePath: String,
    val passwordConfigured: Boolean,
)

fun parseCrypts(raw: String): List<CryptProfileItem> = runCatching {
    val array = JSONArray(raw.trim())
    buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            add(
                CryptProfileItem(
                    id = item.optString("id"),
                    name = item.optString("name", "Crypt"),
                    remoteId = item.optString("remoteId"),
                    remoteName = item.optNullableString("remoteName"),
                    remotePath = item.optString("remotePath", "/"),
                    passwordConfigured = item.optBoolean("passwordConfigured", false),
                )
            )
        }
    }
}.getOrDefault(emptyList())

data class ClientItem(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val status: String = "ACTIVE",
    val createdAt: Long,
    val lastUsedAt: Long?,
    val isCurrent: Boolean = false,
)

fun parseClients(raw: String): List<ClientItem> = runCatching {
    val array = JSONArray(raw.trim())
    buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val status = item.optString("status", if (item.optBoolean("enabled", true)) "ACTIVE" else "REVOKED")
            add(
                ClientItem(
                    id = item.optString("id"),
                    name = item.optString("name", "未知客户端"),
                    enabled = status == "ACTIVE",
                    status = status,
                    createdAt = item.optLong("createdAt"),
                    lastUsedAt = if (item.has("lastSeenAt") && !item.isNull("lastSeenAt")) {
                        item.optLong("lastSeenAt")
                    } else if (item.has("lastUsedAt") && !item.isNull("lastUsedAt")) {
                        item.optLong("lastUsedAt")
                    } else null,
                    isCurrent = item.optBoolean("isCurrent", false),
                )
            )
        }
    }
}.getOrDefault(emptyList())

data class ClientGrantItem(
    val id: Long,
    val scope: String,
    val resource: String,
    val createdAt: Long,
    val expiresAt: Long?,
)

fun parseGrants(raw: String): List<ClientGrantItem> = runCatching {
    val array = JSONArray(raw.trim())
    buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            add(
                ClientGrantItem(
                    id = item.optLong("id"),
                    scope = item.optString("scope"),
                    resource = item.optString("resource", "*"),
                    createdAt = item.optLong("createdAt"),
                    expiresAt = if (item.has("expiresAt") && !item.isNull("expiresAt")) item.optLong("expiresAt") else null,
                )
            )
        }
    }
}.getOrDefault(emptyList())

data class SystemInfoItem(
    val service: String,
    val rcloneVersion: String,
    val gatewayVersion: String,
    val apiVersion: String,
    val root: Boolean,
    val lanEnabled: Boolean,
    val mtlsRequired: Boolean,
)

fun parseSystemInfo(raw: String): SystemInfoItem = runCatching {
    val obj = JSONObject(raw.trim())
    val rcloneVer = when {
        obj.has("rcloneVersion") && obj.getString("rcloneVersion").isNotBlank() -> obj.getString("rcloneVersion")
        obj.has("rclone_version") && obj.getString("rclone_version").isNotBlank() -> obj.getString("rclone_version")
        else -> "未知"
    }
    val gwVer = when {
        obj.has("gatewayVersion") && obj.getString("gatewayVersion").isNotBlank() -> obj.getString("gatewayVersion")
        obj.has("gateway_version") && obj.getString("gateway_version").isNotBlank() -> obj.getString("gateway_version")
        else -> "1.1.0"
    }
    val apiVer = when {
        obj.has("apiVersion") && obj.getString("apiVersion").isNotBlank() -> obj.getString("apiVersion")
        obj.has("api_version") && obj.getString("api_version").isNotBlank() -> obj.getString("api_version")
        else -> "1.1.0"
    }
    val lan = if (obj.has("lanEnabled")) obj.optBoolean("lanEnabled", false) else obj.optBoolean("lan_enabled", false)
    val mtls = if (obj.has("mtlsRequired")) obj.optBoolean("mtlsRequired", false) else obj.optBoolean("mtls_required", false)
    SystemInfoItem(
        service = obj.optString("service", "rclone-gateway"),
        rcloneVersion = if (rcloneVer == "unavailable") "未知" else rcloneVer,
        gatewayVersion = gwVer,
        apiVersion = apiVer,
        root = obj.optBoolean("root", false),
        lanEnabled = lan,
        mtlsRequired = mtls,
    )
}.getOrElse {
    SystemInfoItem("rclone-gateway", "未知", "1.1.0", "1.1.0", root = false, lanEnabled = false, mtlsRequired = false)
}

data class SystemSettingsItem(
    val logRetentionDays: Long,
    val logMaxBytes: Long,
    val cacheMaxBytes: Long,
    val maxConcurrentJobs: Long,
)

fun parseSystemSettings(raw: String): SystemSettingsItem = runCatching {
    val obj = JSONObject(raw.trim())
    SystemSettingsItem(
        logRetentionDays = obj.optLong("logRetentionDays", 14),
        logMaxBytes = obj.optLong("logMaxBytes", 10 * 1024 * 1024),
        cacheMaxBytes = obj.optLong("cacheMaxBytes", 32L * 1024 * 1024 * 1024),
        maxConcurrentJobs = obj.optLong("maxConcurrentJobs", 2),
    )
}.getOrElse {
    SystemSettingsItem(14, 10 * 1024 * 1024, 32L * 1024 * 1024 * 1024, 2)
}

data class BackupItem(
    val name: String,
    val timestamp: Long,
    val size: Long,
)

fun parseBackups(raw: String): List<BackupItem> = runCatching {
    val array = JSONArray(raw.trim())
    buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            add(
                BackupItem(
                    name = item.optString("name"),
                    timestamp = item.optLong("timestamp"),
                    size = item.optLong("size", 0L),
                )
            )
        }
    }
}.getOrDefault(emptyList())

data class AuditLogItem(
    val id: Long,
    val timestamp: Long,
    val clientId: String?,
    val uid: Long?,
    val operation: String,
    val resource: String?,
    val remoteId: String?,
    val pathHash: String?,
    val result: String,
    val errorCode: String?,
    val latencyMs: Long?,
)

fun parseAuditLogs(raw: String): List<AuditLogItem> = runCatching {
    val array = JSONArray(raw.trim())
    buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            add(
                AuditLogItem(
                    id = item.optLong("id"),
                    timestamp = item.optLong("timestamp"),
                    clientId = item.optNullableString("clientId"),
                    uid = if (item.has("uid") && !item.isNull("uid")) item.optLong("uid") else null,
                    operation = item.optString("operation"),
                    resource = item.optNullableString("resource"),
                    remoteId = item.optNullableString("remoteId"),
                    pathHash = item.optNullableString("pathHash"),
                    result = item.optString("result", "UNKNOWN"),
                    errorCode = item.optNullableString("errorCode"),
                    latencyMs = if (item.has("latencyMs") && !item.isNull("latencyMs")) item.optLong("latencyMs") else null,
                )
            )
        }
    }
}.getOrDefault(emptyList())

data class MigrationStatusItem(
    val alreadyMigrated: Boolean,
    val migratedJobs: Int,
    val errorCount: Int,
)

fun parseMigrationStatus(raw: String): MigrationStatusItem = runCatching {
    val obj = JSONObject(raw.trim())
    MigrationStatusItem(
        alreadyMigrated = obj.optBoolean("alreadyMigrated", false),
        migratedJobs = obj.optInt("migratedJobs", 0),
        errorCount = obj.optInt("errorCount", 0),
    )
}.getOrElse {
    MigrationStatusItem(alreadyMigrated = false, migratedJobs = 0, errorCount = 0)
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

fun formatEpochTime(epochSecondsOrMillis: Long): String {
    if (epochSecondsOrMillis <= 0) return "-"
    val millis = if (epochSecondsOrMillis < 100_000_000_000L) epochSecondsOrMillis * 1000 else epochSecondsOrMillis
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(millis))
}

data class LocalFileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val lastModified: Long = 0L
)

