package com.snipsearch.extraction

/** Rectangle in pixels with exclusive right and bottom edges. */
data class PixelRegion(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun translated(dx: Int, dy: Int) = PixelRegion(left + dx, top + dy, right + dx, bottom + dy)

    fun intersection(other: PixelRegion): PixelRegion? {
        val overlap = PixelRegion(
            maxOf(left, other.left), maxOf(top, other.top),
            minOf(right, other.right), minOf(bottom, other.bottom),
        )
        return overlap.takeIf { it.left < it.right && it.top < it.bottom }
    }

}
