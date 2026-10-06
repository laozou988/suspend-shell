package com.yunshu.suspend.api

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 内容拉取：悬浮屏从后端获取最新生成结果。
 * 后端返回 JSON 结构（占位约定）：
 * {
 *   "title":   "文案标题/分镜标题",
 *   "content": "正文内容（多行文本）"
 * }
 */
object ContentApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun fetchLatest(url: String): Pair<String, String>? {
        return try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                val title = json.optString("title", "")
                val content = json.optString("content", "")
                title to content
            }
        } catch (e: Exception) {
            null
        }
    }
}
