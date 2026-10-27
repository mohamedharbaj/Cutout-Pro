package com.example.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

sealed class ModelDownloadState {
    data object NotDownloaded : ModelDownloadState()
    data class Downloading(val progressPercent: Int, val bytesDownloaded: Long, val totalBytes: Long) : ModelDownloadState()
    data object Ready : ModelDownloadState()
    data class Error(val message: String) : ModelDownloadState()
}

class ModelManager(private val context: Context) {

    private val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
    val modelFile = File(modelsDir, "birefnet_lite.onnx")

    // Verified open-source ONNX segmentation model weights mirror
    private val modelUrl = "https://huggingface.co/birefnet/BiRefNet-general-lite/resolve/main/birefnet-general-lite.onnx"
    val expectedSizeBytes: Long = 34_800_000L // ~34 MB

    private val _downloadState = MutableStateFlow<ModelDownloadState>(
        if (modelFile.exists() && modelFile.length() > 100_000L) ModelDownloadState.Ready
        else ModelDownloadState.NotDownloaded
    )
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val saliencyEngine = SaliencySegmenter()
    private var onnxEngine: OnnxSegmenter? = null

    fun getActiveEngine(): SegmentationEngine {
        return if (modelFile.exists() && modelFile.length() > 100_000L) {
            if (onnxEngine == null) {
                onnxEngine = OnnxSegmenter(modelFile)
            }
            onnxEngine!!
        } else {
            saliencyEngine
        }
    }

    suspend fun downloadModel(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            _downloadState.value = ModelDownloadState.Downloading(0, 0, expectedSizeBytes)
            val tempFile = File(modelsDir, "birefnet_lite.tmp")
            if (tempFile.exists()) tempFile.delete()

            val request = Request.Builder().url(modelUrl).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val err = "Download failed with HTTP ${response.code}"
                _downloadState.value = ModelDownloadState.Error(err)
                throw IllegalStateException(err)
            }

            val body = response.body ?: throw IllegalStateException("Empty response body")
            val totalBytes = if (body.contentLength() > 0) body.contentLength() else expectedSizeBytes

            var bytesCopied = 0L
            val buffer = ByteArray(16 * 1024)
            var lastUpdate = System.currentTimeMillis()

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesCopied += read

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 250) {
                            val percent = if (totalBytes > 0) ((bytesCopied * 100) / totalBytes).toInt().coerceIn(0, 99) else 0
                            _downloadState.value = ModelDownloadState.Downloading(percent, bytesCopied, totalBytes)
                            lastUpdate = now
                        }
                    }
                    output.flush()
                }
            }

            if (tempFile.length() < 100_000L) {
                tempFile.delete()
                val err = "Downloaded file size is suspiciously small"
                _downloadState.value = ModelDownloadState.Error(err)
                throw IllegalStateException(err)
            }

            // Rename tmp file to final file
            if (modelFile.exists()) modelFile.delete()
            tempFile.renameTo(modelFile)

            onnxEngine?.release()
            onnxEngine = OnnxSegmenter(modelFile)

            _downloadState.value = ModelDownloadState.Ready
        }.onFailure { e ->
            _downloadState.value = ModelDownloadState.Error(e.message ?: "Download failed")
        }
    }

    suspend fun deleteModel() = withContext(Dispatchers.IO) {
        onnxEngine?.release()
        onnxEngine = null
        if (modelFile.exists()) {
            modelFile.delete()
        }
        _downloadState.value = ModelDownloadState.NotDownloaded
    }
}
