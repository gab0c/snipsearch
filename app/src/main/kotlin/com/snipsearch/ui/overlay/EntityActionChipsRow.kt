package com.snipsearch.ui.overlay

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
            val label = when (entity.kind) {
                EntityKind.URL -> R.string.overlay_open_url
                EntityKind.EMAIL -> R.string.overlay_email
                EntityKind.PHONE -> R.string.overlay_dial
                EntityKind.COUPON -> R.string.overlay_copy_coupon
                EntityKind.TRACKING -> R.string.overlay_copy_tracking
                EntityKind.QR_CODE -> R.string.overlay_copy_qr
                EntityKind.BARCODE -> R.string.overlay_copy_barcode
            }
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
                    onClick = { if (context.searchWeb(selectedText)) onExternalAction() },
                    label = { Text(stringResource(R.string.overlay_search_web)) },
                )
            }
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
    return openSelectionIntent(intent)
}

/** Browser support varies; leave the overlay open when no handler accepts the query. */
internal fun Context.searchWeb(query: String): Boolean {
    if (query.isBlank()) return false
    return openSelectionIntent(
        Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query),
    )
}

private fun Context.openSelectionIntent(intent: Intent): Boolean {
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
