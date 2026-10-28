package com.example.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import android.graphics.Color
import com.example.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.exp

class OnnxSegmenter(private val modelFile: File) : SegmentationEngine {
    override val name: String = "BiRefNet Lite ONNX Neural Model"
    override val isReady: Boolean
        get() = modelFile.exists() && modelFile.length() > 100_000L

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    // Only one inference at a time: the session/env are shared and release() may race with segment().
    private val lock = Any()

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
        synchronized(lock) { runInference(bitmap) }
    }

    private fun runInference(bitmap: Bitmap): Bitmap {
        initSessionIfNeeded()
        val session = ortSession ?: throw IllegalStateException("Failed to initialize ONNX session")
        val env = ortEnv ?: throw IllegalStateException("ONNX environment not initialized")

        val inputName = session.inputNames.iterator().next()
        val inputInfo = session.inputInfo[inputName]?.info as? TensorInfo
        val shape = inputInfo?.shape

        // BiRefNet is exported with a 1024x1024 input. If the exported graph has dynamic
        // spatial dims (-1) fall back to 1024 as well (512 gives badly degraded masks).
        val targetH = if (shape != null && shape.size >= 4 && shape[2] > 0) shape[2].toInt() else 1024
        val targetW = if (shape != null && shape.size >= 4 && shape[3] > 0) shape[3].toInt() else 1024

        val origW = bitmap.width
        val origH = bitmap.height

        // scaleToNewBitmap always returns a NEW bitmap, so recycling it never touches the caller's image.
        val scaled = ImageUtils.scaleToNewBitmap(bitmap, targetW, targetH)
        val pixels = IntArray(targetW * targetH)
        scaled.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
        scaled.recycle()

        // Prepare CHW FloatBuffer with ImageNet normalization
        val totalElements = 3 * targetW * targetH
        val floatBuffer = FloatBuffer.allocate(totalElements)

        val meanR = 0.485f; val meanG = 0.456f; val meanB = 0.406f
        val stdR = 0.229f;  val stdG = 0.224f;  val stdB = 0.225f

        for (i in pixels.indices) {
            floatBuffer.put((((pixels[i] shr 16) and 0xFF) / 255.0f - meanR) / stdR)
        }
        for (i in pixels.indices) {
            floatBuffer.put((((pixels[i] shr 8) and 0xFF) / 255.0f - meanG) / stdG)
        }
        for (i in pixels.indices) {
            floatBuffer.put(((pixels[i] and 0xFF) / 255.0f - meanB) / stdB)
        }
        floatBuffer.rewind()

        val tensorShape = longArrayOf(1, 3, targetH.toLong(), targetW.toLong())
        val maskValues = FloatArray(targetW * targetH)

        // use{} guarantees native tensors/results are closed even if inference throws.
        OnnxTensor.createTensor(env, floatBuffer, tensorShape).use { inputTensor ->
            session.run(mapOf(inputName to inputTensor)).use { output ->
                val outputName = session.outputNames.iterator().next()
                val outputTensor = output[outputName].get() as OnnxTensor
                val rawOutput = outputTensor.floatBuffer
                rawOutput.rewind()
                val n = minOf(maskValues.size, rawOutput.remaining())
                rawOutput.get(maskValues, 0, n)
            }
        }

        // Decide ONCE whether the model returned logits or probabilities. Deciding per pixel
        // (as before) produced a patchwork: a logit that happened to be in [0,1] skipped the sigmoid.
        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        for (v in maskValues) {
            if (v < minV) minV = v
            if (v > maxV) maxV = v
        }
        val isLogits = minV < 0f || maxV > 1f

        val maskPixels = IntArray(targetW * targetH)
        for (i in maskPixels.indices) {
            var v = maskValues[i]
            if (isLogits) v = 1.0f / (1.0f + exp(-v))
            val alpha = (v * 255.0f).toInt().coerceIn(0, 255)
            maskPixels[i] = Color.argb(alpha, 255, 255, 255)
        }

        val lowResMask = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        lowResMask.setPixels(maskPixels, 0, targetW, 0, 0, targetW, targetH)

        // Safe even when the model resolution equals the image resolution.
        val fullMask = ImageUtils.scaleToNewBitmap(lowResMask, origW, origH)
        lowResMask.recycle()

        return fullMask
    }

    fun release() {
        synchronized(lock) {
            ortSession?.close()
            ortSession = null
            // OrtEnvironment is a process-wide singleton shared by every session; closing it
            // here would break any other user of it, so only the session is released.
            ortEnv = null
        }
    }
}
