package com.snipsearch.ui.overlay

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import com.snipsearch.extraction.PixelRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CropViewportTest {
    @Test
    fun mapsLassoFromLetterboxedCanvasAndPadsTheBounds() {
        val viewport = CropViewport(IntSize(200, 100), IntSize(400, 400))
        assertEquals(
            PixelRegion(35, 15, 105, 45),
            viewport.selection(Rect(80f, 140f, 200f, 180f), padding = 10f, minimum = 30f),
        )
    }

    @Test
    fun tapAtAnEdgeKeepsMinimumCropInsideTheImage() {
        val viewport = CropViewport(IntSize(100, 100), IntSize(100, 100))
        assertEquals(
            PixelRegion(0, 70, 30, 100),
            viewport.selection(Rect(1f, 98f, 1f, 98f), padding = 10f, minimum = 30f),
        )
    }

    @Test
    fun cornersResizeWithoutFlippingOrExceedingImageBounds() {
        val region = PixelRegion(20, 25, 60, 65)
        val image = IntSize(100, 100)
        assertEquals(PixelRegion(50, 55, 60, 65), CropCorner.TOP_LEFT.resize(region, Offset(90f, 90f), image, 10))
        assertEquals(PixelRegion(20, 25, 100, 100), CropCorner.BOTTOM_RIGHT.resize(region, Offset(90f, 90f), image, 10))
        assertEquals(PixelRegion(20, 0, 30, 65), CropCorner.TOP_RIGHT.resize(region, Offset(-90f, -90f), image, 10))
        assertEquals(PixelRegion(0, 25, 60, 35), CropCorner.BOTTOM_LEFT.resize(region, Offset(-90f, -90f), image, 10))
    }

    @Test
    fun expandedCornerTargetsResolveOverlapsToTheClosestHandle() {
        val crop = Rect(20f, 20f, 50f, 50f)
        assertEquals(CropCorner.TOP_RIGHT, hitCropCorner(crop, Offset(39f, 28f), 32f))
        assertEquals(CropCorner.BOTTOM_LEFT, hitCropCorner(crop, Offset(26f, 41f), 32f))
        assertEquals(CropCorner.TOP_RIGHT, hitCropCorner(crop, Offset(80f, 21f), 32f))
        assertNull(hitCropCorner(crop, Offset(83f, 21f), 32f))
    }

    @Test
    fun mapsAnOffsetCaptureIntoScreenCoordinates() {
        assertEquals(
            PixelRegion(120, 250, 180, 300),
            PixelRegion(10, 25, 40, 50).mapBetween(PixelRegion(0, 0, 100, 100), PixelRegion(100, 200, 300, 400)),
        )
    }
}
