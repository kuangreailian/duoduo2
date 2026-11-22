package com.example.mahjongai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
    private lateinit var btnFloat: Button

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imgPreview = findViewById(R.id.img_preview)
        tvResult = findViewById(R.id.tv_result)
        btnFloat = findViewById(R.id.btn_float)
        val btnCamera = findViewById<Button>(R.id.btn_camera)
        val btnGallery = findViewById<Button>(R.id.btn_gallery)

        tvResult.text = "正在加载模型..."
        Thread {
            try {
                detector = YoloDetector(this)
                runOnUiThread { tvResult.text = "模型加载完成，点击按钮开始识别" }
            } catch (e: Exception) {
                runOnUiThread {
                    tvResult.text = "模型加载失败: ${e.message}"
                }
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

        btnFloat.setOnClickListener { toggleFloatWindow() }
        updateFloatButtonText()
    }

    override fun onResume() {
        super.onResume()
        updateFloatButtonText()
    }

    private fun toggleFloatWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }
        val serviceIntent = Intent(this, FloatingService::class.java)
        if (!FloatingService.isRunning()) {
            startForegroundService(serviceIntent)
            Toast.makeText(this, "悬浮窗已开启", Toast.LENGTH_SHORT).show()
        } else {
            stopService(serviceIntent)
            Toast.makeText(this, "悬浮窗已关闭", Toast.LENGTH_SHORT).show()
        }
        updateFloatButtonText()
    }

    private fun updateFloatButtonText() {
        val on = FloatingService.isRunning()
        btnFloat.text = if (on) "关闭悬浮窗" else "开启悬浮窗"
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

                // 屏幕下方为手牌（按 y 坐标筛选）
                val hand = dets.filter { it.cy > 0.62f }
                    .sortedBy { it.cx }
                    .map { it.tile }

                val sb = StringBuilder()
                sb.appendLine("识别到 ${dets.size} 张牌")
                sb.appendLine()
                sb.appendLine("手牌（${hand.size} 张）：${hand.joinToString(" ")}")

                val shanten = if (hand.size in 13..14) {
                    advisor.shanten(hand)
                } else {
                    -99
                }

                val floatText = StringBuilder()
                floatText.appendLine("手牌(${hand.size}):")
                floatText.appendLine(hand.joinToString(" "))

                when {
                    shanten == -99 -> {
                        sb.appendLine()
                        sb.appendLine("⚠️ 手牌数异常：应 13 或 14 张，识别到 ${hand.size} 张")
                        sb.appendLine("请调整拍照角度/距离后重试，确保牌面完整无遮挡")
                        floatText.appendLine("⚠️手牌数异常(${hand.size})")
                    }
                    shanten == -1 -> {
                        sb.appendLine("✅ 已经胡牌！")
                        floatText.appendLine("✅ 已经胡牌！")
                    }
                    shanten == 0 -> {
                        sb.appendLine("🎯 已经听牌")
                        floatText.appendLine("🎯 已经听牌")
                    }
                    else -> {
                        sb.appendLine("向听数：${shanten} 向听")
                        floatText.appendLine("向听数：${shanten}")
                        if (hand.size == 14) {
                            val discard = advisor.bestDiscard(hand)
                            sb.appendLine("建议打出：$discard")
                            floatText.appendLine("建议打出：$discard")
                        }
                    }
                }

                runOnUiThread {
                    tvResult.text = sb.toString()
                }
                FloatingService.update(floatText.toString())
            } catch (e: Exception) {
                runOnUiThread { tvResult.text = "识别失败: ${e.message}" }
                FloatingService.update("识别失败")
            }
        }.start()
    }
}
