package com.example.mahjongai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

class FloatingService : Service() {

    companion object {
        private var instance: FloatingService? = null

        /** 更新悬浮窗文字（服务未启动时不生效） */
        fun update(text: String) {
            instance?.updateText(text)
        }

        /** 悬浮窗是否正在运行 */
        fun isRunning(): Boolean = instance != null
    }

    private lateinit var windowManager: WindowManager
    private var floatView: View? = null
    private lateinit var tvFloat: TextView
    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundCompat()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createFloatView()
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "float_channel",
                "麻将AI悬浮窗",
                NotificationManager.IMPORTANCE_MIN
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
            val notification = Notification.Builder(this, "float_channel")
                .setContentTitle("麻将AI运行中")
                .setContentText("悬浮窗显示识别结果")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
            startForeground(1, notification)
        }
    }

    private fun createFloatView() {
        val view = LayoutInflater.from(this).inflate(R.layout.float_view, null)
        tvFloat = view.findViewById(R.id.tv_float)
        tvFloat.text = "等待识别..."

        val close = view.findViewById<View>(R.id.btn_close)
        close.setOnClickListener { stopSelf() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 40
        params.y = 240
        layoutParams = params

        // 支持拖动
        view.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0f
            private var startY = 0f
            private var origX = 0
            private var origY = 0

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        origX = layoutParams?.x ?: 0
                        origY = layoutParams?.y ?: 0
                        return false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - startX).toInt()
                        val dy = (event.rawY - startY).toInt()
                        layoutParams?.x = origX + dx
                        layoutParams?.y = origY + dy
                        windowManager.updateViewLayout(view, layoutParams)
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(view, params)
        floatView = view
    }

    private fun updateText(text: String) {
        tvFloat.post { tvFloat.text = text }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (floatView != null) {
            windowManager.removeView(floatView)
            floatView = null
        }
        instance = null
    }
}
