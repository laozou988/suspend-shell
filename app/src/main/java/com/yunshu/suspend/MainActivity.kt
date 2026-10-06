package com.yunshu.suspend

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Android 13+ 通知权限（前台服务常驻通知）
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                100
            )
        }

        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val btn = findViewById<Button>(R.id.btnOverlay)

        refreshStatus(tvStatus)

        btn.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                startOverlay()
            } else {
                // 跳转系统授权页开启「显示在其他应用上层」
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus(findViewById(R.id.tvStatus))
        // 从授权页返回后自动启动悬浮屏
        if (Settings.canDrawOverlays(this) && !OverlayService.isRunning) {
            startOverlay()
        }
    }

    private fun refreshStatus(tv: TextView) {
        tv.text = if (Settings.canDrawOverlays(this)) "悬浮窗权限：已开启" else "悬浮窗权限：未开启"
    }

    private fun startOverlay() {
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
