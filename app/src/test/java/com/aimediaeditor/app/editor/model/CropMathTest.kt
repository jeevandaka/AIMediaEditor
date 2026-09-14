package com.aimediaeditor.app.editor.model

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs against the REAL production CropMath functions. CropMath.kt's own doc comment
 * makes the same claim ProjectSanitizer's did: the arithmetic was "checked against
 * several source/target aspect ratio pairs... with a throwaway Python port before
 * being written into this uncompiled file" -- never actually executed as Kotlin
 * before this file existed. These cases were first proven to pass for real against a
 * faithful copy of this exact logic in a standalone Kotlin/JVM script, then ported
 * here as permanent, `./gradlew test`-runnable regression coverage.
 */
class CropMathTest {

    private val epsilon = 0.0001f

    @Test
    fun `AspectRatio ratio maps all four enum values correctly`() {
        assertEquals(9f / 16f, AspectRatio.RATIO_9_16.ratio, epsilon)
        assertEquals(16f / 9f, AspectRatio.RATIO_16_9.ratio, epsilon)
        assertEquals(1f, AspectRatio.RATIO_1_1.ratio, epsilon)
        assertEquals(4f / 5f, AspectRatio.RATIO_4_5.ratio, epsilon)
    }

    @Test
    fun `computeCropWindow returns full frame when source equals target aspect ratio`() {
        val window = computeCropWindow(16f / 9f, 16f / 9f, FocalPoint(0.5f, 0.5f))
        assertEquals(CropWindow(0f, 0f, 1f, 1f), window)
    }

    @Test
    fun `computeCropWindow crops width and keeps full height when target is narrower than source`() {
        val source = 16f / 9f
        val target = 9f / 16f
        val window = computeCropWindow(source, target, FocalPoint(0.5f, 0.5f))
        assertEquals(1f, window.heightFraction, epsilon)
        assertEquals(target / source, window.widthFraction, epsilon)
        assertEquals(0f, window.offsetY, epsilon)
        val expectedCenteredOffsetX = (1f - window.widthFraction) / 2f
        assertEquals(expectedCenteredOffsetX, window.offsetX, epsilon)
    }

    @Test
    fun `computeCropWindow crops height and keeps full width when target is wider than source`() {
        val source = 9f / 16f
        val target = 16f / 9f
        val window = computeCropWindow(source, target, FocalPoint(0.5f, 0.5f))
        assertEquals(1f, window.widthFraction, epsilon)
        assertEquals(source / target, window.heightFraction, epsilon)
        assertEquals(0f, window.offsetX, epsilon)
    }

    @Test
    fun `computeCropWindow clamps the crop offset within bounds for edge-pinned focal points`() {
        val source = 16f / 9f
        val target = 9f / 16f

        val leftPinned = computeCropWindow(source, target, FocalPoint(0f, 0.5f))
        assertTrue(leftPinned.offsetX >= 0f)
        assertEquals(0f, leftPinned.offsetX, epsilon)

        val rightPinned = computeCropWindow(source, target, FocalPoint(1f, 0.5f))
        val maxOffsetX = 1f - rightPinned.widthFraction
        assertTrue(rightPinned.offsetX <= maxOffsetX + epsilon)
        assertEquals(maxOffsetX, rightPinned.offsetX, epsilon)
    }

    @Test
    fun `computeCropWindow returns a safe full frame for degenerate aspect ratios`() {
        assertEquals(CropWindow(0f, 0f, 1f, 1f), computeCropWindow(0f, 16f / 9f, FocalPoint(0.5f, 0.5f)))
        assertEquals(CropWindow(0f, 0f, 1f, 1f), computeCropWindow(16f / 9f, -1f, FocalPoint(0.5f, 0.5f)))
    }

    @Test
    fun `toNdcCrop identity crop matches Media3's documented default exactly`() {
        val ndc = CropWindow(0f, 0f, 1f, 1f).toNdcCrop()
        assertEquals(NdcCrop(-1f, 1f, -1f, 1f), ndc)
        assertTrue(ndc.isFullFrame)
    }

    @Test
    fun `toNdcCrop invariants hold across multiple source, target, and focal point combinations`() {
        val cases = listOf(
            Triple(16f / 9f, 9f / 16f, FocalPoint(0.5f, 0.5f)),
            Triple(9f / 16f, 16f / 9f, FocalPoint(0.5f, 0.5f)),
            Triple(16f / 9f, 1f, FocalPoint(0f, 0f)),
            Triple(16f / 9f, 1f, FocalPoint(1f, 1f)),
            Triple(4f / 3f, 4f / 5f, FocalPoint(0.2f, 0.8f))
        )
        for ((source, target, focal) in cases) {
            val window = computeCropWindow(source, target, focal)
            val ndc = window.toNdcCrop()
            assertTrue("left should be < right for source=$source target=$target focal=$focal: $ndc", ndc.left < ndc.right)
            assertTrue("bottom should be < top for source=$source target=$target focal=$focal: $ndc", ndc.bottom < ndc.top)
            val ndcWidth = ndc.right - ndc.left
            val ndcHeight = ndc.top - ndc.bottom
            // NDC fractions scale by the source frame's own aspect ratio to recover the
            // cropped region's actual pixel aspect ratio.
            val resultingAspect = (ndcWidth / ndcHeight) * source
            assertTrue(
                "resulting cropped aspect ratio $resultingAspect doesn't match target $target for source=$source focal=$focal",
                abs(resultingAspect - target) < 0.01f
            )
        }
    }
}
