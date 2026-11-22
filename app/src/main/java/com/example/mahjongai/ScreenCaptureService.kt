package com.example.mahjongai

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * 屏幕识别服务：MediaProjection 截屏 + YOLO 识别 + 悬浮窗显示
 */
class ScreenCaptureService : Service() {

    companion object {
        // 按麻宝AI 分布：手牌 = 牌框底边超过屏幕短边 90% 的竖牌
        private const val HAND_BOTTOM_RATIO = 0.9f

        @Volatile
        private var instance: ScreenCaptureService? = null

        fun isRunning(): Boolean = instance != null

        fun stopIfRunning() {
            instance?.stopSelf()
        }
    }

    private lateinit var windowManager: WindowManager
    private var floatView: View? = null
    private lateinit var tvFloat: TextView
    private var layoutParams: WindowManager.LayoutParams? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var detector: YoloDetector? = null
    private val advisor = DiscardAdvisor()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var capturing = false

    private var screenWidth = 0
    private var screenHeight = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundCompat()
        createFloatView()

        // 后台加载模型
        Thread {
            try {
                detector = YoloDetector(this)
                updateText("模型就绪，正在截屏识别...")
            } catch (e: Exception) {
                updateText("模型加载失败: ${e.message}")
            }
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && mediaProjection == null) {
            val resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra("data", Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra("data")
            }
            if (resultCode == Activity.RESULT_OK && data != null) {
                setupProjection(resultCode, data)
            } else {
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "screen_channel",
                "屏幕识别",
                NotificationManager.IMPORTANCE_MIN
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
            val notification = Notification.Builder(this, "screen_channel")
                .setContentTitle("麻将AI屏幕识别中")
                .setContentText("正在识别屏幕上的麻将牌")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
            startForeground(1, notification)
        }
    }

    private fun setupProjection(resultCode: Int, data: Intent) {
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = pm.getMediaProjection(resultCode, data)

        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight, PixelFormat.RGBA_8888, 2
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "mahjong-screen",
            screenWidth, screenHeight, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                updateText("录屏授权已结束")
                stopSelf()
            }
        }, handler)

        startCaptureLoop()
    }

    private fun startCaptureLoop() {
        capturing = true
        Thread {
            while (capturing) {
                try {
                    val bmp = captureScreen()
                    if (bmp != null) {
                        val dets = detector?.detect(bmp)
                        if (dets != null) processDets(dets)
                        bmp.recycle()
                    }
                } catch (e: Exception) {
                    // 忽略单帧错误，继续下一帧
                }
                try {
                    Thread.sleep(1200)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }.start()
    }

    private fun captureScreen(): Bitmap? {
        val reader = imageReader ?: return null
        val image: Image = reader.acquireLatestImage() ?: return null
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val full = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            full.copyPixelsFromBuffer(buffer)
            return if (rowPadding == 0) full
            else Bitmap.createBitmap(full, 0, 0, screenWidth, screenHeight)
        } finally {
            image.close()
        }
    }

    /** 麻宝AI 分布：手牌 = 底边超过短边 90% 且竖牌（高>宽） */
    private fun isHandTile(d: YoloDetector.Detection): Boolean {
        val w = d.x2 - d.x1
        val h = d.y2 - d.y1
        return d.y2 > HAND_BOTTOM_RATIO && h > w
    }

    private fun processDets(dets: List<YoloDetector.Detection>) {
        // 按麻宝AI 分布：手牌 = 底边超过短边 90% 的竖牌，其余为牌河
        val hand = dets.filter { isHandTile(it) }
            .sortedBy { it.cx }
            .map { it.tile }
        val river = dets.filter { !isHandTile(it) }
            .map { it.tile }

        val sb = StringBuilder()
        if (hand.isEmpty()) {
            sb.appendLine("未识别到手牌")
            sb.appendLine("屏上共 ${dets.size} 张牌")
            updateText(sb.toString())
            return
        }

        sb.appendLine("手牌(${hand.size}): ${hand.joinToString(" ")}")
        if (river.isNotEmpty()) {
            sb.appendLine("牌河(${river.size}): ${river.joinToString(" ")}")
        }

        if (hand.size in 13..14) {
            val s = advisor.shanten(hand)
            when {
                s == -1 -> sb.appendLine("✅ 胡牌！")
                s == 0 -> {
                    sb.appendLine("🎯 已听牌")
                    if (hand.size == 14) {
                        val adv = advisor.fullAdvice(hand, river)
                        sb.appendLine("建议打: ${adv.discard}（${adv.reason}）")
                    } else {
                        val wait = advisor.fullAdvice(hand + hand.first(), river).waiting
                        if (wait.isNotEmpty()) sb.appendLine("听: ${wait.joinToString(" ")}")
                    }
                }
                else -> {
                    sb.appendLine("向听: $s")
                    if (hand.size == 14) {
                        val adv = advisor.fullAdvice(hand, river)
                        sb.appendLine("建议打: ${adv.discard}（${adv.reason}）")
                        if (adv.waiting.isNotEmpty()) {
                            sb.appendLine("打出后听: ${adv.waiting.joinToString(" ")}")
                        }
                    }
                }
            }
        } else {
            sb.appendLine("⚠️ 手牌数异常(${hand.size})，应 13/14 张")
        }
        updateText(sb.toString())
    }

    private fun createFloatView() {
        val view = LayoutInflater.from(this).inflate(R.layout.float_view, null)
        tvFloat = view.findViewById(R.id.tv_float)
        tvFloat.text = "屏幕识别启动中..."

        view.findViewById<View>(R.id.btn_close).setOnClickListener { stopSelf() }

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
        handler.post {
            if (floatView != null) {
                tvFloat.text = text
            }
        }
    }

    override fun onDestroy() {
        capturing = false
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        if (floatView != null) {
            windowManager.removeView(floatView)
            floatView = null
        }
        instance = null
        super.onDestroy()
    }
}
