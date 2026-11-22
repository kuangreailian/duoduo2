package com.example.mahjongai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.FloatBuffer
import java.util.Collections

class YoloDetector(context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val opts = OrtSession.SessionOptions()
        session = env.createSession(context.assets.open("best.onnx").readBytes(), opts)
    }

    companion object {
        val TILE_NAMES = arrayOf(
            "1条", "2条", "3条", "4条", "5条", "6条", "7条", "8条", "9条",
            "1万", "2万", "3万", "4万", "5万", "6万", "7万", "8万", "9万",
            "1筒", "2筒", "3筒", "4筒", "5筒", "6筒", "7筒", "8筒", "9筒",
            "东", "北", "红中", "南", "西"
        )
        const val RED_DRAGON_ID = 29
        private const val INPUT_SIZE = 640
        private const val NC = 32
        private const val NO = 36 // 4 + NC
        private const val ANCHORS = 8400
        private const val CONF_THR = 0.25f
        private const val IOU_THR = 0.45f
    }

    data class Detection(
        val clsId: Int,
        val tile: String,
        val conf: Float,
        val cx: Float,
        val cy: Float,
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float
    )

    fun detect(bitmap: Bitmap): List<Detection> {
        val input = preprocess(bitmap)
        val result = session.run(mapOf("images" to input))
        result.use {
            val tensor = it[0].value as OnnxTensor
            return postprocess(tensor)
        }
    }

    private fun preprocess(bitmap: Bitmap): OnnxTensor {
        val scale = INPUT_SIZE.toFloat() / maxOf(bitmap.width, bitmap.height)
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)

        val xOff = (INPUT_SIZE - w) / 2
        val yOff = (INPUT_SIZE - h) / 2
        val plane = INPUT_SIZE * INPUT_SIZE
        val chw = FloatArray(3 * plane)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = pixels[y * w + x]
                val idx = (y + yOff) * INPUT_SIZE + (x + xOff)
                chw[idx] = ((p shr 16) and 0xFF) / 255f
                chw[plane + idx] = ((p shr 8) and 0xFF) / 255f
                chw[2 * plane + idx] = (p and 0xFF) / 255f
            }
        }
        return OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(chw),
            longArrayOf(1L, 3L, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        )
    }

    private fun postprocess(tensor: OnnxTensor): List<Detection> {
        val raw = tensor.floatBuffer
        val dets = ArrayList<Detection>()

        for (a in 0 until ANCHORS) {
            val base = a * NO
            var bestCls = 0
            var bestConf = -1f
            for (c in 0 until NC) {
                val v = raw.get(base + 4 + c)
                if (v > bestConf) {
                    bestConf = v
                    bestCls = c
                }
            }
            if (bestConf < CONF_THR) continue

            val cx = raw.get(base)
            val cy = raw.get(base + 1)
            val w = raw.get(base + 2)
            val h = raw.get(base + 3)
            val x1 = cx - w / 2f
            val y1 = cy - h / 2f
            val x2 = cx + w / 2f
            val y2 = cy + h / 2f

            dets.add(
                Detection(
                    bestCls, TILE_NAMES[bestCls], bestConf,
                    cx, cy, x1, y1, x2, y2
                )
            )
        }

        return nms(dets)
    }

    private fun nms(input: List<Detection>): List<Detection> {
        val sorted = input.sortedByDescending { it.conf }
        val keep = ArrayList<Detection>()
        val suppressed = BooleanArray(sorted.size)

        for (i in sorted.indices) {
            if (suppressed[i]) continue
            keep.add(sorted[i])
            for (j in i + 1 until sorted.size) {
                if (suppressed[j]) continue
                if (sorted[i].clsId != sorted[j].clsId) continue
                if (iou(sorted[i], sorted[j]) > IOU_THR) {
                    suppressed[j] = true
                }
            }
        }
        return keep
    }

    private fun iou(a: Detection, b: Detection): Float {
        val ix1 = maxOf(a.x1, b.x1)
        val iy1 = maxOf(a.y1, b.y1)
        val ix2 = minOf(a.x2, b.x2)
        val iy2 = minOf(a.y2, b.y2)
        val iw = maxOf(0f, ix2 - ix1)
        val ih = maxOf(0f, iy2 - iy1)
        val inter = iw * ih
        val areaA = maxOf(0f, a.x2 - a.x1) * maxOf(0f, a.y2 - a.y1)
        val areaB = maxOf(0f, b.x2 - b.x1) * maxOf(0f, b.y2 - b.y1)
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }

    fun close() {
        session.close()
        env.close()
    }
}
