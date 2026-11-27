package com.snipsearch.extraction.isolated

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AlignedDetectorInputTest {
    private val scales = floatArrayOf(1f / (255f * .229f), 1f / (255f * .224f), 1f / (255f * .225f))
    private val biases = floatArrayOf(-.485f / .229f, -.456f / .224f, -.406f / .225f)

    @Test fun matchesOriginalBilinearArithmeticBitForBitIncludingPaddedRows() {
        val random = Random(914)
        for ((width, height) in listOf(32 to 32, 64 to 96, 736 to 32)) {
            for (padding in listOf(0, 7)) {
                val stride = width + padding
                val source = IntArray(stride * height) { random.nextInt() }
                // Include channel extremes and alpha variants; alpha must not affect OCR input.
                listOf(0, -1, 0xff000000.toInt(), 0x00ff0000, 0x0000ff00, 0x000000ff).forEachIndexed { i, p -> source[i] = p }
                val pixels = IntBuffer.wrap(source).also { it.position(3) }
                val initialPosition = pixels.position()
                val output = ByteBuffer.allocateDirect(3 * width * height * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer()
                val scratch = DirectFloatScratch()
                try {
                    AlignedDetectorInput.fill(pixels,width,height,stride,output,scratch,scales,biases)
                    assertEquals(initialPosition,pixels.position())
                    val expected = originalIdentityBilinear(source,width,height,stride)
                    val actual = IntArray(expected.size) { output[it].toRawBits() }
                    assertArrayEquals(expected,actual)
                    scratch.scrub()
                    assertEquals(List(width) { 0f },scratch.rowBlue(width).toList())
                    assertEquals(List(width) { 0f },scratch.rowGreen(width).toList())
                    assertEquals(List(width) { 0f },scratch.rowRed(width).toList())
                } finally {
                    scratch.scrub()
                    PaddleOcrEngine.zeroFloatBuffer(output)
                    source.fill(0)
                }
            }
        }
    }

    /** Independent reference for the old detector formula with scaleX=scaleY=1. */
    private fun originalIdentityBilinear(pixels: IntArray,width: Int,height: Int,stride: Int): IntArray {
        val expected = IntArray(3*width*height)
        for (y in 0 until height) for (x in 0 until width) for (c in 0..2) {
            val shift = c*8
            fun channel(px: Int) = ((px ushr shift) and 255).toFloat()
            val top = channel(pixels[y*stride+x])*1f + channel(pixels[y*stride+minOf(x+1,width-1)])*0f
            val bottom = channel(pixels[minOf(y+1,height-1)*stride+x])*1f +
                channel(pixels[minOf(y+1,height-1)*stride+minOf(x+1,width-1)])*0f
            expected[c*width*height+y*width+x] = ((top*1f+bottom*0f)*scales[c]+biases[c]).toRawBits()
        }
        return expected
    }
}
