package com.example.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

object ImageUtils {

    const val MAX_IMAGE_DIMENSION = 2048

    /**
     * Safely decodes a bitmap from a Uri, corrects EXIF orientation,
     * and downsamples if dimensions exceed MAX_IMAGE_DIMENSION.
     */
    suspend fun loadBitmapFromUri(context: Context, uri: Uri): Result<Bitmap> = withContext(Dispatchers.IO) {
        runCatching {
            // 1. Measure dimensions without full decode
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: throw IllegalArgumentException("Cannot open stream for Uri: $uri")

            val origWidth = options.outWidth
            val origHeight = options.outHeight
            if (origWidth <= 0 || origHeight <= 0) {
                throw IllegalStateException("Invalid image dimensions ($origWidth x $origHeight)")
            }

            var inSampleSize = 1
            val maxDim = max(origWidth, origHeight)
            if (maxDim > MAX_IMAGE_DIMENSION) {
                while ((maxDim / (inSampleSize * 2)) >= MAX_IMAGE_DIMENSION) {
                    inSampleSize *= 2
                }
            }

            // 2. Decode bitmap with calculated sample size
            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
            }

            val rawBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: throw IllegalStateException("BitmapFactory failed to decode image")

            // 3. Check and correct EXIF orientation
            val orientation = getExifOrientation(context, uri)
            val correctedBitmap = rotateBitmapIfRequired(rawBitmap, orientation)

            correctedBitmap
        }
    }

    private fun getExifOrientation(context: Context, uri: Uri): Int {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    }

    private fun rotateBitmapIfRequired(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) {
            bitmap.recycle()
        }
        return rotated
    }

    /**
     * Applies an alpha/grayscale mask to an original bitmap to produce a transparent cutout.
     * Mask pixel intensity (0 to 255) defines the alpha value of the resulting cutout.
     */
    fun applyMask(original: Bitmap, mask: Bitmap): Bitmap {
        val width = original.width
        val height = original.height

        // Ensure mask matches original size
        val scaledMask = if (mask.width == width && mask.height == height) {
            mask
        } else {
            Bitmap.createScaledBitmap(mask, width, height, true)
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val origPixels = IntArray(width * height)
        val maskPixels = IntArray(width * height)

        original.getPixels(origPixels, 0, width, 0, 0, width, height)
        scaledMask.getPixels(maskPixels, 0, width, 0, 0, width, height)

        for (i in origPixels.indices) {
            val origColor = origPixels[i]
            val maskColor = maskPixels[i]

            // If mask is grayscale or alpha, extract intensity
            val maskAlpha = (maskColor shr 24) and 0xFF
            val maskR = (maskColor shr 16) and 0xFF
            val maskG = (maskColor shr 8) and 0xFF
            val maskB = maskColor and 0xFF
            // Use maximum of luminance or alpha channel
            val luminance = (maskR * 77 + maskG * 150 + maskB * 29) shr 8
            val alphaFactor = if (maskAlpha < 255) maskAlpha else luminance

            val origA = (origColor shr 24) and 0xFF
            val finalA = (origA * alphaFactor) / 255

            origPixels[i] = (finalA shl 24) or (origColor and 0x00FFFFFF)
        }

        result.setPixels(origPixels, 0, width, 0, 0, width, height)

        if (scaledMask != mask) {
            scaledMask.recycle()
        }

        return result
    }

    /**
     * Composites the cutout foreground onto a chosen background:
     * - Transparent
     * - Solid color
     * - Background image
     */
    fun compositeImage(
        cutout: Bitmap,
        bgType: String,
        bgColor: Int,
        bgImage: Bitmap?
    ): Bitmap {
        val width = cutout.width
        val height = cutout.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        when (bgType) {
            "COLOR" -> {
                canvas.drawColor(bgColor)
            }
            "IMAGE" -> {
                if (bgImage != null) {
                    val srcRect = Rect(0, 0, bgImage.width, bgImage.height)
                    val dstRect = Rect(0, 0, width, height)
                    canvas.drawBitmap(bgImage, srcRect, dstRect, paint)
                } else {
                    canvas.drawColor(bgColor)
                }
            }
            else -> {
                // TRANSPARENT: Leave transparent background (ARGB 0)
            }
        }

        // Draw cutout subject on top
        canvas.drawBitmap(cutout, 0f, 0f, paint)
        return output
    }

    /**
     * Exports an image to MediaStore gallery.
     * format: "PNG" or "JPEG"
     */
    suspend fun saveToGallery(
        context: Context,
        bitmap: Bitmap,
        format: String,
        quality: Int = 95,
        title: String = "CutoutPro_${System.currentTimeMillis()}"
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val extension = if (format == "PNG") "png" else "jpg"
            val mimeType = if (format == "PNG") "image/png" else "image/jpeg"
            val filename = "$title.$extension"

            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/CutoutPro")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw IllegalStateException("Failed to create MediaStore entry")

            resolver.openOutputStream(uri)?.use { outStream ->
                val compressFormat = if (format == "PNG") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                val success = bitmap.compress(compressFormat, quality, outStream)
                if (!success) throw IllegalStateException("Bitmap compression failed")
            } ?: throw IllegalStateException("Failed to open output stream")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }

            uri
        }
    }

    /**
     * Saves bitmap to a temporary cache file and creates a share Intent via FileProvider.
     */
    suspend fun createShareIntent(
        context: Context,
        bitmap: Bitmap,
        isPng: Boolean
    ): Result<Intent> = withContext(Dispatchers.IO) {
        runCatching {
            val cacheDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val extension = if (isPng) "png" else "jpg"
            val file = File(cacheDir, "cutout_share_${System.currentTimeMillis()}.$extension")

            FileOutputStream(file).use { out ->
                val format = if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                bitmap.compress(format, 95, out)
            }

            val authority = "${context.packageName}.fileprovider"
            val contentUri = FileProvider.getUriForFile(context, authority, file)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = if (isPng) "image/png" else "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            Intent.createChooser(shareIntent, "Share Cutout")
        }
    }

    /**
     * Saves a project bitmap internally and returns file path.
     */
    suspend fun saveBitmapToFile(context: Context, bitmap: Bitmap, fileName: String): String =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "projects").apply { mkdirs() }
            val file = File(dir, fileName)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
            }
            file.absolutePath
        }

    /**
     * Loads a bitmap from internal file path.
     */
    suspend fun loadBitmapFromFile(path: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(path)
            if (file.exists()) {
                BitmapFactory.decodeFile(file.absolutePath)
            } else null
        }.getOrNull()
    }
}
