package com.responsly.sdk.internal.ui

import com.responsly.sdk.PresentationStyle
import com.responsly.sdk.internal.config.PresentationSize
import org.junit.Assert.assertEquals
import org.junit.Test

/** Section 6 on a 400 x 800 dp phone at density 1 unless a test says otherwise. */
class SheetGeometryTest {
    private fun frame(
        style: PresentationStyle,
        size: PresentationSize = PresentationSize.MEDIUM,
        width: Int = 400,
        height: Int = 800,
        density: Float = 1f,
        insets: EdgeInsets = EdgeInsets.NONE,
        keyboard: Int = 0,
        requested: Int = 0,
    ) = SheetGeometry.compute(style, size, width, height, density, insets, keyboard, requested)

    @Test
    fun sheetHeightFollowsTheSize() {
        assertEquals(360, frame(PresentationStyle.BOTTOM_SHEET, PresentationSize.SMALL).height)
        assertEquals(520, frame(PresentationStyle.BOTTOM_SHEET, PresentationSize.MEDIUM).height)
        assertEquals(704, frame(PresentationStyle.BOTTOM_SHEET, PresentationSize.LARGE).height)
    }

    @Test
    fun sheetIsAttachedToTheBottomFullWidthWithRoundedTopCorners() {
        val sheet = frame(PresentationStyle.BOTTOM_SHEET)
        assertEquals(0, sheet.left)
        assertEquals(400, sheet.width)
        assertEquals(800, sheet.top + sheet.height)
        assertEquals(16, sheet.topRadius)
        assertEquals(0, sheet.bottomRadius)
    }

    @Test
    fun sheetIsNeverLowerThan320() {
        // 45% of 600 is 270.
        assertEquals(320, frame(PresentationStyle.BOTTOM_SHEET, PresentationSize.SMALL, height = 600).height)
        // Lengths are in dp: at density 3 the floor is 960 px.
        assertEquals(960, frame(PresentationStyle.BOTTOM_SHEET, PresentationSize.SMALL, width = 1200, height = 1800, density = 3f).height)
    }

    @Test
    fun sheetIsCentredAndCappedAt640OnTablets() {
        val sheet = frame(PresentationStyle.BOTTOM_SHEET, width = 1000, height = 1200)
        assertEquals(640, sheet.width)
        assertEquals(180, sheet.left)
    }

    @Test
    fun sheetGrowsToWhatThePageNeedsUpToEightyEightPercent() {
        assertEquals(520, frame(PresentationStyle.BOTTOM_SHEET, requested = 300).height)
        assertEquals(600, frame(PresentationStyle.BOTTOM_SHEET, requested = 600).height)
        assertEquals(704, frame(PresentationStyle.BOTTOM_SHEET, requested = 5_000).height)
        assertEquals(704, frame(PresentationStyle.BOTTOM_SHEET, PresentationSize.LARGE, requested = 5_000).height)
    }

    @Test
    fun sheetCoversTheNavigationBarAndKeepsThePageAboveIt() {
        val insets = EdgeInsets(0, 24, 0, 48)
        val sheet = frame(PresentationStyle.BOTTOM_SHEET, insets = insets)
        assertEquals(520, sheet.height)
        assertEquals(280, sheet.top)
        assertEquals(EdgeInsets(0, 0, 0, 48), sheet.padding)
        assertEquals(472, sheet.contentHeight)
        // The page asks for content height; the strip under the navigation bar comes on top.
        assertEquals(648, frame(PresentationStyle.BOTTOM_SHEET, insets = insets, requested = 600).height)
    }

    @Test
    fun sheetStaysAboveTheKeyboard() {
        val insets = EdgeInsets(0, 24, 0, 48)
        val open = frame(PresentationStyle.BOTTOM_SHEET, insets = insets, keyboard = 300)
        // The page keeps its 472 px: there is room for them above the keyboard.
        assertEquals(472, open.contentHeight)
        assertEquals(300, open.padding.bottom)
        assertEquals(800 - 300 - 472, open.top)

        // A large sheet has to shrink: only 800 - 24 - 300 = 476 px are left.
        val large = frame(PresentationStyle.BOTTOM_SHEET, PresentationSize.LARGE, insets = insets, keyboard = 300)
        assertEquals(476, large.contentHeight)
        assertEquals(24, large.top)
    }

    @Test
    fun sheetRespectsSideInsetsInLandscape() {
        val sheet = frame(PresentationStyle.BOTTOM_SHEET, width = 800, height = 400, insets = EdgeInsets(40, 0, 60, 0))
        assertEquals(640, sheet.width)
        assertEquals(40 + (700 - 640) / 2, sheet.left)
        // 320 is the floor even though 65% of 400 is 260.
        assertEquals(320, sheet.height)
    }

    @Test
    fun dialogHeightFollowsTheSize() {
        assertEquals(320, frame(PresentationStyle.DIALOG, PresentationSize.SMALL).height)
        assertEquals(448, frame(PresentationStyle.DIALOG, PresentationSize.MEDIUM).height)
        assertEquals(640, frame(PresentationStyle.DIALOG, PresentationSize.LARGE).height)
    }

    @Test
    fun dialogIsACentredCard() {
        val dialog = frame(PresentationStyle.DIALOG)
        assertEquals(368, dialog.width)
        assertEquals(16, dialog.left)
        assertEquals((800 - 448) / 2, dialog.top)
        assertEquals(16, dialog.topRadius)
        assertEquals(16, dialog.bottomRadius)
        assertEquals(EdgeInsets.NONE, dialog.padding)

        val tablet = frame(PresentationStyle.DIALOG, width = 1000, height = 1200)
        assertEquals(480, tablet.width)
        assertEquals(260, tablet.left)
    }

    @Test
    fun dialogGrowsUpToEightyPercent() {
        assertEquals(448, frame(PresentationStyle.DIALOG, requested = 200).height)
        assertEquals(500, frame(PresentationStyle.DIALOG, requested = 500).height)
        assertEquals(640, frame(PresentationStyle.DIALOG, requested = 5_000).height)
    }

    @Test
    fun dialogIsCentredAboveTheKeyboard() {
        val dialog = frame(PresentationStyle.DIALOG, insets = EdgeInsets(0, 24, 0, 48), keyboard = 300)
        // Between y = 40 (status bar + margin) and y = 484 (keyboard - margin) there are 444 px.
        assertEquals(444, dialog.height)
        assertEquals(40, dialog.top)

        val small = frame(PresentationStyle.DIALOG, PresentationSize.SMALL, insets = EdgeInsets(0, 24, 0, 48), keyboard = 300)
        assertEquals(320, small.height)
        assertEquals(40 + (444 - 320) / 2, small.top)
    }

    @Test
    fun fullscreenCoversTheWindowAndIgnoresSizeAndResize() {
        val insets = EdgeInsets(0, 24, 0, 48)
        for (size in PresentationSize.values()) {
            val full = frame(PresentationStyle.FULLSCREEN, size, insets = insets, requested = 100)
            assertEquals(0, full.left)
            assertEquals(0, full.top)
            assertEquals(400, full.width)
            assertEquals(800, full.height)
            assertEquals(insets, full.padding)
            assertEquals(0, full.topRadius)
        }
        assertEquals(300, frame(PresentationStyle.FULLSCREEN, insets = insets, keyboard = 300).padding.bottom)
    }
}
