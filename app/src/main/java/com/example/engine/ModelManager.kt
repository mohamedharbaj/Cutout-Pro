package com.example.engine

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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

    // BiRefNet Lite exported to ONNX (fp32, 1024x1024 input). Published by onnx-community.
    // NOTE: the previous URL/size (~34 MB) did not match any real BiRefNet Lite ONNX file;
    // the real fp32 file is ~224 MB.
    private val modelUrl =
        "https://huggingface.co/onnx-community/BiRefNet_lite-ONNX/resolve/main/onnx/model.onnx"
    val expectedSizeBytes: Long = 224_000_000L // ~224 MB

    // Anything smaller than this is certainly an error page / truncated file, not the model.
    private val minValidBytes = 50_000_000L

    private val _downloadState = MutableStateFlow<ModelDownloadState>(
        if (isModelFileValid()) ModelDownloadState.Ready else ModelDownloadState.NotDownloaded
    )
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // Downloads run in an app-level scope so leaving the Settings screen does not kill them,
    // and a second tap on "Download" joins the running download instead of starting another.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Deferred<Result<Unit>>? = null

    private val saliencyEngine = SaliencySegmenter()
    private var onnxEngine: OnnxSegmenter? = null

    private fun isModelFileValid(): Boolean = modelFile.exists() && modelFile.length() > minValidBytes

    @Synchronized
    fun getActiveEngine(): SegmentationEngine {
        return if (isModelFileValid()) {
            val engine = onnxEngine ?: OnnxSegmenter(modelFile).also { onnxEngine = it }
            engine
        } else {
            saliencyEngine
        }
    }

    /** Always-available built-in engine, used as a fallback if the neural engine fails. */
    fun getFallbackEngine(): SegmentationEngine = saliencyEngine

    suspend fun downloadModel(): Result<Unit> {
        val job = synchronized(this) {
            val running = downloadJob
            if (running != null && running.isActive) {
                running
            } else {
                appScope.async { doDownload() }.also { downloadJob = it }
            }
        }
        return job.await()
    }

    private fun doDownload(): Result<Unit> {
        val tempFile = File(modelsDir, "birefnet_lite.tmp")
        return runCatching {
            _downloadState.value = ModelDownloadState.Downloading(0, 0, expectedSizeBytes)
            if (tempFile.exists()) tempFile.delete()

            val request = Request.Builder().url(modelUrl).build()
            // Response MUST be closed (it was leaked before on every error path).
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Download failed with HTTP ${response.code}")
                }

                val body = response.body ?: throw IOException("Empty response body")
                val contentLength = body.contentLength()
                val totalBytes = if (contentLength > 0) contentLength else expectedSizeBytes

                var bytesCopied = 0L
                val buffer = ByteArray(64 * 1024)
                var lastUpdate = System.currentTimeMillis()

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            bytesCopied += read

                            val now = System.currentTimeMillis()
                            if (now - lastUpdate > 250) {
                                val percent = ((bytesCopied * 100) / totalBytes).toInt().coerceIn(0, 99)
                                _downloadState.value = ModelDownloadState.Downloading(percent, bytesCopied, totalBytes)
                                lastUpdate = now
                            }
                        }
                        output.flush()
                    }
                }

                // Truncated download: server said N bytes but the stream ended early.
                if (contentLength > 0 && bytesCopied != contentLength) {
                    throw IOException("Download incomplete ($bytesCopied of $contentLength bytes)")
                }
            }

            if (tempFile.length() < minValidBytes) {
                throw IOException("Downloaded file is too small to be the model")
            }

            // Release the old session before replacing the file it maps.
            synchronized(this) {
                onnxEngine?.release()
                onnxEngine = null
            }
            if (modelFile.exists() && !modelFile.delete()) {
                throw IOException("Could not replace the existing model file")
            }
            if (!tempFile.renameTo(modelFile)) {
                throw IOException("Could not move the downloaded model into place")
            }

            _downloadState.value = ModelDownloadState.Ready
        }.onFailure { e ->
            tempFile.delete()
            _downloadState.value = ModelDownloadState.Error(e.message ?: "Download failed")
        }
    }

    suspend fun deleteModel() = withContext(Dispatchers.IO) {
        synchronized(this@ModelManager) {
            onnxEngine?.release()
            onnxEngine = null
        }
        if (modelFile.exists()) {
            modelFile.delete()
        }
        _downloadState.value = ModelDownloadState.NotDownloaded
    }
}
