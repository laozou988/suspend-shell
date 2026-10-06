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
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.yunshu.suspend.api.ContentApi

/**
 * 悬浮屏服务：常驻任意应用上层。
 * - 胶囊态：一条可拖动的小胶囊
 * - 展开态：一块大面积悬浮副屏（标题 + 内容 + 复制 / 刷新 / 收起）
 * - 内容：从后端接口拉取（OverlayService.contentUrl 可配置）
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "overlay_channel"
        private const val NOTIFY_ID = 1001

        var isRunning = false
            private set

        // 后端内容接口（占位：后端就绪后替换为真实地址）
        var contentUrl = "https://your-backend.example.com/api/latest-content"
    }

    private var wm: WindowManager? = null
    private var capsuleView: View? = null
    private var panelView: View? = null
    private var tvPanelTitle: TextView? = null
    private var tvPanelBody: TextView? = null
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

    // ===== 胶囊态 =====
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
        view.setOnClickListener { showPanel() }
    }

    // ===== 展开态（悬浮副屏）=====
    private fun showPanel() {
        if (panelView != null) return
        val dm: DisplayMetrics = resources.displayMetrics
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)
        val params = WindowManager.LayoutParams(
            (dm.widthPixels * 0.92).toInt(),
            (dm.heightPixels * 0.75).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (dm.widthPixels * 0.04).toInt()
            y = (dm.heightPixels * 0.12).toInt()
        }
        wm?.addView(view, params)
        panelView = view
        bindDrag(view, params)

        tvPanelTitle = view.findViewById(R.id.tvPanelTitle)
        tvPanelBody = view.findViewById(R.id.tvPanelBody)

        view.findViewById<Button>(R.id.btnCollapse).setOnClickListener { hidePanel() }
        view.findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val text = tvPanelBody?.text?.toString().orEmpty()
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("suspend", text))
            toast("已复制到剪贴板")
        }
        view.findViewById<Button>(R.id.btnRefresh).setOnClickListener { loadContent() }

        loadContent()
    }

    private fun hidePanel() {
        removeView(panelView)
        panelView = null
        tvPanelTitle = null
        tvPanelBody = null
    }

    private fun loadContent() {
        tvPanelTitle?.text = "加载中…"
        Thread {
            val pair = ContentApi.fetchLatest(contentUrl)
            mainHandler.post {
                if (panelView == null) return@post
                if (pair == null) {
                    tvPanelTitle?.text = "内容接口未就绪"
                    tvPanelBody?.text =
                        "请在后端接入后，把接口地址填入 OverlayService.contentUrl（当前为占位）。\n\n" +
                        "约定返回：\n" +
                        "{ \"title\": \"标题\", \"content\": \"正文内容\" }"
                } else {
                    tvPanelTitle?.text = pair.first.ifEmpty { "生成结果" }
                    tvPanelBody?.text = pair.second
                }
            }
        }.start()
    }

    // ===== 拖动 =====
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
