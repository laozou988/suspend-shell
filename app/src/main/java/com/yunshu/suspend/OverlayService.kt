package com.yunshu.suspend

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.yunshu.suspend.api.ContentApi
import com.yunshu.suspend.api.ContentData
import com.yunshu.suspend.api.ContentModule
import okhttp3.Call

/**
 * 悬浮屏服务：常驻任意应用上层。
 * - 悬浮球：常驻入口，可随心拖拽；首次点击 = 远程取数并弹出悬浮屏
 * - 悬浮屏：贴底底部抽屉，右上角「切换」图标承担四态循环：
 *   ① 贴底 1/4 → ② 展开 1/2 → ③ 折叠上半屏（下半屏空出）→ 收起（关屏回悬浮球）
 * - 复制：跟随内容，每条内容自带「复制」按钮（对齐小程序 result 页）
 * - 数据：首次点悬浮球取数，点收起断开远程取数，避免常挂阻塞
 */
class OverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "overlay_channel"
        private const val NOTIFY_ID = 1001
        private const val STATE_QUARTER = 0
        private const val STATE_HALF = 1
        private const val STATE_TOP = 2

        var isRunning = false
            private set

        // 后端内容接口（占位：后端就绪后替换为真实地址）
        var contentUrl = "https://your-backend.example.com/api/latest-content"
    }

    private var wm: WindowManager? = null
    private var capsuleView: View? = null
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var tvPanelTitle: TextView? = null
    private var llContent: LinearLayout? = null
    private var state = STATE_QUARTER
    private var pendingCall: Call? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFY_ID, buildNotification())
        showCapsule()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        pendingCall?.cancel()
        removeView(capsuleView)
        removeView(panelView)
        capsuleView = null
        panelView = null
        super.onDestroy()
    }

    private fun removeView(v: View?) {
        v?.let { runCatching { wm?.removeView(it) } }
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL_ID, "悬浮屏", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("云枢悬浮屏运行中")
            .setContentText("点按返回设置")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    // ===== 悬浮球（常驻、可随心拖拽）=====
    private fun showCapsule() {
        if (capsuleView != null) return
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_view, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 500
        }
        wm?.addView(view, params)
        capsuleView = view
        bindDrag(view, params)
        // 首次点击悬浮球 = 远程取数并弹出悬浮屏
        view.setOnClickListener { showPanel() }
    }

    // ===== 悬浮屏（贴底底部抽屉，四态循环）=====
    private fun showPanel() {
        if (panelView != null) return
        state = STATE_QUARTER
        val dm: DisplayMetrics = resources.displayMetrics
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)
        val params = WindowManager.LayoutParams(
            (dm.widthPixels * 0.92).toInt(),
            (dm.heightPixels / 4).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 0
        }
        wm?.addView(view, params)
        panelView = view
        panelParams = params
        view.setBackgroundResource(R.drawable.bg_panel_bottom)

        tvPanelTitle = view.findViewById(R.id.tvPanelTitle)
        llContent = view.findViewById(R.id.llContent)
        view.findViewById<ImageView>(R.id.btnToggle).setOnClickListener { toggleState() }

        loadContent()
    }

    private fun toggleState() {
        when (state) {
            STATE_QUARTER -> applyState(STATE_HALF)
            STATE_HALF -> applyState(STATE_TOP)
            STATE_TOP -> hidePanel()   // 收起 = 关屏，回到悬浮球
            else -> applyState(STATE_QUARTER)
        }
    }

    private fun applyState(next: Int) {
        state = next
        val dm: DisplayMetrics = resources.displayMetrics
        val params = panelParams ?: return
        val view = panelView ?: return
        params.width = (dm.widthPixels * 0.92).toInt()
        params.x = 0
        params.y = 0
        when (next) {
            STATE_QUARTER -> {
                params.height = dm.heightPixels / 4
                params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                view.setBackgroundResource(R.drawable.bg_panel_bottom)
            }
            STATE_HALF -> {
                params.height = dm.heightPixels / 2
                params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                view.setBackgroundResource(R.drawable.bg_panel_bottom)
            }
            STATE_TOP -> {
                params.height = dm.heightPixels / 4
                params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                params.y = dm.heightPixels / 2   // 上边缘锚定中线不动，收起下半部分，露出屏幕底部
                view.setBackgroundResource(R.drawable.bg_panel)
            }
        }
        runCatching { wm?.updateViewLayout(view, params) }
    }

    private fun hidePanel() {
        pendingCall?.cancel()   // 收起 = 断开远程取数
        pendingCall = null
        removeView(panelView)
        panelView = null
        panelParams = null
        tvPanelTitle = null
        llContent = null
        state = STATE_QUARTER
    }

    // ===== 远程取数 / 渲染 =====
    private fun loadContent() {
        pendingCall?.cancel()
        tvPanelTitle?.text = "加载中…"
        val call = ContentApi.newCall(contentUrl)
        pendingCall = call
        Thread {
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        mainHandler.post { if (panelView != null && !call.isCanceled()) showUnready() }
                        return@use
                    }
                    val body = resp.body?.string() ?: ""
                    val data = ContentApi.parse(body)
                    mainHandler.post {
                        if (panelView == null || call.isCanceled()) return@post
                        if (data == null) showUnready() else render(data)
                    }
                }
            } catch (e: Exception) {
                if (!call.isCanceled()) mainHandler.post { if (panelView != null) showUnready() }
            }
        }.start()
    }

    private fun showUnready() {
        tvPanelTitle?.text = "内容接口未就绪"
        render(
            ContentData(
                "内容接口未就绪",
                listOf(
                    ContentModule(
                        "说明",
                        listOf(
                            "请在 OverlayService.contentUrl 填入真实后端接口（当前为占位）。",
                            "约定返回结构：{ \"title\": \"...\", \"modules\": [ { \"moduleTitle\": \"...\", \"items\": [\"...\"] } ] }"
                        )
                    )
                )
            )
        )
    }

    private fun render(data: ContentData) {
        tvPanelTitle?.text = data.title.ifEmpty { "生成结果" }
        val container = llContent ?: return
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val rowParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        if (data.modules.isEmpty()) {
            val row = inflater.inflate(R.layout.overlay_item, container, false)
            row.findViewById<TextView>(R.id.tvItemText).text = "暂无内容"
            container.addView(row, rowParams)
            return
        }
        for (m in data.modules) {
            if (m.moduleTitle.isNotEmpty()) {
                val tv = TextView(this)
                tv.text = m.moduleTitle
                tv.setTextColor(0xFF1B6EF3.toInt())
                tv.textSize = 15f
                tv.setTypeface(tv.typeface, Typeface.BOLD)
                tv.setPadding(0, 10, 0, 2)
                container.addView(tv, rowParams)
            }
            for (item in m.items) {
                if (item.isEmpty()) continue
                val row = inflater.inflate(R.layout.overlay_item, container, false)
                row.findViewById<TextView>(R.id.tvItemText).text = item
                row.findViewById<TextView>(R.id.btnItemCopy).setOnClickListener { copyText(item) }
                container.addView(row, rowParams)
            }
        }
    }

    private fun copyText(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("suspend", text))
        toast("已复制")
    }

    // ===== 拖动（悬浮球）=====
    private fun bindDrag(view: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var startRawX = 0f
        var startRawY = 0f
        var dragging = false
        view.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    startRawX = ev.rawX
                    startRawY = ev.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(ev.rawX - startRawX) > 10 || Math.abs(ev.rawY - startRawY) > 10) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = startX + (ev.rawX - startRawX).toInt()
                        params.y = startY + (ev.rawY - startRawY).toInt()
                        wm?.updateViewLayout(v, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        v.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
