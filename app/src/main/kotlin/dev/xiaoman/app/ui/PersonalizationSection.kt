package dev.xiaoman.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.Canvas
import dev.xiaoman.app.R
import dev.xiaoman.app.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/** Theme colour, assistant name/avatar and chat background. Changes are saved immediately. */
@Composable
fun PersonalizationSection(settings: AppSettings, onSave: (AppSettings) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember(settings.assistantName) { mutableStateOf(settings.assistantName) }
    var pendingAvatar by remember { mutableStateOf<Bitmap?>(null) }
    var pendingBackground by remember { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    val avatar = rememberFileImage(settings.avatarPath)

    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            pendingAvatar = loadBitmap(context, uri)
            loadFailed = pendingAvatar == null
        }
    }
    val pickBackground = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            pendingBackground = loadBitmap(context, uri)
            loadFailed = pendingBackground == null
        }
    }

    Text(stringResource(R.string.settings_personalize), style = MaterialTheme.typography.titleMedium)

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (avatar != null) {
                Image(avatar, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.settings_avatar), style = MaterialTheme.typography.bodyMedium)
            Row {
                TextButton(onClick = { pickAvatar.launch("image/*") }) { Text(stringResource(R.string.settings_avatar_pick)) }
                if (settings.avatarPath != null) {
                    TextButton(onClick = { onSave(settings.copy(avatarPath = null)) }) {
                        Text(stringResource(R.string.settings_avatar_reset))
                    }
                }
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.settings_assistant_name)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Button(
            enabled = name.trim().isNotEmpty() && name.trim() != settings.assistantName,
            onClick = { onSave(settings.copy(assistantName = name.trim())) },
        ) { Text(stringResource(R.string.settings_name_save)) }
    }

    Text(stringResource(R.string.settings_theme_color), style = MaterialTheme.typography.bodyMedium)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        THEME_PRESETS.forEach { preset ->
            val color = themeAccent(preset.hex)
            val selected = settings.themeColor.equals(preset.hex, ignoreCase = true)
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .clickable { onSave(settings.copy(themeColor = preset.hex)) }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(color)
                        .border(
                            width = if (selected) 3.dp else 0.dp,
                            color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Icon(Icons.Filled.Check, contentDescription = preset.name, tint = Color.White)
                }
                Text(
                    preset.name,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val bg = rememberFileImage(settings.chatBackgroundPath)
        Box(
            Modifier.size(56.dp, 74.dp).clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (bg != null) {
                Image(bg, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text("默认", style = MaterialTheme.typography.labelSmall)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.settings_background), style = MaterialTheme.typography.bodyMedium)
            Row {
                TextButton(onClick = { pickBackground.launch("image/*") }) {
                    Text(stringResource(R.string.settings_background_pick))
                }
                if (settings.chatBackgroundPath != null) {
                    TextButton(onClick = { onSave(settings.copy(chatBackgroundPath = null)) }) {
                        Text(stringResource(R.string.settings_background_reset))
                    }
                }
            }
        }
    }
    if (loadFailed) {
        Text(stringResource(R.string.image_load_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }

    pendingAvatar?.let { bitmap ->
        ImageCropDialog(
            bitmap = bitmap,
            aspectRatio = 1f,
            onCancel = { pendingAvatar = null },
            onCropped = { cropped ->
                pendingAvatar = null
                scope.launch {
                    saveImage(context, cropped, "avatar", png = true)?.let { path ->
                        val old = settings.avatarPath
                        if (old != null && old != path) File(old).delete()
                        onSave(settings.copy(avatarPath = path))
                    }
                }
            },
        )
    }
    pendingBackground?.let { bitmap ->
        ImageCropDialog(
            bitmap = bitmap,
            aspectRatio = 3f / 4f,
            onCancel = { pendingBackground = null },
            onCropped = { cropped ->
                pendingBackground = null
                scope.launch {
                    saveImage(context, cropped, "background", png = false)?.let { path ->
                        val old = settings.chatBackgroundPath
                        if (old != null && old != path) File(old).delete()
                        onSave(settings.copy(chatBackgroundPath = path))
                    }
                }
            },
        )
    }
}

@Composable
fun rememberFileImage(path: String?): ImageBitmap? {
    val context = LocalContext.current
    val state = produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            path?.takeIf { it.isNotBlank() }?.let { p ->
                runCatching { BitmapFactory.decodeFile(p)?.asImageBitmap() }.getOrNull()
            }
        }
    }
    return state.value
}

/** Simple pan/zoom cropper; the visible frame is what gets written to disk. */
@Composable
fun ImageCropDialog(bitmap: Bitmap, aspectRatio: Float, onCancel: () -> Unit, onCropped: (Bitmap) -> Unit) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var frameSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    Dialog(onDismissRequest = onCancel) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                    Text(
                        stringResource(R.string.crop_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Button(onClick = {
                        val frameW = frameSize.width.toFloat()
                        val frameH = frameSize.height.toFloat()
                        if (frameW <= 0f || frameH <= 0f) return@Button
                        val base = max(frameW / bitmap.width, frameH / bitmap.height)
                        val total = base * scale
                        val left = frameW / 2f + offset.x - bitmap.width * total / 2f
                        val top = frameH / 2f + offset.y - bitmap.height * total / 2f
                        val srcX = (-left / total).coerceIn(0f, bitmap.width.toFloat())
                        val srcY = (-top / total).coerceIn(0f, bitmap.height.toFloat())
                        val srcW = min(frameW / total, bitmap.width - srcX).coerceAtLeast(1f)
                        val srcH = min(frameH / total, bitmap.height - srcY).coerceAtLeast(1f)
                        val outW = min(1440, srcW.toInt().coerceAtLeast(1))
                        val outH = (outW * frameH / frameW).toInt().coerceAtLeast(1)
                        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                        android.graphics.Canvas(out).drawBitmap(
                            bitmap,
                            Rect(srcX.toInt(), srcY.toInt(), (srcX + srcW).toInt(), (srcY + srcH).toInt()),
                            Rect(0, 0, outW, outH),
                            Paint(Paint.FILTER_BITMAP_FLAG),
                        )
                        onCropped(out)
                    }) { Text(stringResource(R.string.crop_use)) }
                }
                Text(
                    stringResource(R.string.crop_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                BoxWithConstraints(
                    Modifier.fillMaxWidth().heightIn(max = 400.dp).padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val frameW = minOf(maxWidth, maxHeight * aspectRatio)
                    val frameH = frameW / aspectRatio
                    val frameWpx = with(density) { frameW.toPx() }
                    val frameHpx = with(density) { frameH.toPx() }
                    val base = max(frameWpx / bitmap.width, frameHpx / bitmap.height)
                    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
                        val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                        val total = base * newScale
                        val maxX = max(0f, (bitmap.width * total - frameWpx) / 2f)
                        val maxY = max(0f, (bitmap.height * total - frameHpx) / 2f)
                        scale = newScale
                        offset = Offset(
                            (offset.x + panChange.x).coerceIn(-maxX, maxX),
                            (offset.y + panChange.y).coerceIn(-maxY, maxY),
                        )
                    }
                    Box(
                        Modifier.size(frameW, frameH).clip(RoundedCornerShape(16.dp))
                            .onSizeChanged { frameSize = it }
                            .background(Color.Black)
                            .transformable(transformState),
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            val total = base * scale
                            withTransform({
                                translate(size.width / 2f + offset.x, size.height / 2f + offset.y)
                                scale(total, total, pivot = Offset.Zero)
                            }) {
                                drawImage(
                                    image,
                                    dstOffset = IntOffset(-bitmap.width / 2, -bitmap.height / 2),
                                    dstSize = IntSize(bitmap.width, bitmap.height),
                                    filterQuality = FilterQuality.Medium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Decodes at most ~2048px and applies the EXIF rotation so phone photos are not sideways. */
private suspend fun loadBitmap(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return@runCatching null
        rotateByExif(decoded, bytes)
    }.getOrNull()
}

/** `android.media.ExifInterface(InputStream)` exists since API 24, below our minSdk 25. */
private fun rotateByExif(bitmap: Bitmap, bytes: ByteArray): Bitmap {
    val orientation = runCatching {
        android.media.ExifInterface(bytes.inputStream()).getAttributeInt(
            android.media.ExifInterface.TAG_ORIENTATION,
            android.media.ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(android.media.ExifInterface.ORIENTATION_NORMAL)
    val degrees = when (orientation) {
        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> return bitmap
    }
    return runCatching {
        val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }.getOrDefault(bitmap)
}

/** Saves under files/custom with a fresh name so Compose reloads the preview. */
private suspend fun saveImage(context: Context, bitmap: Bitmap, prefix: String, png: Boolean): String? =
    withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, "custom").apply { mkdirs() }
            val file = File(dir, "$prefix-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(6)}.${if (png) "png" else "jpg"}")
            file.outputStream().use { out ->
                bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, out)
            }
            file.absolutePath
        }.getOrNull()
    }
