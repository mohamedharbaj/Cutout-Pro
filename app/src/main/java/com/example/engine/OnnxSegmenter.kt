package com.example.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

class OnnxSegmenter(private val modelFile: File) : SegmentationEngine {
    override val name: String = "BiRefNet Lite ONNX Neural Model"
    override val isReady: Boolean
        get() = modelFile.exists() && modelFile.length() > 100_000L

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    private fun initSessionIfNeeded() {
        if (ortSession == null && isReady) {
            val env = OrtEnvironment.getEnvironment().also { ortEnv = it }
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            }
            ortSession = env.createSession(modelFile.absolutePath, options)
        }
    }

    override suspend fun segment(bitmap: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        if (!isReady) {
            throw IllegalStateException("ONNX model file not found or corrupted.")
        }

        initSessionIfNeeded()
        val session = ortSession ?: throw IllegalStateException("Failed to initialize ONNX session")
        val env = ortEnv ?: throw IllegalStateException("ONNX environment not initialized")

        val inputName = session.inputNames.iterator().next()
        val inputInfo = session.inputInfo[inputName]?.info as? ai.onnxruntime.TensorInfo
        val shape = inputInfo?.shape

        // Default to 512x512 or 320x320 if dynamic
        val targetH = if (shape != null && shape.size >= 4 && shape[2] > 0) shape[2].toInt() else 512
        val targetW = if (shape != null && shape.size >= 4 && shape[3] > 0) shape[3].toInt() else 512

        val origW = bitmap.width
        val origH = bitmap.height

        val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        val pixels = IntArray(targetW * targetH)
        scaled.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
        scaled.recycle()

        // Prepare CHW FloatBuffer with ImageNet normalization
        val totalElements = 3 * targetW * targetH
        val floatBuffer = FloatBuffer.allocate(totalElements)

        val meanR = 0.485f; val meanG = 0.456f; val meanB = 0.406f
        val stdR = 0.229f;  val stdG = 0.224f;  val stdB = 0.225f

        // Channel R
        for (i in pixels.indices) {
            val r = ((pixels[i] shr 16) and 0xFF) / 255.0f
            floatBuffer.put((r - meanR) / stdR)
        }
        // Channel G
        for (i in pixels.indices) {
            val g = ((pixels[i] shr 8) and 0xFF) / 255.0f
            floatBuffer.put((g - meanG) / stdG)
        }
        // Channel B
        for (i in pixels.indices) {
            val b = (pixels[i] and 0xFF) / 255.0f
            floatBuffer.put((b - meanB) / stdB)
        }
        floatBuffer.rewind()

        val tensorShape = longArrayOf(1, 3, targetH.toLong(), targetW.toLong())
        val inputTensor = OnnxTensor.createTensor(env, floatBuffer, tensorShape)

        val output = session.run(mapOf(inputName to inputTensor))
        inputTensor.close()

        val outputName = session.outputNames.iterator().next()
        val outputTensor = output[outputName].get() as OnnxTensor
        val rawOutput = outputTensor.floatBuffer

        val maskPixels = IntArray(targetW * targetH)
        rawOutput.rewind()

        for (i in maskPixels.indices) {
            var v = rawOutput.get()
            // If output values are raw logits, apply sigmoid activation
            if (v < 0f || v > 1f) {
                v = 1.0f / (1.0f + exp(-v))
            }
            val alpha = (v * 255.0f).toInt().coerceIn(0, 255)
            maskPixels[i] = Color.argb(alpha, 255, 255, 255)
        }

        output.close()

        val lowResMask = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        lowResMask.setPixels(maskPixels, 0, targetW, 0, 0, targetW, targetH)

        val fullMask = Bitmap.createScaledBitmap(lowResMask, origW, origH, true)
        lowResMask.recycle()

        fullMask
    }

    fun release() {
        ortSession?.close()
        ortSession = null
        ortEnv?.close()
        ortEnv = null
    }
}
