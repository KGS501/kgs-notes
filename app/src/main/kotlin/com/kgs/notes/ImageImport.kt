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
    val decoded = if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) {
            strings.getString(UiR.ui_the_selected_image_could_not_be_decoded)
        }.applyExifOrientation(bytes)
    }
    val output = ByteArrayOutputStream()
    val format = if (mediaType == "image/png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
    val quality = if (format == Bitmap.CompressFormat.PNG) 100 else 95
    check(decoded.compress(format, quality, output)) { strings.getString(UiR.ui_the_selected_image_could_not_be_prepared) }
    decoded.recycle()
    return output.toByteArray()
}

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
