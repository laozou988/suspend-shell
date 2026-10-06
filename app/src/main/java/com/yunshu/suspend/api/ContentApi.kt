package com.yunshu.suspend.api

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 一个内容模块：模块标题 + 若干可独立复制的条目 */
data class ContentModule(val moduleTitle: String, val items: List<String>)

/** 悬浮屏取数结果：标题 + 模块列表（对齐小程序 result 页的模块化结构） */
data class ContentData(val title: String, val modules: List<ContentModule>)

/**
 * 内容拉取：悬浮屏仅消费后端给的数据，给什么用什么。
 * 后端返回 JSON 结构（约定）：
 * {
 *   "title":   "结果页标题",
 *   "modules": [
 *     { "moduleTitle": "爆款标题", "items": ["...", "..."] },
 *     ...
 *   ]
 * }
 */
object ContentApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 创建可取消的取数请求（悬浮屏收起时 cancel 断开远程取数） */
    fun newCall(url: String): Call =
        client.newCall(Request.Builder().url(url).get().build())

    fun parse(body: String): ContentData? {
        return try {
            val json = JSONObject(body)
            val title = json.optString("title", "生成结果")
            val modules = ArrayList<ContentModule>()
            val arr = json.optJSONArray("modules")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    val mt = m.optString("moduleTitle", "")
                    val items = ArrayList<String>()
                    val it = m.optJSONArray("items")
                    if (it != null) {
                        for (j in 0 until it.length()) {
                            val s = it.optString(j, "")
                            if (s.isNotEmpty()) items.add(s)
                        }
                    }
                    if (mt.isNotEmpty() || items.isNotEmpty()) {
                        modules.add(ContentModule(mt, items))
                    }
                }
            } else {
                // 兼容旧结构 { title, content }
                val content = json.optString("content", "")
                if (content.isNotEmpty()) modules.add(ContentModule("", content.split("\n")))
            }
            ContentData(title, modules)
        } catch (e: Exception) {
            null
        }
    }
}
