package com.musico.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.floor

data class QrCodeMatrix(
    val version: Int,
    val size: Int,
    val modules: BooleanArray,
) {
    fun get(row: Int, col: Int): Boolean = modules[row * size + col]

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QrCodeMatrix) return false
        return version == other.version && size == other.size && modules.contentEquals(other.modules)
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + size
        result = 31 * result + modules.contentHashCode()
        return result
    }
}

/**
 * Zero-dependency ISO/IEC 18004 QR Code encoder supporting Byte mode, Error Correction Level M,
 * and Versions 1–10 (up to 213 UTF-8 bytes).
 */
object QrCodeEncoder {
    private data class VersionSpecM(
        val version: Int,
        val totalDataCodewords: Int,
        val ecCodewordsPerBlock: Int,
        val group1Blocks: Int,
        val group1DataCodewords: Int,
        val group2Blocks: Int,
        val group2DataCodewords: Int,
        val alignmentCenters: IntArray,
    )

    private val VERSION_SPECS_M = arrayOf(
        VersionSpecM(1, 16, 10, 1, 16, 0, 0, intArrayOf()),
        VersionSpecM(2, 28, 16, 1, 28, 0, 0, intArrayOf(6, 18)),
        VersionSpecM(3, 44, 26, 1, 44, 0, 0, intArrayOf(6, 22)),
        VersionSpecM(4, 64, 18, 2, 32, 0, 0, intArrayOf(6, 26)),
        VersionSpecM(5, 86, 24, 2, 43, 0, 0, intArrayOf(6, 30)),
        VersionSpecM(6, 108, 16, 4, 27, 0, 0, intArrayOf(6, 34)),
        VersionSpecM(7, 124, 18, 4, 31, 0, 0, intArrayOf(6, 22, 38)),
        VersionSpecM(8, 154, 22, 2, 38, 2, 39, intArrayOf(6, 24, 42)),
        VersionSpecM(9, 182, 22, 3, 36, 2, 37, intArrayOf(6, 26, 46)),
        VersionSpecM(10, 216, 26, 4, 43, 1, 44, intArrayOf(6, 28, 50)),
    )

    private val GF_EXP = IntArray(512)
    private val GF_LOG = IntArray(256)

    init {
        var v = 1
        for (i in 0 until 255) {
            GF_EXP[i] = v
            GF_EXP[i + 255] = v
            GF_LOG[v] = i
            v = v shl 1
            if ((v and 0x100) != 0) {
                v = v xor 0x11D
            }
        }
    }

    fun encode(text: String): QrCodeMatrix {
        val payload = text.toByteArray(Charsets.UTF_8)
        require(payload.isNotEmpty()) { "QR payload must not be empty." }

        val spec = VERSION_SPECS_M.firstOrNull { cand ->
            val countBits = if (cand.version >= 10) 16 else 8
            val requiredBits = 4 + countBits + payload.size * 8
            requiredBits <= cand.totalDataCodewords * 8
        } ?: throw IllegalArgumentException("QR payload exceeds 213 UTF-8 bytes (${payload.size} bytes).")

        val dataCodewords = encodeDataCodewords(payload, spec)
        val finalCodewords = interleaveBlocks(dataCodewords, spec)
        val size = 17 + 4 * spec.version

        var bestMask = 0
        var bestPenalty = Int.MAX_VALUE
        var bestModules: BooleanArray? = null

        for (mask in 0 until 8) {
            val isFunction = BooleanArray(size * size)
            val modules = BooleanArray(size * size)
            drawFunctionPatterns(spec, size, modules, isFunction)
            placeCodewords(finalCodewords, size, modules, isFunction)
            applyMask(mask, size, modules, isFunction)
            drawFormatBits(mask, size, modules, isFunction)
            val penalty = computePenaltyScore(size, modules)
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                bestMask = mask
                bestModules = modules
            }
        }

        // Ensure chosen mask is encoded in returned matrix (already encoded into bestModules)
        check(bestMask in 0..7)
        return QrCodeMatrix(
            version = spec.version,
            size = size,
            modules = bestModules ?: error("Failed to construct QR matrix"),
        )
    }

    private fun encodeDataCodewords(payload: ByteArray, spec: VersionSpecM): ByteArray {
        val totalBits = spec.totalDataCodewords * 8
        val bits = ArrayList<Int>(totalBits)

        fun appendBits(value: Int, bitCount: Int) {
            for (i in bitCount - 1 downTo 0) {
                bits.add((value ushr i) and 1)
            }
        }

        val countBits = if (spec.version >= 10) 16 else 8
        appendBits(0b0100, 4) // Byte mode
        appendBits(payload.size, countBits)
        for (b in payload) {
            appendBits(b.toInt() and 0xFF, 8)
        }

        val terminatorLen = minOf(4, totalBits - bits.size)
        repeat(terminatorLen) { bits.add(0) }
        while (bits.size % 8 != 0) {
            bits.add(0)
        }

        val codewords = ByteArray(spec.totalDataCodewords)
        val byteCountFromBits = bits.size / 8
        for (i in 0 until byteCountFromBits) {
            var byteVal = 0
            for (b in 0 until 8) {
                byteVal = (byteVal shl 1) or bits[i * 8 + b]
            }
            codewords[i] = byteVal.toByte()
        }

        var padIdx = 0
        for (i in byteCountFromBits until spec.totalDataCodewords) {
            codewords[i] = (if (padIdx % 2 == 0) 0xEC else 0x11).toByte()
            padIdx += 1
        }
        return codewords
    }

    private fun interleaveBlocks(dataCodewords: ByteArray, spec: VersionSpecM): ByteArray {
        val totalBlocks = spec.group1Blocks + spec.group2Blocks
        val dataBlocks = ArrayList<ByteArray>(totalBlocks)
        val ecBlocks = ArrayList<ByteArray>(totalBlocks)
        val divisor = rsGeneratorPoly(spec.ecCodewordsPerBlock)

        var offset = 0
        repeat(spec.group1Blocks) {
            val block = dataCodewords.copyOfRange(offset, offset + spec.group1DataCodewords)
            offset += spec.group1DataCodewords
            dataBlocks.add(block)
            ecBlocks.add(rsRemainder(block, divisor))
        }
        repeat(spec.group2Blocks) {
            val block = dataCodewords.copyOfRange(offset, offset + spec.group2DataCodewords)
            offset += spec.group2DataCodewords
            dataBlocks.add(block)
            ecBlocks.add(rsRemainder(block, divisor))
        }

        val totalOutputBytes = dataCodewords.size + totalBlocks * spec.ecCodewordsPerBlock
        val out = ByteArray(totalOutputBytes)
        var outPos = 0

        val maxDataBlockLen = maxOf(spec.group1DataCodewords, spec.group2DataCodewords)
        for (col in 0 until maxDataBlockLen) {
            for (block in dataBlocks) {
                if (col < block.size) {
                    out[outPos++] = block[col]
                }
            }
        }
        for (col in 0 until spec.ecCodewordsPerBlock) {
            for (ec in ecBlocks) {
                out[outPos++] = ec[col]
            }
        }
        return out
    }

    private fun rsGeneratorPoly(degree: Int): IntArray {
        var poly = intArrayOf(1)
        for (i in 0 until degree) {
            val next = IntArray(poly.size + 1)
            next[0] = poly[0]
            val root = GF_EXP[i]
            for (j in 1 until poly.size) {
                next[j] = poly[j] xor gfMul(poly[j - 1], root)
            }
            next[poly.size] = gfMul(poly[poly.size - 1], root)
            poly = next
        }
        return poly
    }

    private fun rsRemainder(data: ByteArray, divisor: IntArray): ByteArray {
        val ecLen = divisor.size - 1
        val work = IntArray(data.size + ecLen)
        for (i in data.indices) {
            work[i] = data[i].toInt() and 0xFF
        }
        for (i in data.indices) {
            val factor = work[i]
            if (factor != 0) {
                for (j in 1 until divisor.size) {
                    work[i + j] = work[i + j] xor gfMul(divisor[j], factor)
                }
            }
        }
        val ec = ByteArray(ecLen)
        for (i in 0 until ecLen) {
            ec[i] = work[data.size + i].toByte()
        }
        return ec
    }

    private fun gfMul(a: Int, b: Int): Int {
        if (a == 0 || b == 0) return 0
        return GF_EXP[GF_LOG[a] + GF_LOG[b]]
    }

    private fun drawFunctionPatterns(
        spec: VersionSpecM,
        size: Int,
        modules: BooleanArray,
        isFunction: BooleanArray,
    ) {
        fun setFn(r: Int, c: Int, dark: Boolean) {
            if (r in 0 until size && c in 0 until size) {
                val idx = r * size + c
                modules[idx] = dark
                isFunction[idx] = true
            }
        }

        // Finder patterns + 1-module white separator border
        fun drawFinder(topRow: Int, leftCol: Int) {
            for (dr in -1..7) {
                for (dc in -1..7) {
                    val r = topRow + dr
                    val c = leftCol + dc
                    val isDark = if (dr in 0..6 && dc in 0..6) {
                        val dist = maxOf(abs(dr - 3), abs(dc - 3))
                        dist != 2
                    } else {
                        false
                    }
                    setFn(r, c, isDark)
                }
            }
        }
        drawFinder(0, 0)
        drawFinder(0, size - 7)
        drawFinder(size - 7, 0)

        // Timing patterns
        for (i in 8 until size - 8) {
            val dark = (i % 2 == 0)
            setFn(6, i, dark)
            setFn(i, 6, dark)
        }

        // Alignment patterns
        val centers = spec.alignmentCenters
        for (r in centers) {
            for (c in centers) {
                val overlapsFinder =
                    (r <= 8 && c <= 8) ||
                        (r <= 8 && c >= size - 9) ||
                        (r >= size - 9 && c <= 8)
                if (overlapsFinder) continue
                for (dr in -2..2) {
                    for (dc in -2..2) {
                        val dist = maxOf(abs(dr), abs(dc))
                        setFn(r + dr, c + dc, dist != 1)
                    }
                }
            }
        }

        // Format info placeholders + mandatory dark module
        for (i in 0..8) {
            setFn(8, i, false)
            setFn(i, 8, false)
        }
        for (i in size - 8 until size) {
            setFn(8, i, false)
            if (i >= size - 7) {
                setFn(i, 8, false)
            }
        }
        // Re-assert timing intersections at (6, 8) and (8, 6)
        setFn(6, 8, true)
        setFn(8, 6, true)
        setFn(size - 8, 8, true)

        // Version info (Versions 7+)
        if (spec.version >= 7) {
            var rem = spec.version shl 12
            for (bit in 17 downTo 12) {
                if (((rem ushr bit) and 1) != 0) {
                    rem = rem xor (0x1F25 shl (bit - 12))
                }
            }
            val bits = (spec.version shl 12) or rem
            for (k in 0 until 18) {
                val dark = ((bits ushr k) and 1) != 0
                val a = k / 3
                val b = size - 11 + (k % 3)
                setFn(a, b, dark)
                setFn(b, a, dark)
            }
        }
    }

    private fun placeCodewords(
        codewords: ByteArray,
        size: Int,
        modules: BooleanArray,
        isFunction: BooleanArray,
    ) {
        val totalBits = codewords.size * 8
        var bitIdx = 0
        var upward = true
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (step in 0 until size) {
                val row = if (upward) size - 1 - step else step
                for (dx in 0..1) {
                    val col = right - dx
                    val cell = row * size + col
                    if (!isFunction[cell]) {
                        val dark = if (bitIdx < totalBits) {
                            val byteVal = codewords[bitIdx ushr 3].toInt() and 0xFF
                            ((byteVal ushr (7 - (bitIdx and 7))) and 1) != 0
                        } else {
                            false
                        }
                        modules[cell] = dark
                        bitIdx += 1
                    }
                }
            }
            upward = !upward
            right -= 2
        }
    }

    private fun applyMask(
        mask: Int,
        size: Int,
        modules: BooleanArray,
        isFunction: BooleanArray,
    ) {
        for (r in 0 until size) {
            for (c in 0 until size) {
                val idx = r * size + c
                if (isFunction[idx]) continue
                val invert = when (mask) {
                    0 -> (r + c) % 2 == 0
                    1 -> r % 2 == 0
                    2 -> c % 3 == 0
                    3 -> (r + c) % 3 == 0
                    4 -> ((r / 2) + (c / 3)) % 2 == 0
                    5 -> (r * c) % 2 + (r * c) % 3 == 0
                    6 -> (((r * c) % 2) + ((r * c) % 3)) % 2 == 0
                    7 -> (((r + c) % 2) + ((r * c) % 3)) % 2 == 0
                    else -> false
                }
                if (invert) {
                    modules[idx] = !modules[idx]
                }
            }
        }
    }

    private fun drawFormatBits(
        mask: Int,
        size: Int,
        modules: BooleanArray,
        isFunction: BooleanArray,
    ) {
        // EC Level M = 00
        val data = mask and 0x07
        var rem = data shl 10
        for (bit in 14 downTo 10) {
            if (((rem ushr bit) and 1) != 0) {
                rem = rem xor (0x537 shl (bit - 10))
            }
        }
        val bits = ((data shl 10) or rem) xor 0x5412

        fun setBit(r: Int, c: Int, bitIndex: Int) {
            val idx = r * size + c
            modules[idx] = ((bits ushr bitIndex) and 1) != 0
            isFunction[idx] = true
        }

        // Copy 1: top-left around finder
        for (i in 0..5) setBit(8, i, 14 - i)
        setBit(8, 7, 8)
        setBit(8, 8, 7)
        setBit(7, 8, 6)
        for (i in 5 downTo 0) setBit(i, 8, i)

        // Copy 2: bottom-left and top-right
        for (i in 0..6) setBit(size - 1 - i, 8, 14 - i)
        for (i in 0..7) setBit(8, size - 8 + i, 7 - i)
    }

    private fun computePenaltyScore(size: Int, modules: BooleanArray): Int {
        var score = 0

        // N1 + N3 on rows
        for (r in 0 until size) {
            var runColor = modules[r * size]
            var runLen = 1
            for (c in 1 until size) {
                val dark = modules[r * size + c]
                if (dark == runColor) {
                    runLen += 1
                    if (runLen == 5) score += 3
                    else if (runLen > 5) score += 1
                } else {
                    runColor = dark
                    runLen = 1
                }
            }
            for (c in 0..size - 11) {
                if (matchesFinderLikePattern(size, modules, r, c, horizontal = true)) {
                    score += 40
                }
            }
        }

        // N1 + N3 on columns
        for (c in 0 until size) {
            var runColor = modules[c]
            var runLen = 1
            for (r in 1 until size) {
                val dark = modules[r * size + c]
                if (dark == runColor) {
                    runLen += 1
                    if (runLen == 5) score += 3
                    else if (runLen > 5) score += 1
                } else {
                    runColor = dark
                    runLen = 1
                }
            }
            for (r in 0..size - 11) {
                if (matchesFinderLikePattern(size, modules, r, c, horizontal = false)) {
                    score += 40
                }
            }
        }

        // N2: 2x2 blocks
        for (r in 0 until size - 1) {
            for (c in 0 until size - 1) {
                val color = modules[r * size + c]
                if (modules[r * size + c + 1] == color &&
                    modules[(r + 1) * size + c] == color &&
                    modules[(r + 1) * size + c + 1] == color
                ) {
                    score += 3
                }
            }
        }

        // N4: dark module balance around 50%
        var darkCount = 0
        for (m in modules) {
            if (m) darkCount += 1
        }
        val total = size * size
        val percentDiff = abs(darkCount * 100.0 / total - 50.0)
        val fivePercentSteps = floor(percentDiff / 5.0).toInt()
        score += fivePercentSteps * 10

        return score
    }

    private fun matchesFinderLikePattern(
        size: Int,
        modules: BooleanArray,
        startR: Int,
        startC: Int,
        horizontal: Boolean,
    ): Boolean {
        fun bit(offset: Int): Boolean = if (horizontal) {
            modules[startR * size + startC + offset]
        } else {
            modules[(startR + offset) * size + startC]
        }
        // Check 10111010000 or 00001011101
        val matchLeading = bit(0) && !bit(1) && bit(2) && bit(3) && bit(4) && !bit(5) &&
            bit(6) && !bit(7) && !bit(8) && !bit(9) && !bit(10)
        if (matchLeading) return true
        return !bit(0) && !bit(1) && !bit(2) && !bit(3) && bit(4) && !bit(5) &&
            bit(6) && bit(7) && bit(8) && !bit(9) && bit(10)
    }
}

@Composable
fun QrCodeCanvas(
    matrix: QrCodeMatrix,
    modifier: Modifier = Modifier,
) {
    val quietModules = 4
    val totalModules = matrix.size + quietModules * 2

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(Color.White, RoundedCornerShape(16.dp))
            .padding(12.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasSide = floor(minOf(size.width, size.height))
            val cellSize = floor(canvasSide / totalModules.toFloat()).coerceAtLeast(1f)
            val drawnSide = cellSize * totalModules
            val originX = floor((size.width - drawnSide) / 2f)
            val originY = floor((size.height - drawnSide) / 2f)

            drawRect(
                color = Color.White,
                topLeft = Offset(originX, originY),
                size = Size(drawnSide, drawnSide),
            )

            for (r in 0 until matrix.size) {
                for (c in 0 until matrix.size) {
                    if (matrix.get(r, c)) {
                        drawRect(
                            color = Color.Black,
                            topLeft = Offset(
                                originX + (c + quietModules) * cellSize,
                                originY + (r + quietModules) * cellSize,
                            ),
                            size = Size(cellSize, cellSize),
                        )
                    }
                }
            }
        }
    }
}
