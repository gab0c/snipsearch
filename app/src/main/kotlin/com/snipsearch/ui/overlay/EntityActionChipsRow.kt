package com.snipsearch.ui.overlay

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.snipsearch.R
import com.snipsearch.extraction.DetectedEntity
import com.snipsearch.extraction.EntityAndQrDetector
import com.snipsearch.extraction.EntityKind

@Composable
internal fun EntityActionChipsRow(
    selectedText: String,
    entities: List<DetectedEntity>,
    onExternalAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(entities) { entity ->
            val label = entity.actionLabel()
            AssistChip(
                onClick = { if (context.actOn(entity)) onExternalAction() },
                label = {
                    Text(
                        stringResource(label, entity.value),
                        modifier = Modifier.widthIn(max = 240.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
        if (selectedText.isNotEmpty()) {
            item {
                AssistChip(
                    onClick = { context.copyExtraction(selectedText) },
                    label = { Text(stringResource(R.string.overlay_copy_text)) },
                )
            }
        }
    }
}

/** Returns true if another app was opened. Clipboard actions stay in the overlay. */
private fun Context.actOn(entity: DetectedEntity): Boolean {
    val intent = when (entity.kind) {
        EntityKind.URL -> {
            if (!EntityAndQrDetector.isWebUrl(entity.value)) return false
            Intent(Intent.ACTION_VIEW, entity.value.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        }
        EntityKind.EMAIL -> Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", entity.value, null))
        EntityKind.PHONE -> Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", entity.value, null))
        EntityKind.COUPON, EntityKind.TRACKING, EntityKind.QR_CODE, EntityKind.BARCODE -> {
            copyExtraction(entity.value)
            return false
        }
    }
    return try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, R.string.overlay_no_action_app, Toast.LENGTH_SHORT).show()
        false
    } catch (_: SecurityException) {
        Toast.makeText(this, R.string.overlay_no_action_app, Toast.LENGTH_SHORT).show()
        false
    }
}

private fun Context.copyExtraction(value: String) {
    val clip = ClipData.newPlainText(getString(R.string.overlay_clip_label), value)
    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}


private fun DetectedEntity.actionLabel(): Int = when (kind) {
    EntityKind.URL -> R.string.overlay_open_url
    EntityKind.EMAIL -> R.string.overlay_email
    EntityKind.PHONE -> R.string.overlay_dial
    EntityKind.COUPON -> R.string.overlay_copy_coupon
    EntityKind.TRACKING -> R.string.overlay_copy_tracking
    EntityKind.QR_CODE -> R.string.overlay_copy_qr
    EntityKind.BARCODE -> R.string.overlay_copy_barcode
}

@Composable
internal fun EntityActionIcon(entity: DetectedEntity, onExternalAction: () -> Unit) {
    val context = LocalContext.current
    val label = stringResource(entity.actionLabel(), entity.value)
    val color = MaterialTheme.colorScheme.onSurface
    DescribedIconButton(
        label = label,
        onClick = { if (context.actOn(entity)) onExternalAction() },
    ) {
        Canvas(Modifier.size(24.dp)) {
            scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
                val stroke = Stroke(1.8f)
                when (entity.kind) {
                    EntityKind.EMAIL -> {
                        drawRoundRect(color, Offset(2f, 4f), Size(20f, 16f),
                            CornerRadius(2f), style = stroke)
                        drawPath(Path().apply {
                            moveTo(2f, 5f); lineTo(12f, 13f); lineTo(22f, 5f)
                        }, color, style = stroke)
                    }
                    EntityKind.PHONE -> drawPath(Path().apply {
                        moveTo(6.6f, 10.8f)
                        cubicTo(8.1f, 13.9f, 10.1f, 15.9f, 13.2f, 17.4f)
                        lineTo(15.4f, 15.2f); lineTo(21f, 17f); lineTo(21f, 21f)
                        cubicTo(11f, 22f, 2f, 13f, 3f, 3f)
                        lineTo(7f, 3f); lineTo(8.8f, 8.6f); close()
                    }, color, style = stroke)
                    EntityKind.URL -> {
                        drawPath(Path().apply {
                            moveTo(11f, 4f); lineTo(4f, 4f); lineTo(4f, 20f); lineTo(20f, 20f); lineTo(20f, 13f)
                            moveTo(13f, 3f); lineTo(21f, 3f); lineTo(21f, 11f)
                            moveTo(21f, 3f); lineTo(11f, 13f)
                        }, color, style = stroke)
                    }
                    // Copyable entities use the same copy glyph plus a small identifying mark.
                    else -> {
                        drawRect(color, Offset(8f, 7f), Size(13f, 15f), style = stroke)
                        drawPath(Path().apply {
                            moveTo(5f, 17f); lineTo(3f, 17f); lineTo(3f, 2f); lineTo(16f, 2f); lineTo(16f, 4f)
                        }, color, style = stroke)
                        if (entity.kind == EntityKind.BARCODE) {
                            for (x in listOf(11f, 14f, 18f)) drawLine(color, Offset(x, 11f), Offset(x, 18f), 1f)
                        } else {
                            drawRect(color, Offset(11f, 11f), Size(3f, 3f), style = stroke)
                        }
                    }
                }
            }
        }
    }
}
