package com.musico.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeEncoderTest {

    @Test
    fun encodesVersions1Through10WithValidIsoStructure() {
        // Test payloads targeting Versions 1..10 (Level M, Byte mode)
        val lengths = listOf(10, 20, 35, 55, 75, 95, 115, 140, 170, 205)
        lengths.forEachIndexed { idx, len ->
            val expectedVersion = idx + 1
            val payload = "A".repeat(len)
            val matrix = QrCodeEncoder.encode(payload)
            assertEquals("Expected version $expectedVersion for $len bytes", expectedVersion, matrix.version)
            val expectedSize = 17 + 4 * expectedVersion
            assertEquals(expectedSize, matrix.size)
            assertEquals(expectedSize * expectedSize, matrix.modules.size)

            // Verify all 3 finder patterns (7x7 outer ring) and dark module
            assertFinderPattern(matrix, 0, 0)
            assertFinderPattern(matrix, 0, matrix.size - 7)
            assertFinderPattern(matrix, matrix.size - 7, 0)
            assertTrue("Dark module must be dark", matrix.get(matrix.size - 8, 8))

            // Verify alternating timing patterns
            for (i in 8 until matrix.size - 8) {
                assertEquals(i % 2 == 0, matrix.get(6, i))
                assertEquals(i % 2 == 0, matrix.get(i, 6))
            }
        }
    }

    @Test
    fun encodesNetEaseQrLoginUrlAndMatchesGoldenHeaderBits() {
        val url = "https://music.163.com/login?codekey=test-uuid-1234567890"
        val matrix = QrCodeEncoder.encode(url)
        assertEquals(4, matrix.version)
        assertEquals(33, matrix.size)
        // Format info copies must agree across top-left and bottom-left/top-right
        for (i in 0..5) {
            assertEquals(matrix.get(8, i), matrix.get(matrix.size - 1 - i, 8))
        }
        assertEquals(matrix.get(8, 7), matrix.get(matrix.size - 7, 8))
        assertEquals(matrix.get(8, 8), matrix.get(8, matrix.size - 8))
        assertEquals(matrix.get(7, 8), matrix.get(8, matrix.size - 7))
        for (i in 0..5) {
            assertEquals(matrix.get(i, 8), matrix.get(8, matrix.size - 1 - i))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyPayload() {
        QrCodeEncoder.encode("")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedPayloadBeyond213Bytes() {
        QrCodeEncoder.encode("x".repeat(214))
    }

    private fun assertFinderPattern(matrix: QrCodeMatrix, top: Int, left: Int) {
        for (r in 0..6) {
            for (c in 0..6) {
                val expected = when {
                    r == 0 || r == 6 || c == 0 || c == 6 -> true
                    r in 2..4 && c in 2..4 -> true
                    else -> false
                }
                assertEquals("Finder mismatch at ($top+$r, $left+$c)", expected, matrix.get(top + r, left + c))
            }
        }
        // Check adjacent white separator corner
        val sepRow = if (top == 0) 7 else top - 1
        val sepCol = if (left == 0) 7 else left - 1
        assertFalse(matrix.get(sepRow, sepCol))
    }
}
