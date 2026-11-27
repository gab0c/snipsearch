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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
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
import com.snipsearch.extraction.OcrLine
import com.snipsearch.extraction.OcrTextCorrection
import com.snipsearch.extraction.OcrTiming
import com.snipsearch.extraction.OcrWord
import com.snipsearch.extraction.PixelRegion
import com.snipsearch.extraction.ocr.OcrCoordinator
import com.snipsearch.extraction.ocr.OcrScanSnapshot
import com.snipsearch.extraction.ocr.SessionOcrRunner
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
internal fun OverlayScreen(
    screenshot: Bitmap?,
    assistStructures: List<AssistStructure>,
    onScreenshotConsumed: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    displayId: Int = Display.DEFAULT_DISPLAY,
    screenshotScreenBounds: PixelRegion? = null,
    ocrRunner: SessionOcrRunner? = null,
    fullScreenScanId: Long = 0L,
    timing: OcrTiming? = null,
) {
    val context = LocalContext.current.applicationContext
    val ownedRunner = remember(context, ocrRunner) {
        if (ocrRunner == null) SessionOcrRunner(OcrCoordinator(context)) else null
    }
    DisposableEffect(ownedRunner) {
        onDispose { ownedRunner?.onSessionDestroy() }
    }
    val activeRunner = ocrRunner ?: requireNotNull(ownedRunner)

    var imageMode by remember(screenshot) { mutableStateOf(false) }
    var imageSelection by remember(screenshot) { mutableStateOf<PixelRegion?>(null) }
    var locked by remember { mutableStateOf<LockedCrop?>(null) }
    var cropFailed by remember(screenshot) { mutableStateOf(false) }
    var localFullScanId by remember(screenshot) { mutableLongStateOf(0L) }
    var retryFullScanId by remember(screenshot) { mutableLongStateOf(0L) }
    var cropScanId by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) {
        onDispose { locked?.bitmap?.scrubAndRecycle() }
    }

    val crop = locked
    val source = crop?.bitmap ?: screenshot
    val originalSize = crop?.originalSize ?: screenshot?.let { IntSize(it.width, it.height) }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (source != null && originalSize != null) {
            val fullImage = PixelRegion(0, 0, originalSize.width, originalSize.height)
            val activeRegion = crop?.region ?: fullImage
            val screen = screenshotScreenBounds?.takeIf { it.width > 0 && it.height > 0 } ?: fullImage

            if (crop == null) {
                LaunchedEffect(screenshot, fullScreenScanId) {
                    if (screenshot != null && !screenshot.isRecycled &&
                        fullScreenScanId == 0L && retryFullScanId == 0L && localFullScanId == 0L
                    ) {
                        val id = activeRunner.nextScanId()
                        localFullScanId = id
                        activeRunner.startScan(id, screenshot, timing)
                    }
                }
            } else if (crop.initialOcrLines == null) {
                LaunchedEffect(crop) {
                    val id = activeRunner.nextScanId()
                    cropScanId = id
                    activeRunner.startScan(id, crop.bitmap, timing = null)
                }
            }

            val effectiveFullScanId = when {
                retryFullScanId != 0L -> retryFullScanId
                fullScreenScanId != 0L -> fullScreenScanId
                else -> localFullScanId
            }
            val activeScanId = if (crop != null) cropScanId else effectiveFullScanId
            val scanState by activeRunner.state.collectAsState()
            val matchesScan = activeScanId != 0L && scanState.scanId == activeScanId
            val useInitialCropLines = crop?.initialOcrLines != null && cropScanId == 0L
            val ocrLines = when {
                useInitialCropLines -> crop.initialOcrLines.orEmpty()
                matchesScan -> scanState.lines
                else -> emptyList()
            }
            val ocrComplete = useInitialCropLines || (matchesScan &&
                (scanState.status == OcrScanSnapshot.Status.COMPLETE || scanState.status == OcrScanSnapshot.Status.FAILED))
            val ocrFailed = !useInitialCropLines && matchesScan && scanState.status == OcrScanSnapshot.Status.FAILED

            val onRetryOcr = {
                if (!source.isRecycled) {
                    val id = activeRunner.nextScanId()
                    if (crop != null) {
                        cropScanId = id
                    } else {
                        retryFullScanId = id
                    }
                    activeRunner.startScan(id, source, if (crop == null) timing else null)
                }
            }

            val textMode = crop != null || !imageMode
            val ocrLoading = textMode && !ocrComplete

            ImageViewport(source, originalSize, activeRegion, Modifier.fillMaxSize()) { viewport ->
                if (crop != null) {
                    val cropPixels = PixelRegion(0, 0, crop.region.width, crop.region.height)
                    val codes = rememberCodes(crop.bitmap, cropPixels, crop.region)
                    TextSelection(
                        bitmap = crop.bitmap,
                        region = crop.region,
                        structures = assistStructures,
                        displayId = displayId,
                        screen = screen,
                        viewport = viewport,
                        ocrLines = ocrLines,
                        ocrComplete = ocrComplete,
                        ocrFailed = ocrFailed,
                        codes = codes.orEmpty(),
                        timing = null,
                        onRetryOcr = onRetryOcr,
                        onDismiss = onDismiss,
                    )
                } else if (imageMode) {
                    val region = imageSelection
                    val codes = if (region != null) rememberCodes(source, region, region) else null
                    if (region == null) {
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
                                        val fullReady = effectiveFullScanId != 0L &&
                                            scanState.scanId == effectiveFullScanId &&
                                            scanState.status == OcrScanSnapshot.Status.COMPLETE
                                        val reusedLines = if (fullReady) {
                                            cropOcrLines(scanState.lines, region).takeIf { it.isNotEmpty() }
                                        } else {
                                            null
                                        }
                                        val bitmap = copyCrop(source, region)
                                        if (bitmap == null) {
                                            cropFailed = true
                                        } else {
                                            locked = LockedCrop(bitmap, originalSize, region, reusedLines)
                                            onScreenshotConsumed()
                                        }
                                    }
                                }) { Text(stringResource(R.string.overlay_lock_selection)) }
                            }
                        }
                    }
                } else {
                    TextSelection(
                        bitmap = source,
                        region = fullImage,
                        structures = assistStructures,
                        displayId = displayId,
                        screen = screen,
                        viewport = viewport,
                        ocrLines = ocrLines,
                        ocrComplete = ocrComplete,
                        ocrFailed = ocrFailed,
                        codes = emptyList(),
                        timing = timing,
                        onRetryOcr = onRetryOcr,
                        onDismiss = onDismiss,
                        onImageMode = { imageMode = true },
                    )
                }
            }
            BorderGlow(ocrLoading, Modifier.fillMaxSize())
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
    ocrLines: List<OcrLine>,
    ocrComplete: Boolean,
    ocrFailed: Boolean,
    codes: List<DetectedEntity>,
    timing: OcrTiming?,
    onRetryOcr: () -> Unit,
    onDismiss: () -> Unit,
    onImageMode: (() -> Unit)? = null,
) {
    val imageBounds = viewport.imageBounds
    val screenCrop = region.mapBetween(imageBounds, screen)
    var assist by remember(bitmap, structures, screenCrop, displayId) {
        mutableStateOf<List<AssistTextBlock>>(emptyList())
    }

    LaunchedEffect(bitmap, structures, screenCrop, displayId) {
        assist = withContext(Dispatchers.Default) {
            AssistStructureParser.extract(structures, screenCrop, displayId)
        }
    }

    val recognized = remember(ocrLines, ocrComplete, region, imageBounds, screen) {
        if (!ocrComplete) {
            null
        } else {
            ocrLines.map { line ->
                line.copy(words = line.words.map { word ->
                    val bounds = word.bounds.translated(region.left, region.top)
                    word.copy(bounds = bounds.mapBetween(imageBounds, screen))
                })
            }
        }
    }

    val lines = recognized
    val resolvedOptions = remember(lines, assist, screenCrop) {
        when {
            lines == null -> null
            lines.isEmpty() -> TextOptions(assist, selectableWords(assist, screenCrop))
            else -> {
                val corrected = OcrTextCorrection.correct(lines, assist)
                val words = corrected.flatMapIndexed { lineIndex, line ->
                    val bidi = if (hasPossibleRtl(line.text)) {
                        Bidi(line.text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
                    } else {
                        null
                    }
                    line.words.map { word ->
                        val rtl = bidi != null && (bidi.getLevelAt(word.characters.first) % 2 != 0)
                        SelectableWord(lineIndex, word.characters, word.bounds, rtl)
                    }
                }
                TextOptions(corrected.map { AssistTextBlock(it.text, it.bounds) }, words)
            }
        }
    }
    LaunchedEffect(timing, resolvedOptions?.words?.isNotEmpty(), lines?.isNotEmpty()) {
        val currentOptions = resolvedOptions ?: return@LaunchedEffect
        if (timing == null || currentOptions.words.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        val fromOcr = lines?.isNotEmpty() == true
        val label = if (fromOcr) "ocr_final" else "assist"
        timing.logFirstSelectableFrame(label, currentOptions.words.size)
        if (fromOcr) {
            timing.logFirstOcrSelectableFrame("ocr_final", currentOptions.words.size)
        }
    }
    var selection by remember(resolvedOptions?.words?.map { it.bounds }) { mutableStateOf<IntRange?>(null) }
    val selected = if (resolvedOptions == null) "" else selectedText(resolvedOptions.blocks, resolvedOptions.words, selection)
    val entities = remember(codes, selected) { (codes + EntityAndQrDetector.extract(selected)).distinct() }
    if (resolvedOptions?.words?.isNotEmpty() == true) {
        SelectableTextLayer(resolvedOptions.words, viewport, screen, selection, { selection = it }, Modifier.fillMaxSize())
    }
    OverlayCard(Modifier.align(Alignment.BottomCenter)) {
        val message = when {
            resolvedOptions == null -> R.string.overlay_running_ocr
            ocrFailed && resolvedOptions.words.isEmpty() -> R.string.overlay_ocr_failed
            resolvedOptions.words.isEmpty() -> R.string.overlay_no_text
            else -> R.string.overlay_select_text
        }
        Row(Modifier.fillMaxWidth().padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SelectionActions(selected, entities, message, onDismiss) }
            if (ocrFailed) {
                TextButton(onClick = onRetryOcr) { Text(stringResource(R.string.overlay_ocr_retry)) }
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
            contentColor = MaterialTheme.colorScheme.onSurface,
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
                val minDim = minOf(this.size.width, this.size.height)
                if (minDim > 0f) {
                    val scaleX = if (this.size.width >= this.size.height) this.size.width / minDim else 1f
                    val scaleY = if (this.size.height > this.size.width) this.size.height / minDim else 1f
                    val brush = Brush.radialGradient(
                        0.00f to Color.Black.copy(alpha = DIM_CENTER_ALPHA),
                        0.45f to Color.Black.copy(alpha = DIM_MID_ALPHA),
                        0.72f to Color.Black.copy(alpha = DIM_EDGE_ALPHA),
                        1.00f to Color.Black.copy(alpha = DIM_CORNER_ALPHA),
                        center = center,
                        radius = minDim * 0.72f,
                    )
                    clipRect {
                        scale(scaleX = scaleX, scaleY = scaleY, pivot = center) {
                            drawRect(brush)
                        }
                    }
                }
            }
            content(viewport)
        }
    }
}

/** Subtle screenshot dimming beneath selection/lasso/cards, slightly brighter at center than edges. */
private const val DIM_CENTER_ALPHA = 0.06f
private const val DIM_MID_ALPHA = 0.075f
private const val DIM_EDGE_ALPHA = 0.115f
private const val DIM_CORNER_ALPHA = 0.15f

private data class LockedCrop(
    val bitmap: Bitmap,
    val originalSize: IntSize,
    val region: PixelRegion,
    val initialOcrLines: List<OcrLine>? = null,
)

private fun hasPossibleRtl(text: String): Boolean {
    for (i in text.indices) {
        if (text[i] >= '\u0590') return true
    }
    return false
}

internal fun cropOcrLines(lines: List<OcrLine>, region: PixelRegion): List<OcrLine> {
    if (lines.isEmpty() || region.width <= 0 || region.height <= 0) return emptyList()
    return lines.mapNotNull { line ->
        val kept = line.words.mapNotNull { word ->
            val bounds = word.bounds
            val overlap = bounds.intersection(region) ?: return@mapNotNull null
            val wordArea = bounds.width.toLong() * bounds.height
            val overlapArea = overlap.width.toLong() * overlap.height
            val centerX = (bounds.left + bounds.right) / 2
            val centerY = (bounds.top + bounds.bottom) / 2
            val centerInside = centerX in region.left until region.right &&
                centerY in region.top until region.bottom
            if (!centerInside && overlapArea * 2 < wordArea) return@mapNotNull null
            val text = line.text.substring(word.characters)
            text to overlap.translated(-region.left, -region.top)
        }
        if (kept.isEmpty()) return@mapNotNull null
        val words = ArrayList<OcrWord>(kept.size)
        val text = buildString {
            kept.forEachIndexed { index, (wordText, translatedBounds) ->
                if (index > 0) append(' ')
                val start = length
                append(wordText)
                words += OcrWord(start until length, translatedBounds)
            }
        }
        OcrLine(text, words)
    }
}

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
