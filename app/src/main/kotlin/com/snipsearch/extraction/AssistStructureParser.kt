package com.snipsearch.extraction

import android.app.assist.AssistStructure
import android.text.InputType
import android.view.View
import kotlin.math.ceil

/** Text supplied by the app, with a line or node's screen-space bounds. */
data class AssistTextBlock(
    val text: String,
    val bounds: PixelRegion,
    val textSize: Float = 0f,
    val textStyle: Int = 0,
    val baseline: Int? = null,
)

object AssistStructureParser {
    fun extract(
        structures: List<AssistStructure>,
        crop: PixelRegion,
        displayId: Int,
    ): List<AssistTextBlock> {
        val blocks = linkedSetOf<AssistTextBlock>()
        val pending = ArrayDeque<NodePosition>()
        for (structure in structures) {
            repeat(structure.windowNodeCount) { windowIndex ->
                val window = structure.getWindowNodeAt(windowIndex)
                if (window.displayId != displayId) return@repeat
                pending.addLast(NodePosition(window.rootViewNode, window.left, window.top))
                while (pending.isNotEmpty()) {
                    val (node, parentX, parentY) = pending.removeLast()
                    if (node.visibility != View.VISIBLE || node.alpha <= 0f || node.isAssistBlocked) {
                        continue
                    }
                    val bounds = nodeBounds(parentX, parentY, node.left, node.top, node.width, node.height)
                    // Accessibility descriptions can describe an entire row or an icon.
                    // Only visible UI text has a useful position for on-screen selection.
                    if (bounds.intersection(crop) != null && !isPassword(node.inputType)) {
                        blocks += textLines(
                            node.text?.toString().orEmpty(), bounds, crop,
                            node.textLineCharOffsets, node.textLineBaselines, node.textSize, node.textStyle,
                        )
                    }

                    // Children can extend outside their parent.
                    val childX = bounds.left - node.scrollX
                    val childY = bounds.top - node.scrollY
                    for (childIndex in node.childCount - 1 downTo 0) {
                        pending.addLast(NodePosition(node.getChildAt(childIndex), childX, childY))
                    }
                }
            }
        }
        return blocks.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    private data class NodePosition(val node: AssistStructure.ViewNode, val parentX: Int, val parentY: Int)
}

internal fun nodeBounds(
    parentX: Int,
    parentY: Int,
    left: Int,
    top: Int,
    width: Int,
    height: Int,
) = PixelRegion(left, top, left + width, top + height).translated(parentX, parentY)

internal fun textLines(
    text: String,
    bounds: PixelRegion,
    crop: PixelRegion,
    offsets: IntArray?,
    baselines: IntArray?,
    textSize: Float,
    textStyle: Int = 0,
): List<AssistTextBlock> {
    if (text.isBlank()) return emptyList()
    if (offsets == null || offsets.isEmpty() || baselines == null || offsets.size != baselines.size ||
        offsets.first() != 0 || !textSize.isFinite() || textSize <= 0f ||
        offsets.indices.any { index ->
            offsets[index] !in text.indices ||
                (index > 0 && (offsets[index] <= offsets[index - 1] || baselines[index] <= baselines[index - 1]))
        }
    ) {
        return if (bounds.intersection(crop) == null) emptyList()
        else listOf(AssistTextBlock(text.trim(), bounds, textSize, textStyle))
    }

    // AssistStructure provides line starts and vertical baselines, but no per-glyph X positions.
    val ascent = ceil(textSize).toInt()
    val descent = ceil(textSize * 0.25f).toInt()
    return offsets.indices.mapNotNull { index ->
        val baseline = bounds.top + baselines[index]
        val lineBounds = PixelRegion(bounds.left, baseline - ascent, bounds.right, baseline + descent)
        val visible = lineBounds.intersection(bounds) ?: return@mapNotNull null
        if (visible.intersection(crop) == null) return@mapNotNull null
        val line = text.substring(offsets[index], offsets.getOrElse(index + 1) { text.length }).trim()
        if (line.isEmpty()) null else AssistTextBlock(line, visible, textSize, textStyle, baseline)
    }
}


internal fun isPassword(inputType: Int): Boolean {
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    return when (inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        else -> false
    }
}
