package com.readicop.lettor

import com.readicop.lettor.ui.reader.EpubScreenTapZone
import com.readicop.lettor.ui.reader.EpubTapOutcome
import com.readicop.lettor.ui.reader.resolveChapterAndSubPageForGlobalPage
import com.readicop.lettor.ui.reader.resolveEpubScreenTapZone
import com.readicop.lettor.ui.reader.resolveEpubTapOutcome
import com.readicop.lettor.ui.reader.resolveGlobalPageFromChapterCounts
import org.junit.Assert.assertEquals
import org.junit.Test

class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun epubVerticalCenterRectangleExcludesTopAndBottomMargins() {
    val width = 1000f
    val height = 2000f
    val halfWidth = 150f // X in 350..650, Y in 320..1680 (16%..84%)

    assertEquals(EpubScreenTapZone.CENTER_RECT, resolveEpubScreenTapZone(500f, 1000f, width, height, halfWidth))
    assertEquals(EpubScreenTapZone.CENTER_RECT, resolveEpubScreenTapZone(600f, 450f, width, height, halfWidth))
    assertEquals(EpubScreenTapZone.CENTER_RECT, resolveEpubScreenTapZone(420f, 1550f, width, height, halfWidth))

    // Top & bottom margins along the center strip fall outside the rectangle
    assertEquals(EpubScreenTapZone.PREVIOUS_PAGE, resolveEpubScreenTapZone(480f, 200f, width, height, halfWidth))
    assertEquals(EpubScreenTapZone.NEXT_PAGE, resolveEpubScreenTapZone(520f, 1850f, width, height, halfWidth))

    // Left & right sides outside the rectangle
    assertEquals(EpubScreenTapZone.PREVIOUS_PAGE, resolveEpubScreenTapZone(120f, 1000f, width, height, halfWidth))
    assertEquals(EpubScreenTapZone.NEXT_PAGE, resolveEpubScreenTapZone(880f, 1000f, width, height, halfWidth))
  }

  @Test
  fun epubTapOutcomeDismissesControlsOutsideCenterRect() {
    assertEquals(
      EpubTapOutcome.DISMISS_CONTROLS,
      resolveEpubTapOutcome(EpubScreenTapZone.PREVIOUS_PAGE, controlsVisible = true)
    )
    assertEquals(
      EpubTapOutcome.DISMISS_CONTROLS,
      resolveEpubTapOutcome(EpubScreenTapZone.NEXT_PAGE, controlsVisible = true)
    )
    assertEquals(
      EpubTapOutcome.TOGGLE_CONTROLS,
      resolveEpubTapOutcome(EpubScreenTapZone.CENTER_RECT, controlsVisible = true)
    )
    assertEquals(
      EpubTapOutcome.NEXT_PAGE,
      resolveEpubTapOutcome(EpubScreenTapZone.NEXT_PAGE, controlsVisible = false)
    )
  }

  @Test
  fun globalEpubPageCountersRoundTrip() {
    val counts = listOf(1, 4, 3, 5)
    var expected = 0
    for (chIdx in counts.indices) {
      for (sub in 0 until counts[chIdx]) {
        assertEquals(expected, resolveGlobalPageFromChapterCounts(counts, chIdx, sub))
        assertEquals(chIdx to sub, resolveChapterAndSubPageForGlobalPage(counts, expected))
        expected++
      }
    }
    assertEquals(3 to 4, resolveChapterAndSubPageForGlobalPage(counts, 999))
    assertEquals(0 to 0, resolveChapterAndSubPageForGlobalPage(counts, -3))
  }
}
