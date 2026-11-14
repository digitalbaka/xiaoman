package dev.xiaoman.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import dev.xiaoman.api.ChatAttachment
import java.io.ByteArrayOutputStream

/** One picked file, ready for the chat input and for [dev.xiaoman.api.Agent]. */
data class PickedAttachment(val name: String, val attachment: ChatAttachment) {
    val image: Boolean get() = attachment is ChatAttachment.Image
}

/** Refuse absurd files before they are read into memory. */
private const val MAX_BYTES = 8L * 1024 * 1024

/** Text files are trimmed to keep the prompt small; the agent applies the same budget. */
private const val MAX_TEXT_CHARS = 20_000

/** Longest edge of an attached image; bigger photos are downscaled before PNG encoding. */
private const val MAX_IMAGE_DIM = 1568

/** Reads [uri] into an attachment; images become PNG, text files UTF-8, the rest name + size. */
fun readAttachment(context: Context, uri: Uri): PickedAttachment {
    val name = displayName(context, uri)
    val mime = context.contentResolver.getType(uri).orEmpty()
    if (mime.startsWith("image/")) {
        val png = readImage(context, uri)
        if (png != null) return PickedAttachment(name, ChatAttachment.Image(name, png))
    }
    val bytes = readBytes(context, uri)
    val size = bytes?.size?.toLong() ?: sizeOf(context, uri)
    val text = bytes?.let { decodeText(it) }
    return if (text != null) {
        PickedAttachment(name, ChatAttachment.Text(name, text.take(MAX_TEXT_CHARS)))
    } else {
        PickedAttachment(name, ChatAttachment.File(name, size))
    }
}

private fun displayName(context: Context, uri: Uri): String {
    val fromProvider = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
    return fromProvider?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "附件"
}

private fun sizeOf(context: Context, uri: Uri): Long = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getLong(0) else 0L
    }
}.getOrNull() ?: 0L

private fun readBytes(context: Context, uri: Uri): ByteArray? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > MAX_BYTES) return@use null
            out.write(buffer, 0, read)
        }
        out.toByteArray()
    }
}.getOrNull()

/** Decodes as UTF-8; null when the payload is binary (NUL bytes) or not valid text. */
private fun decodeText(bytes: ByteArray): String? {
    if (bytes.isEmpty()) return ""
    if (bytes.any { it == 0.toByte() }) return null
    val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull() ?: return null
    // Replacement characters mean the bytes were not UTF-8 after all.
    return text.takeIf { !it.contains('\uFFFD') }
}

private fun readImage(context: Context, uri: Uri): ByteArray? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > MAX_IMAGE_DIM * 2 || bounds.outHeight / sample > MAX_IMAGE_DIM * 2) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val decoded = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
    val scaled = scaleDown(decoded)
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
    if (scaled !== decoded) scaled.recycle()
    decoded.recycle()
    out.toByteArray()
}.getOrNull()

private fun scaleDown(bitmap: Bitmap): Bitmap {
    val longest = maxOf(bitmap.width, bitmap.height)
    if (longest <= MAX_IMAGE_DIM) return bitmap
    val scale = MAX_IMAGE_DIM.toFloat() / longest
    return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
}
