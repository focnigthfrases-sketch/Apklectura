package com.readicop.lettor

import androidx.compose.ui.text.AnnotatedString
import com.readicop.lettor.domain.engine.EpubBlock
import com.readicop.lettor.ui.reader.EpubCharPosition
import com.readicop.lettor.ui.reader.EpubScreenTapZone
import com.readicop.lettor.ui.reader.EpubTapOutcome
import com.readicop.lettor.ui.reader.EpubTextSelection
import com.readicop.lettor.ui.reader.extractSelectedEpubText
import com.readicop.lettor.ui.reader.resolveChapterAndSubPageForGlobalPage
import com.readicop.lettor.ui.reader.resolveEpubScreenTapZone
import com.readicop.lettor.ui.reader.resolveEpubTapOutcome
import com.readicop.lettor.ui.reader.resolveGlobalPageFromChapterCounts
import com.readicop.lettor.ui.reader.snapEpubSelectionToWord
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

  @Test
  fun epubSnapToWordReducesMultiWordOrParagraphSelectionToSingleWord() {
    val p1 = "Hola mundo literario desde Lettor"
    val p2 = "Segundo párrafo de prueba"
    val blocks = listOf(
      EpubBlock.Paragraph(content = AnnotatedString(p1), plainText = p1),
      EpubBlock.Paragraph(content = AnnotatedString(p2), plainText = p2)
    )
    // Partial/multi-word selection starting inside "mundo" and spanning across block 1
    val multiSelection = EpubTextSelection(
      chapterIndex = 0,
      anchorA = EpubCharPosition(blockIndex = 0, charOffset = 6),
      anchorB = EpubCharPosition(blockIndex = 1, charOffset = 7)
    )
    val snapped = snapEpubSelectionToWord(
      blocks = blocks,
      selection = multiSelection,
      fallbackBlockIndex = 0,
      chapterIndex = 0
    )!!
    assertEquals("mundo", extractSelectedEpubText(blocks, snapped))

    // Leading whitespace inside selection skips forward to the next word
    val whitespaceSelection = EpubTextSelection(
      chapterIndex = 0,
      anchorA = EpubCharPosition(blockIndex = 0, charOffset = 4),
      anchorB = EpubCharPosition(blockIndex = 0, charOffset = 18)
    )
    val snappedAfterSpace = snapEpubSelectionToWord(
      blocks = blocks,
      selection = whitespaceSelection,
      fallbackBlockIndex = 0,
      chapterIndex = 0
    )!!
    assertEquals("mundo", extractSelectedEpubText(blocks, snappedAfterSpace))
  }
}
