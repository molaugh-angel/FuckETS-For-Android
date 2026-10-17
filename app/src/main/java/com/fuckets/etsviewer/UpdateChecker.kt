package com.fuckets.etsviewer

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 自动更新检查：请求托管在 GitHub Pages 上的版本 JSON，
 * 与当前版本号比对。网络请求在 IO 线程进行（调用方负责切线程）。
 *
 * JSON 结构（已确认）：
 * ```json
 * { "last-version": "2.1", "update-log": "1.xxx，2.xxx" }
 * ```
 * 可选字段："url" / "download-url" —— 更新包/发布页地址，缺省时用兜底发布页。
 */
object UpdateChecker {

    private const val CHECK_URL = "https://molaugh-angel.github.io/appver/FuckETS.json"

    /** JSON 未提供下载地址时的兜底发布页 */
    private const val FALLBACK_PAGE = "https://github.com/molaugh-angel/FuckEts/releases"

    private const val TIMEOUT_MS = 8_000

    data class Result(
        val latestVersion: String,
        val updateLog: String,
        val url: String
    )

    /** 阻塞式检查更新；网络失败/解析失败返回 null（已写日志） */
    fun check(): Result? {
        AppLog.i("Update", "开始检查更新：$CHECK_URL")
        val conn = (URL(CHECK_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // 防止 CDN 缓存旧版本
            setRequestProperty("Cache-Control", "no-cache")
        }
        return try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                AppLog.w("Update", "检查更新失败：HTTP $code")
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val latest = json.optString("last-version")
            if (latest.isBlank()) {
                AppLog.w("Update", "版本 JSON 缺少 last-version 字段：${AppLog.truncate(body, 200)}")
                return null
            }
            val url = json.optString("url").ifBlank {
                json.optString("download-url").ifBlank { FALLBACK_PAGE }
            }
            Result(latest, json.optString("update-log", ""), url).also {
                AppLog.i("Update", "检查完成：最新版本 ${it.latestVersion}，当前版本 ${BuildConfig.VERSION_NAME}")
            }
        } catch (e: Exception) {
            AppLog.w("Update", "检查更新异常：${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    /** 点分版本号比较："2.10" > "2.9"；格式异常时退化为字符串比较 */
    fun isNewer(latest: String, current: String): Boolean {
        val a = latest.split(".").map { it.toIntOrNull() ?: return latest != current }
        val b = current.split(".").map { it.toIntOrNull() ?: return latest != current }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
