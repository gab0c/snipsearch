package com.snipsearch.extraction.ocr

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Normalized horizontal and vertical word span inside a rectified line crop (`[0.0, 1.0]`). */
internal data class RefinedWordSpan(
    val hStart: Double,
    val hEnd: Double,
    val vStart: Double,
    val vEnd: Double,
)

/**
 * Refines coarse CTC step spans using calibrated CTC priors and per-line foreground ink bounds
 * measured directly from the rectified recognizer input tensor.
 */
internal object WordBoundsRefiner {
    private const val CTC_LEFT_SHIFT_STEPS = 0.90
    private const val CTC_RIGHT_SHIFT_STEPS = 0.10
    private const val FALLBACK_V_START = 0.16
    private const val FALLBACK_V_END = 0.84
    private const val MAX_BORDER_DIFF = 0.16f
    private const val MAX_LINE_NOISE = 0.12f
    private const val MIN_PEAK_SIGNAL = 0.14f
    private const val MIN_SNR = 5.0f
    private const val NOISE_THRESH_MULT = 3.0f
    private const val MIN_INK_THRESH = 0.08f
    private const val PEAK_INK_RATIO = 0.22f
    private const val PEAK_STRONG_RATIO = 0.30f

    fun refine(
        planar: FloatArray,
        width: Int,
        contentWidth: Int,
        height: Int,
        steps: Int,
        words: List<DecodedWord>,
    ): List<RefinedWordSpan> {
        if (words.isEmpty()) return emptyList()
        val n = words.size
        if (contentWidth <= 0 || steps <= 0) {
            return List(n) { RefinedWordSpan(0.0, 1.0, FALLBACK_V_START, FALLBACK_V_END) }
        }

        val stepPx = width.toDouble() / steps
        val contentLimit = contentWidth.toDouble()
        val pL = DoubleArray(n)
        val pR = DoubleArray(n)
        for (i in 0 until n) {
            val left = ((words[i].firstStep - CTC_LEFT_SHIFT_STEPS) * stepPx).coerceIn(0.0, contentLimit)
            val right = ((words[i].endStep + CTC_RIGHT_SHIFT_STEPS) * stepPx).coerceIn(left, contentLimit)
            pL[i] = left
            pR[i] = right
        }
        for (i in 1 until n) {
            if (pL[i] < pR[i - 1]) {
                val mid = 0.5 * (pR[i - 1] + pL[i])
                pR[i - 1] = mid
                pL[i] = mid
            }
        }

        fun fallback(): List<RefinedWordSpan> = List(n) { i ->
            val start = (pL[i] / contentLimit).coerceIn(0.0, 1.0)
            val end = (pR[i] / contentLimit).coerceIn(start, 1.0)
            RefinedWordSpan(start, end, FALLBACK_V_START, FALLBACK_V_END)
        }

        if (width < contentWidth || height < 12 || planar.size < 3 * width * height) {
            return fallback()
        }

        val lineX0 = floor(pL[0] - stepPx).toInt().coerceIn(0, contentWidth)
        val lineX1 = ceil(pR[n - 1] + stepPx).toInt().coerceIn(lineX0, contentWidth)
        if (lineX1 <= lineX0) {
            return fallback()
        }

        val rawTb = FloatArray(contentWidth)
        val rawTg = FloatArray(contentWidth)
        val rawTr = FloatArray(contentWidth)
        val rawBb = FloatArray(contentWidth)
        val rawBg = FloatArray(contentWidth)
        val rawBr = FloatArray(contentWidth)
        val bgTb = FloatArray(contentWidth)
        val bgTg = FloatArray(contentWidth)
        val bgTr = FloatArray(contentWidth)
        val bgBb = FloatArray(contentWidth)
        val bgBg = FloatArray(contentWidth)
        val bgBr = FloatArray(contentWidth)
        val colNoise = FloatArray(contentWidth)
        val horizJitter = FloatArray(contentWidth)
        val devBuf = FloatArray(6)
        val sStep = maxOf(1, contentWidth / 32)
        val sampleCount = ((contentWidth + sStep - 1) / sStep) * 2
        val globSampleB = FloatArray(sampleCount)
        val globSampleG = FloatArray(sampleCount)
        val globSampleR = FloatArray(sampleCount)
        val diff = FloatArray(height * contentWidth)
        val hist = IntArray(101)
        val rowCounts = IntArray(height)
        val rowMax = FloatArray(height)
        val rowFullHits = IntArray(height)
        val colCnt = IntArray(contentWidth)
        val colSplitCnt = IntArray(contentWidth)
        val colMax = FloatArray(contentWidth)
        val colSplitMax = FloatArray(contentWidth)
        val splits = IntArray(n + 1)
        val splitHasUnderline = BooleanArray(n + 1)

        try {
            val plane = width * height
            val plane2 = 2 * plane
            val bRow0 = (height - 5) * width
            val bRow1 = (height - 4) * width
            val bRow2 = (height - 3) * width
            val bRow3 = (height - 2) * width
            val bRow4 = (height - 1) * width
            for (x in 0 until contentWidth) {
                rawTb[x] = median5(planar[x], planar[width + x], planar[2 * width + x], planar[3 * width + x], planar[4 * width + x])
                rawTg[x] = median5(
                    planar[plane + x],
                    planar[plane + width + x],
                    planar[plane + 2 * width + x],
                    planar[plane + 3 * width + x],
                    planar[plane + 4 * width + x],
                )
                rawTr[x] = median5(
                    planar[plane2 + x],
                    planar[plane2 + width + x],
                    planar[plane2 + 2 * width + x],
                    planar[plane2 + 3 * width + x],
                    planar[plane2 + 4 * width + x],
                )
                rawBb[x] = median5(planar[bRow0 + x], planar[bRow1 + x], planar[bRow2 + x], planar[bRow3 + x], planar[bRow4 + x])
                rawBg[x] = median5(
                    planar[plane + bRow0 + x],
                    planar[plane + bRow1 + x],
                    planar[plane + bRow2 + x],
                    planar[plane + bRow3 + x],
                    planar[plane + bRow4 + x],
                )
                rawBr[x] = median5(
                    planar[plane2 + bRow0 + x],
                    planar[plane2 + bRow1 + x],
                    planar[plane2 + bRow2 + x],
                    planar[plane2 + bRow3 + x],
                    planar[plane2 + bRow4 + x],
                )
            }

            var sIdx = 0
            var sx = 0
            while (sx < contentWidth) {
                globSampleB[sIdx] = rawTb[sx]
                globSampleG[sIdx] = rawTg[sx]
                globSampleR[sIdx] = rawTr[sx]
                sIdx++
                globSampleB[sIdx] = rawBb[sx]
                globSampleG[sIdx] = rawBg[sx]
                globSampleR[sIdx] = rawBr[sx]
                sIdx++
                sx += sStep
            }
            val globB = medianInPlace(globSampleB, sIdx)
            val globG = medianInPlace(globSampleG, sIdx)
            val globR = medianInPlace(globSampleR, sIdx)

            for (x in 0 until contentWidth) {
                val xl = maxOf(0, x - 1)
                val xr = minOf(contentWidth - 1, x + 1)
                var tb = median3(rawTb[xl], rawTb[x], rawTb[xr])
                var tg = median3(rawTg[xl], rawTg[x], rawTg[xr])
                var tr = median3(rawTr[xl], rawTr[x], rawTr[xr])
                var bb = median3(rawBb[xl], rawBb[x], rawBb[xr])
                var bg = median3(rawBg[xl], rawBg[x], rawBg[xr])
                var br = median3(rawBr[xl], rawBr[x], rawBr[xr])
                if (maxOf(abs(tb - bb), abs(tg - bg), abs(tr - br)) > MAX_BORDER_DIFF) {
                    val dt = maxOf(abs(tb - globB), abs(tg - globG), abs(tr - globR))
                    val db = maxOf(abs(bb - globB), abs(bg - globG), abs(br - globR))
                    if (dt <= db) {
                        bb = tb
                        bg = tg
                        br = tr
                    } else {
                        tb = bb
                        tg = bg
                        tr = br
                    }
                }
                bgTb[x] = tb
                bgTg[x] = tg
                bgTr[x] = tr
                bgBb[x] = bb
                bgBg[x] = bg
                bgBr[x] = br

                for (k in 0..2) {
                    val topOff = (1 + k) * width + x
                    devBuf[k] = maxOf(
                        abs(planar[topOff] - tb),
                        abs(planar[plane + topOff] - tg),
                        abs(planar[plane2 + topOff] - tr),
                    )
                    val botOff = (height - 4 + k) * width + x
                    devBuf[3 + k] = maxOf(
                        abs(planar[botOff] - bb),
                        abs(planar[plane + botOff] - bg),
                        abs(planar[plane2 + botOff] - br),
                    )
                }
                devBuf.sort()
                colNoise[x] = devBuf[3]

                val x2 = minOf(contentWidth - 1, x + 3)
                val dt3 = maxOf(abs(rawTb[x] - rawTb[x2]), abs(rawTg[x] - rawTg[x2]), abs(rawTr[x] - rawTr[x2]))
                val db3 = maxOf(abs(rawBb[x] - rawBb[x2]), abs(rawBg[x] - rawBg[x2]), abs(rawBr[x] - rawBr[x2]))
                horizJitter[x] = minOf(dt3, db3)
            }

            val lineNoise = maxOf(medianInPlace(colNoise, contentWidth), medianInPlace(horizJitter, contentWidth))
            if (lineNoise > MAX_LINE_NOISE) {
                return fallback()
            }

            val coreTop = (height * 0.21).toInt().coerceIn(1, height - 2)
            val coreBot = (height * 0.78).toInt().coerceIn(coreTop, height - 2)
            var coreCount = 0
            val invHm1 = 1.0f / (height - 1)
            for (y in 0 until height) {
                val t = y * invHm1
                val inCoreY = y in coreTop..coreBot
                val rowDOff = y * contentWidth
                val rowPOff = y * width
                for (x in 0 until contentWidth) {
                    val eb = bgTb[x] + (bgBb[x] - bgTb[x]) * t
                    val eg = bgTg[x] + (bgBg[x] - bgTg[x]) * t
                    val er = bgTr[x] + (bgBr[x] - bgTr[x]) * t
                    val pOff = rowPOff + x
                    val d = maxOf(
                        abs(planar[pOff] - eb),
                        abs(planar[plane + pOff] - eg),
                        abs(planar[plane2 + pOff] - er),
                    )
                    diff[rowDOff + x] = d
                    if (inCoreY && x in lineX0 until lineX1) {
                        val bIdx = if (d.isFinite()) (d * 50f).toInt().coerceIn(0, 100) else 0
                        hist[bIdx]++
                        coreCount++
                    }
                }
            }

            if (coreCount == 0) {
                return fallback()
            }
            val target = maxOf(1, (coreCount * 0.96).toInt())
            var acc = 0
            var peakSignal = 0f
            for (bIdx in 0..100) {
                acc += hist[bIdx]
                if (acc >= target) {
                    peakSignal = (bIdx + 0.5f) / 50f
                    break
                }
            }
            if (peakSignal < maxOf(MIN_PEAK_SIGNAL, lineNoise * MIN_SNR)) {
                return fallback()
            }

            val inkThresh = maxOf(MIN_INK_THRESH, lineNoise * NOISE_THRESH_MULT, peakSignal * PEAK_INK_RATIO)
            val strongThresh = maxOf(inkThresh, peakSignal * PEAK_STRONG_RATIO)

            for (y in 0 until height) {
                var cnt = 0
                var mx = 0f
                var fullHits = 0
                val rowDOff = y * contentWidth
                for (x in 0 until contentWidth) {
                    val d = diff[rowDOff + x]
                    if (d >= inkThresh) {
                        fullHits++
                        if (x in lineX0 until lineX1) cnt++
                    }
                    if (x in lineX0 until lineX1 && d > mx) {
                        mx = d
                    }
                }
                rowCounts[y] = cnt
                rowMax[y] = mx
                rowFullHits[y] = fullHits
            }

            var peakY = coreTop
            for (y in (coreTop + 1)..coreBot) {
                if (rowCounts[y] > rowCounts[peakY]) peakY = y
            }
            if (rowCounts[peakY] == 0) {
                return fallback()
            }

            fun rowActive(y: Int) = rowCounts[y] >= 2 || (rowCounts[y] >= 1 && rowMax[y] >= strongThresh)
            fun isDivider(y: Int) = (y < 6 || y > height - 7) && rowFullHits[y] > contentWidth * 0.85

            var inkTop = peakY
            var vGap = 0
            for (y in (peakY - 1) downTo 0) {
                if (rowActive(y) && !isDivider(y)) {
                    inkTop = y
                    vGap = 0
                } else {
                    vGap++
                    if (vGap > 4) break
                }
            }

            var inkBot = peakY
            vGap = 0
            for (y in (peakY + 1) until height) {
                if (rowActive(y) && !isDivider(y)) {
                    inkBot = y
                    vGap = 0
                } else {
                    vGap++
                    if (vGap > 4) break
                }
            }

            val inkH = inkBot - inkTop + 1
            if (inkH < 6) {
                return fallback()
            }

            val vPad = (inkH * 0.10).coerceIn(2.0, 4.0)
            val vStart = ((inkTop - vPad) / height).coerceIn(0.0, 1.0)
            val vEnd = ((inkBot + 1 + vPad) / height).coerceIn(vStart, 1.0)

            val underlineMargin = if (inkH >= 16) maxOf(3, (inkH * 0.14).roundToInt()) else 0
            val splitBot = (inkBot - underlineMargin).coerceIn(minOf(inkTop + 4, inkBot), inkBot)
            for (y in inkTop..inkBot) {
                val rowDOff = y * contentWidth
                val inSplit = y <= splitBot
                for (x in 0 until contentWidth) {
                    val d = diff[rowDOff + x]
                    if (d >= inkThresh) {
                        colCnt[x]++
                        if (inSplit) colSplitCnt[x]++
                    }
                    if (d > colMax[x]) colMax[x] = d
                    if (inSplit && d > colSplitMax[x]) colSplitMax[x] = d
                }
            }

            splits[0] = 0
            splits[n] = contentWidth
            for (i in 1 until n) {
                val estGap = 0.5 * (pR[i - 1] + pL[i])
                val prevSpan = maxOf(stepPx, pR[i - 1] - pL[i - 1])
                val nextSpan = maxOf(stepPx, pR[i] - pL[i])
                val sL = maxOf(
                    splits[i - 1] + 1,
                    floor(maxOf(pL[i - 1] + prevSpan * 0.35, estGap - 2.0 * stepPx)).toInt(),
                )
                val sR = minOf(
                    contentWidth - 1,
                    ceil(minOf(pR[i] - nextSpan * 0.35, estGap + 2.0 * stepPx)).toInt(),
                )
                if (sL >= sR) {
                    splits[i] = estGap.roundToInt().coerceIn(splits[i - 1], contentWidth)
                } else {
                    var minV = Int.MAX_VALUE
                    for (x in sL..sR) {
                        if (colSplitCnt[x] < minV) minV = colSplitCnt[x]
                    }
                    var bestScore = Double.NEGATIVE_INFINITY
                    var bestMid = estGap.roundToInt().coerceIn(sL, sR)
                    var rx = sL
                    while (rx <= sR) {
                        if (colSplitCnt[rx] <= minV) {
                            val r0 = rx
                            while (rx <= sR && colSplitCnt[rx] <= minV) rx++
                            val r1 = rx - 1
                            val center = 0.5 * (r0 + r1)
                            val runWidth = r1 - r0 + 1
                            val score = runWidth * 1.5 - abs(center - estGap)
                            if (score > bestScore) {
                                bestScore = score
                                bestMid = center.roundToInt()
                            }
                        } else {
                            rx++
                        }
                    }
                    splits[i] = bestMid.coerceIn(splits[i - 1], contentWidth)
                    if (bestMid in 0 until contentWidth && colCnt[bestMid] > colSplitCnt[bestMid]) {
                        splitHasUnderline[i] = true
                    }
                }
            }

            fun isActiveCol(x: Int, bodyOnly: Boolean): Boolean = if (bodyOnly) {
                colSplitCnt[x] >= 2 || (colSplitCnt[x] >= 1 && colSplitMax[x] >= strongThresh)
            } else {
                colCnt[x] >= 2 || (colCnt[x] >= 1 && colMax[x] >= strongThresh)
            }

            val maxIntraGap = maxOf(5, (inkH * 0.24).roundToInt())
            val hPad = 1.0
            val out = ArrayList<RefinedWordSpan>(n)
            for (i in 0 until n) {
                var lo = splits[i]
                var hi = splits[i + 1]
                if (i == 0) lo = maxOf(lo, floor(pL[0] - 3.0 * stepPx).toInt())
                if (i == n - 1) hi = minOf(hi, ceil(pR[n - 1] + 2.5 * stepPx).toInt())
                if (hi <= lo) {
                    val start = (pL[i] / contentLimit).coerceIn(0.0, 1.0)
                    val end = (pR[i] / contentLimit).coerceIn(start, 1.0)
                    out += RefinedWordSpan(start, end, vStart, vEnd)
                    continue
                }

                val useBodyLeft = splitHasUnderline[i]
                val useBodyRight = splitHasUnderline[i + 1]
                val seedBody = useBodyLeft || useBodyRight
                val seedL = maxOf(lo, floor(pL[i] - 0.5 * stepPx).toInt())
                val seedR = minOf(hi, ceil(pR[i] + 0.5 * stepPx).toInt())
                var sFirst = -1
                var sLast = -1
                for (x in seedL until seedR) {
                    if (isActiveCol(x, seedBody)) {
                        if (sFirst < 0) sFirst = x
                        sLast = x
                    }
                }
                if (sFirst < 0) {
                    for (x in lo until hi) {
                        if (isActiveCol(x, false)) {
                            if (sFirst < 0) sFirst = x
                            sLast = x
                        }
                    }
                }
                if (sFirst < 0) {
                    val start = (pL[i] / contentLimit).coerceIn(0.0, 1.0)
                    val end = (pR[i] / contentLimit).coerceIn(start, 1.0)
                    out += RefinedWordSpan(start, end, vStart, vEnd)
                    continue
                }

                var wl = sFirst
                var gap = 0
                for (x in (wl - 1) downTo lo) {
                    if (isActiveCol(x, useBodyLeft)) {
                        wl = x
                        gap = 0
                    } else {
                        gap++
                        if (gap > maxIntraGap) break
                    }
                }

                var wr = sLast
                gap = 0
                for (x in (wr + 1) until hi) {
                    if (isActiveCol(x, useBodyRight)) {
                        wr = x
                        gap = 0
                    } else {
                        gap++
                        if (gap > maxIntraGap) break
                    }
                }

                val fl = maxOf(splits[i].toDouble(), wl - hPad)
                val fr = minOf(splits[i + 1].toDouble(), wr + 1.0 + hPad)
                val hStart = (fl / contentLimit).coerceIn(0.0, 1.0)
                val hEnd = (fr / contentLimit).coerceIn(hStart, 1.0)
                out += RefinedWordSpan(hStart, hEnd, vStart, vEnd)
            }
            return out
        } finally {
            rawTb.fill(0f)
            rawTg.fill(0f)
            rawTr.fill(0f)
            rawBb.fill(0f)
            rawBg.fill(0f)
            rawBr.fill(0f)
            bgTb.fill(0f)
            bgTg.fill(0f)
            bgTr.fill(0f)
            bgBb.fill(0f)
            bgBg.fill(0f)
            bgBr.fill(0f)
            colNoise.fill(0f)
            horizJitter.fill(0f)
            devBuf.fill(0f)
            globSampleB.fill(0f)
            globSampleG.fill(0f)
            globSampleR.fill(0f)
            diff.fill(0f)
            hist.fill(0)
            rowCounts.fill(0)
            rowMax.fill(0f)
            rowFullHits.fill(0)
            colCnt.fill(0)
            colSplitCnt.fill(0)
            colMax.fill(0f)
            colSplitMax.fill(0f)
            splits.fill(0)
            splitHasUnderline.fill(false)
        }
    }

    private fun median3(a: Float, b: Float, c: Float): Float {
        var x = a
        var y = b
        var z = c
        if (x > y) {
            val t = x
            x = y
            y = t
        }
        if (y > z) {
            y = z
        }
        if (x > y) {
            y = x
        }
        return y
    }

    private fun median5(a: Float, b: Float, c: Float, d: Float, e: Float): Float {
        var x0 = a
        var x1 = b
        var x2 = c
        var x3 = d
        var x4 = e
        if (x0 > x1) { val t = x0; x0 = x1; x1 = t }
        if (x3 > x4) { val t = x3; x3 = x4; x4 = t }
        if (x0 > x3) {
            val t0 = x0; x0 = x3; x3 = t0
            val t1 = x1; x1 = x4; x4 = t1
        }
        if (x2 > x1) { val t = x2; x2 = x1; x1 = t }
        if (x3 > x1) {
            return if (x2 < x3) minOf(x1, x3) else minOf(x2, x4)
        }
        return if (x4 < x2) minOf(x2, x4) else minOf(x1, x4)
    }

    // Mutates `values` in place; callers must scrub the array before returning.
    private fun medianInPlace(values: FloatArray, count: Int): Float {
        if (count <= 0) return 0f
        values.sort(0, count)
        return values[count / 2]
    }
}
