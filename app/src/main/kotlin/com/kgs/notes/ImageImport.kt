package com.kgs.notes

import com.kgs.notes.design.R.string as UiR

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

internal data class PreparedImage(
    val displayName: String,
    val mediaType: String,
    val bytes: ByteArray,
)

internal fun prepareImageImport(
    context: Context,
    uri: Uri,
    removePrivateMetadata: Boolean,
): PreparedImage {
    val strings = context.localizedResources()
    val resolver = context.contentResolver
    val mediaType = resolver.getType(uri)?.lowercase() ?: "image/jpeg"
    require(mediaType.startsWith("image/")) { strings.getString(UiR.ui_the_selected_file_is_not_an_image) }
    val displayName = resolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }?.takeIf(String::isNotBlank) ?: "Image.${extensionFor(mediaType)}"
    val original = requireNotNull(resolver.openInputStream(uri)) { strings.getString(UiR.ui_the_selected_image_cannot_be_opened) }
        .use { readBounded(strings, it) }
    val content = if (removePrivateMetadata) {
        reencodeWithoutPrivateMetadata(strings, original, mediaType)
    } else {
        original
    }
    return PreparedImage(displayName, mediaType, content)
}

private fun readBounded(strings: android.content.res.Resources, input: java.io.InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        require(total <= MAX_IMAGE_BYTES) { strings.getString(UiR.ui_the_selected_image_is_larger_than_64_mb) }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun reencodeWithoutPrivateMetadata(strings: android.content.res.Resources, bytes: ByteArray, mediaType: String): ByteArray {
    require(mediaType == "image/jpeg" || mediaType == "image/png") {
        strings.getString(UiR.ui_metadata_removal_currently_supports_jpeg_and_png_images_choose_keep_original_for_this)
    }
    // Dimensions are checked before any pixel buffer exists; a tiny file can describe a huge image.
    val source = encodedImageDimensions(bytes) ?: platformImageDimensions(bytes)
        ?: throw ImageImportProblem(UiR.ui_the_selected_image_could_not_be_decoded)
    val budget = ImageDecodeBudget.forRuntime()
    val target = budget.decodeTarget(source) ?: throw ImageImportProblem(UiR.ui_this_image_is_too_large_to_remove_its_metadata_safely)
    val decoded = try {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                // The platform header is authoritative; recheck it before the decoder allocates.
                val header = ImageDimensions(info.size.width, info.size.height)
                val bounded = budget.decodeTarget(header)
                    ?: throw ImageImportProblem(UiR.ui_this_image_is_too_large_to_remove_its_metadata_safely)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                if (bounded != header) decoder.setTargetSize(bounded.width, bounded.height)
            }
        } else {
            val options = BitmapFactory.Options().apply { inSampleSize = budget.sampleSize(source) }
            requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)) {
                strings.getString(UiR.ui_the_selected_image_could_not_be_decoded)
            }.applyExifOrientation(bytes)
        }
    } catch (_: OutOfMemoryError) {
        throw ImageImportProblem(UiR.ui_this_image_is_too_large_to_remove_its_metadata_safely)
    }
    try {
        val output = ByteArrayOutputStream()
        val format = if (mediaType == "image/png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val quality = if (format == Bitmap.CompressFormat.PNG) 100 else 95
        check(decoded.compress(format, quality, output)) { strings.getString(UiR.ui_the_selected_image_could_not_be_prepared) }
        return output.toByteArray()
    } catch (_: OutOfMemoryError) {
        throw ImageImportProblem(UiR.ui_this_image_is_too_large_to_remove_its_metadata_safely)
    } finally {
        decoded.recycle()
    }
}

private fun platformImageDimensions(bytes: ByteArray): ImageDimensions? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    return ImageDimensions(options.outWidth, options.outHeight).takeIf { it.width > 0 && it.height > 0 }
}

/** A recoverable import refusal whose explanation is shown as is. */
internal class ImageImportProblem(val messageRes: Int) : IllegalArgumentException()

internal data class ImageDimensions(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height.toLong()
}

/**
 * Metadata removal holds a decoded bitmap, an EXIF-rotated copy on older Android versions,
 * and the re-encoded output at once. Larger photos are downsampled to stay inside this
 * budget; images whose header describes an absurd size are refused before decoding.
 */
internal data class ImageDecodeBudget(val maxDecodedPixels: Long, val maxSourcePixels: Long = MAX_SOURCE_PIXELS) {
    /** The decoded size within budget, preserving aspect ratio, or null to refuse the image. */
    fun decodeTarget(source: ImageDimensions): ImageDimensions? {
        if (source.width <= 0 || source.height <= 0 || source.pixels > maxSourcePixels) return null
        if (source.pixels <= maxDecodedPixels) return source
        val scale = kotlin.math.sqrt(maxDecodedPixels.toDouble() / source.pixels)
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        return ImageDimensions(width, height).takeIf { it.pixels <= maxDecodedPixels }
            ?: ImageDimensions((width - 1).coerceAtLeast(1), (height - 1).coerceAtLeast(1))
    }

    /** BitmapFactory only subsamples by powers of two. */
    fun sampleSize(source: ImageDimensions): Int {
        var sample = 1
        while (source.pixels / (sample.toLong() * sample) > maxDecodedPixels) sample *= 2
        return sample
    }

    companion object {
        /** Bitmap, rotated copy and worst-case encoded output, at four bytes per pixel each. */
        const val PEAK_BYTES_PER_PIXEL = 12L
        const val MAX_DECODED_PIXELS = 12_000_000L
        const val MAX_SOURCE_PIXELS = 200_000_000L

        fun forHeap(maxHeapBytes: Long): ImageDecodeBudget =
            ImageDecodeBudget(minOf(MAX_DECODED_PIXELS, (maxHeapBytes / 2) / PEAK_BYTES_PER_PIXEL).coerceAtLeast(1L))

        fun forRuntime(): ImageDecodeBudget = forHeap(Runtime.getRuntime().maxMemory())
    }
}

/** Reads PNG and JPEG dimensions from their headers without decoding pixels. */
internal fun encodedImageDimensions(bytes: ByteArray): ImageDimensions? {
    fun u8(index: Int) = bytes[index].toInt() and 0xff
    fun u16(index: Int) = (u8(index) shl 8) or u8(index + 1)
    fun u32(index: Int) = (u16(index).toLong() shl 16) or u16(index + 2).toLong()
    if (bytes.size >= 24 && PngSignature.indices.all { u8(it) == PngSignature[it] } &&
        String(bytes, 12, 4, Charsets.US_ASCII) == "IHDR"
    ) {
        val width = u32(16)
        val height = u32(20)
        if (width <= 0 || height <= 0 || width > Int.MAX_VALUE || height > Int.MAX_VALUE) return null
        return ImageDimensions(width.toInt(), height.toInt())
    }
    if (bytes.size < 4 || u8(0) != 0xff || u8(1) != 0xd8) return null
    var index = 2
    while (index + 3 < bytes.size) {
        if (u8(index) != 0xff) return null
        var marker = u8(index + 1)
        while (marker == 0xff && index + 2 < bytes.size) { index++; marker = u8(index + 1) }
        index += 2
        if (marker == 0x01 || marker in 0xd0..0xd8) continue
        if (marker == 0xd9 || marker == 0xda || index + 1 >= bytes.size) return null
        val length = u16(index)
        if (length < 2) return null
        val startOfFrame = marker in 0xc0..0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc
        if (startOfFrame) {
            if (index + 6 >= bytes.size) return null
            val height = u16(index + 3)
            val width = u16(index + 5)
            return ImageDimensions(width, height).takeIf { width > 0 && height > 0 }
        }
        index += length
    }
    return null
}

private val PngSignature = intArrayOf(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

@Suppress("DEPRECATION")
private fun Bitmap.applyExifOrientation(bytes: ByteArray): Bitmap {
    val orientation = runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                postRotate(90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                postRotate(-90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(-90f)
        }
    }
    if (matrix.isIdentity) return this
    val oriented = Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    if (oriented !== this) recycle()
    return oriented
}

private fun extensionFor(mediaType: String): String = when (mediaType) {
    "image/png" -> "png"
    "image/gif" -> "gif"
    "image/webp" -> "webp"
    else -> "jpg"
}

private const val MAX_IMAGE_BYTES = 64 * 1024 * 1024
