package com.snipsearch.extraction.isolated

import java.nio.FloatBuffer
import java.nio.IntBuffer

/** Exact identity-resize path. Call only when source and detector dimensions match. */
internal object AlignedDetectorInput {
    fun fill(
        argb: IntBuffer,
        width: Int,
        height: Int,
        stride: Int,
        input: FloatBuffer,
        scratch: DirectFloatScratch,
        scales: FloatArray,
        biases: FloatArray,
    ) {
        val row = IntArray(width)
        val blue = scratch.rowBlue(width)
        val green = scratch.rowGreen(width)
        val red = scratch.rowRed(width)
        val source = argb.duplicate()
        val plane = width * height
        try {
            for (y in 0 until height) {
                source.position(y * stride)
                source.get(row)
                for (x in 0 until width) {
                    val pixel = row[x]
                    blue[x] = (pixel and 0xff) * scales[0] + biases[0]
                    green[x] = ((pixel ushr 8) and 0xff) * scales[1] + biases[1]
                    red[x] = ((pixel ushr 16) and 0xff) * scales[2] + biases[2]
                }
                val offset = y * width
                input.position(offset)
                input.put(blue, 0, width)
                input.position(plane + offset)
                input.put(green, 0, width)
                input.position(2 * plane + offset)
                input.put(red, 0, width)
            }
        } finally {
            row.fill(0)
        }
    }
}
