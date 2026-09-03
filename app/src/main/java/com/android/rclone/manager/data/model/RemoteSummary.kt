package com.android.rclone.manager.data.model

import org.json.JSONArray
import org.json.JSONObject

data class RemoteSummary(
    val id: String,
    val name: String,
    val type: String,
    val endpoint: String,
    val enabled: Boolean,
)

fun parseRemotes(raw: String): List<RemoteSummary> = runCatching {
    val value = raw.trim()
    val array = when {
        value.startsWith("[") -> JSONArray(value)
        value.startsWith("{") -> {
            val objectValue = JSONObject(value)
            objectValue.optJSONArray("remotes") ?: objectValue.optJSONArray("items") ?: JSONArray()
        }
        else -> JSONArray()
    }
    buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val endpoint = item.optString("endpoint", item.optString("path", ""))
            add(RemoteSummary(item.optString("id"), item.optString("name", "未命名"), item.optString("type", "unknown"), endpoint, item.optBoolean("enabled", true)))
        }
    }
}.getOrDefault(emptyList())
