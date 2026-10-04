package komascroll.immersion.clipper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import komascroll.i18n.KSR
import komascroll.panels.PanelBox
import komascroll.panels.PanelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Panel clipper: pick a panel (or drag any area) of a reader page, blur spoilers by dragging boxes
 * over them, and share the result as an image.
 */
@Composable
fun PanelClipDialog(
    page: ReaderPage,
    mangaTitle: String?,
    rightToLeft: Boolean,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val target = remember(page) { (page as? InsertPage)?.parent ?: page }

    val source by produceState<Bitmap?>(null, target) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                target.statusFlow.first { it == Page.State.Ready }
                target.stream?.invoke()?.use { it.readBytes() }?.let(PanelClipRenderer::decode)
            }.onFailure { logcat(LogPriority.ERROR, it) { "Could not load page for clipping" } }.getOrNull()
        }
    }
    val panels by produceState(emptyList<PanelBox>(), target) {
        value = runCatching { Injekt.get<PanelManager>().layoutFor(target, rightToLeft).panels }.getOrDefault(emptyList())
    }

    var mode by remember { mutableStateOf(Mode.SELECT) }
    var crop by remember { mutableStateOf<Rect?>(null) }
    val blurs = remember { mutableStateListOf<Rect>() }
    var addCaption by remember { mutableStateOf(true) }
    var markSpoiler by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }

    // Live preview with the blurs applied; recomputed off the main thread when they change.
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(source, blurs.toList()) {
        val bitmap = source ?: return@LaunchedEffect
        preview = withContext(Dispatchers.Default) { PanelClipRenderer.withBlurs(bitmap, blurs.toList()) }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismissRequest) {
                        Icon(Icons.Outlined.Close, contentDescription = null)
                    }
                    Text(
                        stringResource(KSR.strings.clip_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { if (blurs.isNotEmpty()) blurs.removeAt(blurs.lastIndex) }, enabled = blurs.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = stringResource(KSR.strings.clip_undo_blur))
                    }
                    IconButton(
                        enabled = source != null && !sharing,
                        onClick = {
                            val bitmap = source ?: return@IconButton
                            sharing = true
                            scope.launch {
                                try {
                                    share(
                                        context = context,
                                        source = bitmap,
                                        crop = crop ?: Rect(0, 0, bitmap.width, bitmap.height),
                                        blurs = blurs.toList(),
                                        caption = if (addCaption) caption(mangaTitle, target) else null,
                                        spoiler = markSpoiler,
                                    )
                                    onDismissRequest()
                                } catch (e: Exception) {
                                    logcat(LogPriority.ERROR, e) { "Could not share clip" }
                                    context.toast(KSR.strings.clip_failed)
                                } finally {
                                    sharing = false
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Outlined.Share, contentDescription = stringResource(KSR.strings.clip_share))
                    }
                }

                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = mode == Mode.SELECT,
                        onClick = { mode = Mode.SELECT },
                        label = { Text(stringResource(KSR.strings.clip_mode_select)) },
                    )
                    FilterChip(
                        selected = mode == Mode.BLUR,
                        onClick = { mode = Mode.BLUR },
                        label = { Text(stringResource(KSR.strings.clip_mode_blur)) },
                    )
                }
                Text(
                    stringResource(if (mode == Mode.SELECT) KSR.strings.clip_hint_select else KSR.strings.clip_hint_blur),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    val bitmap = source
                    if (bitmap == null) {
                        CircularProgressIndicator()
                    } else {
                        ClipCanvas(
                            source = bitmap,
                            preview = preview ?: bitmap,
                            panels = panels,
                            mode = mode,
                            crop = crop,
                            blurs = blurs,
                            onCropChange = { crop = it },
                            onAddBlur = { blurs += it },
                            onRemoveBlur = { blurs.remove(it) },
                        )
                    }
                    if (sharing) CircularProgressIndicator()
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = addCaption, onCheckedChange = { addCaption = it })
                    Text(stringResource(KSR.strings.clip_add_caption), style = MaterialTheme.typography.bodyMedium)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = markSpoiler, onCheckedChange = { markSpoiler = it })
                    Text(stringResource(KSR.strings.clip_mark_spoiler), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private enum class Mode { SELECT, BLUR }

/** Maps between image pixels and the fitted (letterboxed) image on screen. */
private class Fit(imageWidth: Int, imageHeight: Int, box: IntSize) {
    val scale = min(box.width / imageWidth.toFloat(), box.height / imageHeight.toFloat())
    val left = (box.width - imageWidth * scale) / 2f
    val top = (box.height - imageHeight * scale) / 2f

    fun toImage(offset: Offset) = Offset((offset.x - left) / scale, (offset.y - top) / scale)
    fun toScreen(x: Float, y: Float) = Offset(left + x * scale, top + y * scale)
}

@Composable
private fun ClipCanvas(
    source: Bitmap,
    preview: Bitmap,
    panels: List<PanelBox>,
    mode: Mode,
    crop: Rect?,
    blurs: List<Rect>,
    onCropChange: (Rect?) -> Unit,
    onAddBlur: (Rect) -> Unit,
    onRemoveBlur: (Rect) -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    val width = source.width
    val height = source.height
    val panelRects = remember(panels, width, height) {
        panels.map { Rect((it.left * width).toInt(), (it.top * height).toInt(), (it.right * width).toInt(), (it.bottom * height).toInt()) }
    }
    val accent = MaterialTheme.colorScheme.primary

    fun imageRect(a: Offset, b: Offset): Rect = Rect(
        min(a.x, b.x).toInt().coerceIn(0, width),
        min(a.y, b.y).toInt().coerceIn(0, height),
        max(a.x, b.x).toInt().coerceIn(0, width),
        max(a.y, b.y).toInt().coerceIn(0, height),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(mode, panelRects, blurs.size, size) {
                if (size.width == 0) return@pointerInput
                val fit = Fit(width, height, size)
                detectTapGestures { offset ->
                    val point = fit.toImage(offset)
                    val x = point.x.toInt()
                    val y = point.y.toInt()
                    when (mode) {
                        Mode.SELECT -> onCropChange(panelRects.firstOrNull { it.contains(x, y) })
                        Mode.BLUR -> blurs.lastOrNull { it.contains(x, y) }?.let(onRemoveBlur)
                    }
                }
            }
            .pointerInput(mode, size) {
                if (size.width == 0) return@pointerInput
                val fit = Fit(width, height, size)
                detectDragGestures(
                    onDragStart = {
                        dragStart = fit.toImage(it)
                        dragEnd = dragStart
                    },
                    onDrag = { change, _ -> dragEnd = fit.toImage(change.position) },
                    onDragEnd = {
                        val start = dragStart
                        val end = dragEnd
                        if (start != null && end != null) {
                            val rect = imageRect(start, end)
                            val big = rect.width() > MIN_DRAG_PX && rect.height() > MIN_DRAG_PX
                            if (big) {
                                when (mode) {
                                    Mode.SELECT -> onCropChange(rect)
                                    Mode.BLUR -> onAddBlur(rect)
                                }
                            }
                        }
                        dragStart = null
                        dragEnd = null
                    },
                    onDragCancel = {
                        dragStart = null
                        dragEnd = null
                    },
                )
            },
    ) {
        Image(
            bitmap = preview.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(Modifier.fillMaxSize()) {
            if (size.width == 0) return@Canvas
            val fit = Fit(width, height, size)
            fun drawImageRect(rect: Rect, color: Color, strokeWidth: Float) {
                val topLeft = fit.toScreen(rect.left.toFloat(), rect.top.toFloat())
                val bottomRight = fit.toScreen(rect.right.toFloat(), rect.bottom.toFloat())
                drawRect(
                    color = color,
                    topLeft = topLeft,
                    size = Size(abs(bottomRight.x - topLeft.x), abs(bottomRight.y - topLeft.y)),
                    style = Stroke(width = strokeWidth),
                )
            }

            // Dim everything outside the clip.
            crop?.let { rect ->
                val topLeft = fit.toScreen(rect.left.toFloat(), rect.top.toFloat())
                val bottomRight = fit.toScreen(rect.right.toFloat(), rect.bottom.toFloat())
                val shade = Color.Black.copy(alpha = 0.55f)
                drawRect(shade, Offset.Zero, Size(this.size.width, topLeft.y))
                drawRect(shade, Offset(0f, bottomRight.y), Size(this.size.width, this.size.height - bottomRight.y))
                drawRect(shade, Offset(0f, topLeft.y), Size(topLeft.x, bottomRight.y - topLeft.y))
                drawRect(
                    shade,
                    Offset(bottomRight.x, topLeft.y),
                    Size(this.size.width - bottomRight.x, bottomRight.y - topLeft.y),
                )
            }
            if (mode == Mode.SELECT) {
                panelRects.forEach { drawImageRect(it, accent.copy(alpha = 0.6f), 2.dp.toPx()) }
            }
            crop?.let { drawImageRect(it, accent, 3.dp.toPx()) }
            blurs.forEach { drawImageRect(it, Color.White.copy(alpha = 0.8f), 1.5.dp.toPx()) }

            val start = dragStart
            val end = dragEnd
            if (start != null && end != null) {
                drawImageRect(imageRect(start, end), if (mode == Mode.SELECT) accent else Color.White, 2.dp.toPx())
            }
        }
    }
}

private const val MIN_DRAG_PX = 12

private fun caption(mangaTitle: String?, page: ReaderPage): String? {
    val chapter = page.chapter.chapter.name.takeIf { it.isNotBlank() }
    return listOfNotNull(mangaTitle?.takeIf { it.isNotBlank() }, chapter).joinToString(" – ").ifEmpty { null }
}

private suspend fun share(
    context: Context,
    source: Bitmap,
    crop: Rect,
    blurs: List<Rect>,
    caption: String?,
    spoiler: Boolean,
) {
    val file = withContext(Dispatchers.Default) {
        val clip = PanelClipRenderer.render(source, crop, blurs)
        val directory = File(context.cacheDir, "komascroll_share").apply { mkdirs() }
        // Discord and some other apps hide attachments whose name starts with SPOILER_ until tapped.
        val name = DiskUtil.buildValidFilename(
            (if (spoiler) "SPOILER_" else "") + "clip-${System.currentTimeMillis()}.jpg",
        )
        File(directory, name).also { file ->
            file.outputStream().use { clip.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            clip.recycle()
        }
    }
    context.startActivity(file.getUriCompat(context).toShareIntent(context, type = "image/jpeg", message = caption))
}

private const val JPEG_QUALITY = 92
