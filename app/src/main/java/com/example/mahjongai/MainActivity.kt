package com.example.mahjongai

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    private lateinit var detector: YoloDetector
    private val advisor = DiscardAdvisor()
    private lateinit var imgPreview: ImageView
    private lateinit var tvResult: TextView

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
            if (bitmap != null) {
                imgPreview.setImageBitmap(bitmap)
                analyze(bitmap)
            }
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                val bitmap = readBitmap(uri)
                if (bitmap != null) {
                    imgPreview.setImageBitmap(bitmap)
                    analyze(bitmap)
                }
            }
        }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) takePicture.launch(null)
            else Toast.makeText(this, "需要相机权限才能拍照", Toast.LENGTH_SHORT).show()
        }

    private val screenCapture =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val intent = Intent(this, ScreenCaptureService::class.java)
                intent.putExtra("resultCode", result.resultCode)
                intent.putExtra("data", result.data)
                startForegroundService(intent)
                Toast.makeText(this, "屏幕识别已开启", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "需要屏幕捕获权限", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imgPreview = findViewById(R.id.img_preview)
        tvResult = findViewById(R.id.tv_result)
        val btnCamera = findViewById<Button>(R.id.btn_camera)
        val btnGallery = findViewById<Button>(R.id.btn_gallery)
        val btnScreen = findViewById<Button>(R.id.btn_screen)

        tvResult.text = "正在加载模型..."
        Thread {
            try {
                detector = YoloDetector(this)
                runOnUiThread { tvResult.text = "模型加载完成" }
            } catch (e: Exception) {
                runOnUiThread { tvResult.text = "模型加载失败: ${e.message}" }
            }
        }.start()

        btnCamera.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                takePicture.launch(null)
            } else {
                requestCameraPermission.launch(Manifest.permission.CAMERA)
            }
        }

        btnGallery.setOnClickListener { pickImage.launch("image/*") }

        btnScreen.setOnClickListener { startScreenCapture() }
        updateScreenButtonText()
    }

    override fun onResume() {
        super.onResume()
        updateScreenButtonText()
    }

    private fun startScreenCapture() {
        // 1. 悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }
        // 2. 屏幕捕获授权
        val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCapture.launch(pm.createScreenCaptureIntent())
    }

    private fun updateScreenButtonText() {
        val btn = findViewById<Button>(R.id.btn_screen)
        btn.text = if (ScreenCaptureService.isRunning()) "关闭屏幕识别" else "开启屏幕识别"
    }

    private fun readBitmap(uri: Uri): Bitmap? {
        return try {
            val input: InputStream = contentResolver.openInputStream(uri) ?: return null
            BitmapFactory.decodeStream(input)
        } catch (e: Exception) {
            null
        }
    }

    private fun analyze(bitmap: Bitmap) {
        tvResult.text = "识别中..."
        Thread {
            try {
                val dets = detector.detect(bitmap)
                // 按麻宝AI 分布：手牌 = 底边超过短边 90% 的竖牌，其余为牌河
                val hand = dets.filter {
                    val w = it.x2 - it.x1
                    val h = it.y2 - it.y1
                    it.y2 > 0.9f && h > w
                }
                    .sortedBy { it.cx }
                    .map { it.tile }
                val river = dets.filter {
                    val w = it.x2 - it.x1
                    val h = it.y2 - it.y1
                    !(it.y2 > 0.9f && h > w)
                }
                    .map { it.tile }

                val sb = StringBuilder()
                sb.appendLine("识别到 ${dets.size} 张牌")
                sb.appendLine()
                sb.appendLine("手牌（${hand.size} 张）：${hand.joinToString(" ")}")
                if (river.isNotEmpty()) {
                    sb.appendLine("牌河（${river.size} 张）：${river.joinToString(" ")}")
                }

                val shanten = if (hand.size in 13..14) advisor.shanten(hand) else -99

                when {
                    shanten == -99 -> {
                        sb.appendLine()
                        sb.appendLine("⚠️ 手牌数异常：应 13 或 14 张，识别到 ${hand.size} 张")
                        sb.appendLine("请调整拍照角度/距离后重试")
                    }
                    shanten == -1 -> sb.appendLine("✅ 已经胡牌！")
                    shanten == 0 -> {
                        sb.appendLine("🎯 已经听牌")
                        if (hand.size == 14) {
                            val adv = advisor.fullAdvice(hand, river)
                            sb.appendLine("建议打出：${adv.discard}（${adv.reason}）")
                        } else {
                            val wait = advisor.fullAdvice(hand + hand.first(), river).waiting
                            if (wait.isNotEmpty()) sb.appendLine("听：${wait.joinToString(" ")}")
                        }
                    }
                    else -> {
                        sb.appendLine("向听数：${shanten} 向听")
                        if (hand.size == 14) {
                            val adv = advisor.fullAdvice(hand, river)
                            sb.appendLine("建议打出：${adv.discard}（${adv.reason}）")
                            if (adv.waiting.isNotEmpty()) {
                                sb.appendLine("打出后听：${adv.waiting.joinToString(" ")}")
                            }
                        }
                    }
                }
                runOnUiThread { tvResult.text = sb.toString() }
            } catch (e: Exception) {
                runOnUiThread { tvResult.text = "识别失败: ${e.message}" }
            }
        }.start()
    }
}
