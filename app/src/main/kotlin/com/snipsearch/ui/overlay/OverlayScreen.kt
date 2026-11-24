package com.snipsearch.ui.overlay

import android.app.assist.AssistStructure
import android.graphics.Bitmap
import android.icu.text.Bidi
import android.graphics.Paint
import android.graphics.Rect
import android.view.Display
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import com.snipsearch.R
import com.snipsearch.extraction.AssistStructureParser
import com.snipsearch.extraction.AssistTextBlock
import com.snipsearch.extraction.DetectedEntity
import com.snipsearch.extraction.EntityAndQrDetector
import com.snipsearch.extraction.EntityKind
import com.snipsearch.extraction.PixelRegion
import com.snipsearch.extraction.OcrLine
import com.snipsearch.extraction.OcrTextCorrection
import com.snipsearch.extraction.isolated.OcrClientCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Mount for each assistant invocation, within the session's MaterialTheme. The session owns
 * [screenshot] until [onScreenshotConsumed] and must release it on hide if no crop was locked.
 * The callback must release the original and update [screenshot] to null. Remove this composable
 * on hide to clear the locked crop and extraction state. Pass null while waiting for capture.
 * Handle protected or unavailable captures and system Back in the session. Supply a new
 * [assistStructures] list when additional assist data arrives.
 *
 * [screenshotScreenBounds] can describe a scaled or offset capture in AssistStructure coordinates.
 * By default the bitmap covers a display starting at (0, 0) with the same pixel dimensions.
 */
@Composable
fun OverlayScreen(
    screenshot: Bitmap?,
    assistStructures: List<AssistStructure>,
    onScreenshotConsumed: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    displayId: Int = Display.DEFAULT_DISPLAY,
    screenshotScreenBounds: PixelRegion? = null,
) {
    var imageMode by remember(screenshot) { mutableStateOf(false) }
    var imageSelection by remember(screenshot) { mutableStateOf<PixelRegion?>(null) }
    var locked by remember { mutableStateOf<LockedCrop?>(null) }
    var cropFailed by remember(screenshot) { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { locked?.bitmap?.scrubAndRecycle() }
    }

    val crop = locked
    val source = crop?.bitmap ?: screenshot
    val originalSize = crop?.originalSize ?: screenshot?.let { IntSize(it.width, it.height) }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (source != null && originalSize != null) {
            val fullImage = PixelRegion(0, 0, originalSize.width, originalSize.height)
            val screen = screenshotScreenBounds?.takeIf { it.width > 0 && it.height > 0 } ?: fullImage
            ImageViewport(source, originalSize, crop?.region ?: fullImage, Modifier.fillMaxSize()) { viewport ->
                if (crop != null) {
                    val cropPixels = PixelRegion(0, 0, crop.region.width, crop.region.height)
                    val codes = rememberCodes(crop.bitmap, cropPixels, crop.region)
                    TextSelection(
                        crop.bitmap, crop.region, assistStructures, displayId, screen, viewport,
                        codes.orEmpty(), onDismiss,
                    )
                } else if (imageMode) {
                    val region = imageSelection
                    val codes = if (region != null) rememberCodes(source, region, region) else null
                    if (region == null) {
                        Canvas(Modifier.fillMaxSize()) { drawRect(Color.Black.copy(alpha = 0.21f)) }
                        GestureLassoCanvas(viewport, { imageSelection = it }, Modifier.fillMaxSize())
                    } else {
                        AdjustableCropBox(region, viewport, { imageSelection = it }, Modifier.fillMaxSize())
                    }
                    OverlayCard(Modifier.align(Alignment.BottomCenter)) {
                        if (region != null) {
                            SelectionActions(
                                selectedText = "",
                                entities = codes.orEmpty(),
                                message = if (codes == null) R.string.overlay_scanning_codes else R.string.overlay_no_codes,
                                onDismiss = onDismiss,
                            )
                        } else {
                            OverlayMessage(R.string.overlay_select_region)
                        }
                        if (cropFailed) OverlayMessage(R.string.overlay_crop_failed)
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = {
                                imageMode = false
                                imageSelection = null
                                cropFailed = false
                            }) { Text(stringResource(R.string.overlay_text_mode)) }
                            if (region != null) {
                                TextButton(onClick = {
                                    imageSelection = null
                                    cropFailed = false
                                }) { Text(stringResource(R.string.overlay_reselect)) }
                                Button(onClick = {
                                    if (locked == null && !source.isRecycled) {
                                        val bitmap = copyCrop(source, region)
                                        if (bitmap == null) {
                                            cropFailed = true
                                        } else {
                                            locked = LockedCrop(bitmap, originalSize, region)
                                            onScreenshotConsumed()
                                        }
                                    }
                                }) { Text(stringResource(R.string.overlay_lock_selection)) }
                            }
                        }
                    }
                } else {
                    TextSelection(
                        source, fullImage, assistStructures, displayId, screen, viewport,
                        emptyList(), onDismiss, onImageMode = { imageMode = true },
                    )
                }
            }
        }
        Surface(
            modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(12.dp),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.overlay_close)) }
        }
    }
}

@Composable
private fun BoxScope.TextSelection(
    bitmap: Bitmap,
    region: PixelRegion,
    structures: List<AssistStructure>,
    displayId: Int,
    screen: PixelRegion,
    viewport: CropViewport,
    codes: List<DetectedEntity>,
    onDismiss: () -> Unit,
    onImageMode: (() -> Unit)? = null,
) {
    val context = LocalContext.current.applicationContext
    val ocr = remember(context) { OcrClientCoordinator(context) }
    val imageBounds = viewport.imageBounds
    val screenCrop = region.mapBetween(imageBounds, screen)
    var assist by remember(bitmap, structures, screenCrop, displayId) {
        mutableStateOf<List<AssistTextBlock>>(emptyList())
    }
    var request by remember(bitmap) { mutableIntStateOf(0) }
    var recognized by remember(bitmap, region, request, screen, imageBounds) {
        mutableStateOf<List<OcrLine>?>(null)
    }
    var failed by remember(bitmap, region, request, screen, imageBounds) { mutableStateOf(false) }

    LaunchedEffect(bitmap, structures, screenCrop, displayId) {
        assist = withContext(Dispatchers.Default) {
            AssistStructureParser.extract(structures, screenCrop, displayId)
        }
    }
    LaunchedEffect(bitmap, region, request, screen, imageBounds) {
        try {
            recognized = ocr.recognize(bitmap).map { line ->
                line.copy(words = line.words.map { word ->
                    val bounds = word.bounds.translated(region.left, region.top)
                    word.copy(bounds = bounds.mapBetween(imageBounds, screen))
                })
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            failed = true
            recognized = emptyList()
        }
    }

    val lines = recognized
    val options = remember(lines, assist, screenCrop) {
        when {
            lines == null -> null
            lines.isEmpty() -> TextOptions(assist, selectableWords(assist, screenCrop))
            else -> {
                val corrected = OcrTextCorrection.correct(lines, assist)
                val words = corrected.flatMapIndexed { lineIndex, line ->
                    val bidi = Bidi(line.text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
                    line.words.map { word ->
                        SelectableWord(lineIndex, word.characters, word.bounds, bidi.getLevelAt(word.characters.first) % 2 != 0)
                    }
                }
                TextOptions(corrected.map { AssistTextBlock(it.text, it.bounds) }, words)
            }
        }
    }
    var selection by remember(options?.words?.map { it.bounds }) { mutableStateOf<IntRange?>(null) }
    val selected = if (options == null) "" else selectedText(options.blocks, options.words, selection)
    val entities = remember(codes, selected) { (codes + EntityAndQrDetector.extract(selected)).distinct() }
    if (options?.words?.isNotEmpty() == true) {
        SelectableTextLayer(options.words, viewport, screen, selection, { selection = it }, Modifier.fillMaxSize())
    }
    OverlayCard(Modifier.align(Alignment.BottomCenter)) {
        val message = when {
            options == null -> R.string.overlay_running_ocr
            failed && options.words.isEmpty() -> R.string.overlay_ocr_failed
            options.words.isEmpty() -> R.string.overlay_no_text
            else -> R.string.overlay_select_text
        }
        Row(Modifier.fillMaxWidth().padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SelectionActions(selected, entities, message, onDismiss) }
            if (failed) {
                TextButton(onClick = { request++ }) { Text(stringResource(R.string.overlay_ocr_retry)) }
            }
            if (onImageMode != null) {
                TextButton(onClick = onImageMode) { Text(stringResource(R.string.overlay_image_mode)) }
            }
        }
    }
}

private data class TextOptions(val blocks: List<AssistTextBlock>, val words: List<SelectableWord>)

@Composable
private fun OverlayCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().windowInsetsPadding(
        WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
    ).padding(10.dp)) {
        Surface(
            Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
        }
    }
}

@Composable
private fun rememberCodes(source: Bitmap, region: PixelRegion, originalRegion: PixelRegion): List<DetectedEntity>? {
    var barcode by remember(originalRegion) { mutableStateOf<List<DetectedEntity>?>(null) }
    LaunchedEffect(source, region) {
        if (barcode != null) return@LaunchedEffect
        delay(100)
        if (source.isRecycled) return@LaunchedEffect
        // Copy pixels before suspending so the session can safely recycle the bitmap on hide.
        val pixels = IntArray(region.width * region.height)
        barcode = try {
            val copied = source.withSoftwarePixels {
                it.getPixels(pixels, 0, region.width, region.left, region.top, region.width, region.height)
                true
            }
            if (copied == null) emptyList() else withContext(Dispatchers.Default) {
                val decoded = EntityAndQrDetector.decode(pixels, region.width, region.height)
                buildList {
                    if (decoded != null) {
                        add(decoded)
                        if (decoded.kind == EntityKind.QR_CODE && EntityAndQrDetector.isWebUrl(decoded.value)) {
                            add(DetectedEntity(EntityKind.URL, decoded.value))
                        }
                    }
                }
            }
        } finally {
            pixels.fill(0)
        }
    }
    return barcode
}

@Composable
private fun SelectionActions(
    selectedText: String,
    entities: List<DetectedEntity>,
    message: Int,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
        if (selectedText.isEmpty() && entities.isEmpty()) {
            OverlayMessage(message)
        } else {
            EntityActionChipsRow(selectedText, entities, onDismiss)
        }
    }
}

@Composable
private fun OverlayMessage(message: Int) {
    Text(stringResource(message), Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
}

@Composable
private fun ImageViewport(
    bitmap: Bitmap,
    imageSize: IntSize,
    imageRegion: PixelRegion,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(CropViewport) -> Unit,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier.clipToBounds().onSizeChanged { size = it }) {
        if (size.width > 0 && size.height > 0) {
            val viewport = remember(imageSize, size) { CropViewport(imageSize, size) }
            val destination = viewport.toCanvas(imageRegion)
            Canvas(Modifier.fillMaxSize()) {
                if (bitmap.isRecycled) return@Canvas
                drawImage(
                    image,
                    dstOffset = IntOffset(destination.left.roundToInt(), destination.top.roundToInt()),
                    dstSize = IntSize(
                        destination.width.roundToInt().coerceAtLeast(1),
                        destination.height.roundToInt().coerceAtLeast(1),
                    ),
                )
            }
            content(viewport)
        }
    }
}

private data class LockedCrop(
    val bitmap: Bitmap,
    val originalSize: IntSize,
    val region: PixelRegion,
)

private fun copyCrop(screenshot: Bitmap, region: PixelRegion): Bitmap? =
    screenshot.withSoftwarePixels { source ->
        val crop = createBitmap(region.width, region.height)
        val from = Rect(region.left, region.top, region.right, region.bottom)
        val to = Rect(0, 0, region.width, region.height)
        android.graphics.Canvas(crop).drawBitmap(source, from, to, Paint())
        crop
    }

private inline fun <T> Bitmap.withSoftwarePixels(read: (Bitmap) -> T): T? {
    if (config != Bitmap.Config.HARDWARE) return read(this)
    val software = copy(Bitmap.Config.ARGB_8888, true) ?: return null
    try {
        return read(software)
    } finally {
        software.scrubAndRecycle()
    }
}

private fun Bitmap.scrubAndRecycle() {
    if (isMutable) eraseColor(android.graphics.Color.TRANSPARENT)
    recycle()
}
