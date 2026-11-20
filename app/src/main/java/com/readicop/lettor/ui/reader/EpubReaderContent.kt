package com.readicop.lettor.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readicop.lettor.data.local.HighlightEntity
import com.readicop.lettor.data.preferences.ReaderFont
import com.readicop.lettor.data.preferences.ReaderPreferences
import com.readicop.lettor.domain.engine.EpubBlock
import com.readicop.lettor.domain.engine.EpubChapter
import com.readicop.lettor.domain.engine.EpubChapterStub
import com.readicop.lettor.domain.engine.EpubMetadata
import com.readicop.lettor.ui.theme.JetBrainsMonoFamily
import com.readicop.lettor.ui.theme.PlayfairDisplayFamily
import com.readicop.lettor.ui.theme.ReaderSurfaceColors
import com.readicop.lettor.ui.theme.fontFamily
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal enum class EpubBoundaryAction {
  NONE,
  NEXT_CHAPTER,
  PREVIOUS_CHAPTER
}

internal fun resolveEpubBoundarySwipe(
  startedOnFirstPage: Boolean,
  startedOnLastPage: Boolean,
  chapterIndex: Int,
  totalChapters: Int,
  dx: Float,
  dy: Float,
  swipeThresholdPx: Float
): EpubBoundaryAction {
  if (abs(dx) < swipeThresholdPx) return EpubBoundaryAction.NONE
  if (abs(dx) <= abs(dy) * 1.35f) return EpubBoundaryAction.NONE
  return when {
    dx < -swipeThresholdPx && startedOnLastPage && chapterIndex < totalChapters - 1 ->
      EpubBoundaryAction.NEXT_CHAPTER
    dx > swipeThresholdPx && startedOnFirstPage && chapterIndex > 0 ->
      EpubBoundaryAction.PREVIOUS_CHAPTER
    else -> EpubBoundaryAction.NONE
  }
}

internal fun resolveEpubVerticalBoundaryScroll(
  startedAtTop: Boolean,
  startedAtBottom: Boolean,
  stillAtTop: Boolean,
  stillAtBottom: Boolean,
  chapterIndex: Int,
  totalChapters: Int,
  dx: Float,
  dy: Float,
  swipeThresholdPx: Float
): EpubBoundaryAction {
  if (abs(dy) < swipeThresholdPx) return EpubBoundaryAction.NONE
  if (abs(dy) <= abs(dx) * 1.25f) return EpubBoundaryAction.NONE
  return when {
    dy < -swipeThresholdPx && startedAtBottom && stillAtBottom && chapterIndex < totalChapters - 1 ->
      EpubBoundaryAction.NEXT_CHAPTER
    dy > swipeThresholdPx && startedAtTop && stillAtTop && chapterIndex > 0 ->
      EpubBoundaryAction.PREVIOUS_CHAPTER
    else -> EpubBoundaryAction.NONE
  }
}

internal data class EpubPaginationLayoutKey(
  val availableWidthPx: Int,
  val availableHeightPx: Int,
  val fontSizeSp: Float,
  val lineHeightMultiplier: Float,
  val readerFont: ReaderFont,
  val horizontalPaddingDp: Int
)

internal class EpubPagesCache<T> {
  private var activeLayoutKey: EpubPaginationLayoutKey? = null
  private val pagesByChapter = mutableMapOf<Int, List<T>>()

  val cachedChapterCount: Int
    get() = pagesByChapter.size

  fun getOrCompute(
    chapterIndex: Int,
    layoutKey: EpubPaginationLayoutKey,
    compute: () -> List<T>
  ): List<T> {
    if (activeLayoutKey != layoutKey) {
      activeLayoutKey = layoutKey
      pagesByChapter.clear()
    }
    return pagesByChapter.getOrPut(chapterIndex, compute)
  }
}

data class EpubVerticalLayoutSignature(
  val fontSizeSp: Float,
  val lineHeightMultiplier: Float,
  val readerFont: ReaderFont,
  val horizontalPaddingDp: Int
)

data class EpubVerticalScrollPosition(
  val chapterId: String,
  val chapterIndex: Int,
  val blockIndex: Int = 0,
  val scrollOffsetPx: Int = 0,
  val blockScrollFraction: Float = 0f,
  val characterOffsetInBlock: Int = 0,
  val layoutSignature: EpubVerticalLayoutSignature? = null
)

internal fun epubChapterUniqueId(chapter: EpubChapter): String =
  chapter.zipPath.ifBlank { "chapter_${chapter.index}" }

internal fun resolveVerticalScrollRestoreTarget(
  saved: EpubVerticalScrollPosition?,
  blockCount: Int,
  currentLayout: EpubVerticalLayoutSignature,
  newItemHeightPx: Int? = null,
  newLineTopPx: Int? = null
): Pair<Int, Int> {
  if (saved == null || blockCount <= 0) return 0 to 0
  val targetBlock = saved.blockIndex.coerceIn(0, blockCount - 1)
  if (saved.layoutSignature == null || saved.layoutSignature == currentLayout) {
    return targetBlock to saved.scrollOffsetPx.coerceAtLeast(0)
  }
  if (newLineTopPx != null && newLineTopPx >= 0) {
    return targetBlock to newLineTopPx
  }
  if (newItemHeightPx != null && newItemHeightPx > 0) {
    val maxScroll = (newItemHeightPx - 1).coerceAtLeast(0)
    val proportionalOffset = (saved.blockScrollFraction.coerceIn(0f, 1f) * newItemHeightPx.toFloat())
      .roundToInt()
      .coerceIn(0, maxScroll)
    return targetBlock to proportionalOffset
  }
  return targetBlock to 0
}

private fun String.hasReadableCoverGapText(): Boolean =
  any { !it.isWhitespace() && it != '\u00A0' && it != '\u200B' }

internal fun findVerticalEpubCoverImageBlock(
  chapter: EpubChapter,
  metadata: EpubMetadata
): EpubBlock.Image? {
  val imageBlocks = chapter.blocks.filterIsInstance<EpubBlock.Image>()
  if (imageBlocks.size != 1) return null
  val hasVisibleText = chapter.blocks.any { block ->
    when (block) {
      is EpubBlock.Heading -> block.text.hasReadableCoverGapText()
      is EpubBlock.Paragraph -> block.plainText.hasReadableCoverGapText()
      is EpubBlock.Quote -> block.plainText.hasReadableCoverGapText()
      is EpubBlock.Image -> false
    }
  }
  if (hasVisibleText) return null

  val singleImage = imageBlocks.first()
  val matchesCoverMeta = !metadata.coverZipPath.isNullOrBlank() &&
    singleImage.zipEntryPath.equals(metadata.coverZipPath, ignoreCase = true)
  val isCoverChapter = chapter.index == 0 ||
    chapter.title.contains("cover", ignoreCase = true) ||
    chapter.title.contains("portada", ignoreCase = true) ||
    chapter.title.contains("cubierta", ignoreCase = true) ||
    chapter.zipPath.contains("cover", ignoreCase = true) ||
    chapter.zipPath.contains("portada", ignoreCase = true) ||
    singleImage.zipEntryPath.contains("cover", ignoreCase = true) ||
    singleImage.zipEntryPath.contains("portada", ignoreCase = true)

  return if (matchesCoverMeta || isCoverChapter) singleImage else null
}

internal fun computeFittedCoverDimensions(
  imageWidthPx: Int,
  imageHeightPx: Int,
  maxWidthDp: Float,
  maxHeightDp: Float
): Pair<Float, Float> {
  val safeMaxW = maxWidthDp.coerceAtLeast(1f)
  val safeMaxH = maxHeightDp.coerceAtLeast(1f)
  if (imageWidthPx <= 0 || imageHeightPx <= 0) {
    val fallbackAspect = 0.67f
    val fallbackH = safeMaxW / fallbackAspect
    return if (fallbackH <= safeMaxH) {
      safeMaxW to fallbackH
    } else {
      (safeMaxH * fallbackAspect) to safeMaxH
    }
  }
  val aspect = imageWidthPx.toFloat() / imageHeightPx.toFloat()
  val widthBoundHeight = safeMaxW / aspect
  return if (widthBoundHeight <= safeMaxH) {
    safeMaxW to widthBoundHeight
  } else {
    (safeMaxH * aspect) to safeMaxH
  }
}

data class EpubCharPosition(
  val blockIndex: Int,
  val charOffset: Int
) : Comparable<EpubCharPosition> {
  override fun compareTo(other: EpubCharPosition): Int {
    val cmp = blockIndex.compareTo(other.blockIndex)
    return if (cmp != 0) cmp else charOffset.compareTo(other.charOffset)
  }
}

data class EpubTextSelection(
  val chapterIndex: Int,
  val anchorA: EpubCharPosition,
  val anchorB: EpubCharPosition
) {
  val start: EpubCharPosition get() = if (anchorA <= anchorB) anchorA else anchorB
  val end: EpubCharPosition get() = if (anchorA <= anchorB) anchorB else anchorA
}

fun expandWordRangeAtOffset(text: String, rawOffset: Int): IntRange {
  if (text.isEmpty()) return 0..0
  val idx = rawOffset.coerceIn(0, text.lastIndex)
  fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-'

  return if (isWordChar(text[idx])) {
    var start = idx
    while (start > 0 && isWordChar(text[start - 1])) start--
    var end = idx + 1
    while (end < text.length && isWordChar(text[end])) end++
    start until end
  } else {
    val start = idx
    val end = (idx + 1).coerceAtMost(text.length)
    start until end
  }
}

fun extractSelectedEpubText(blocks: List<EpubBlock>, selection: EpubTextSelection): String {
  val s = selection.start
  val e = selection.end
  if (s >= e) return ""

  val parts = mutableListOf<String>()
  for (blockIdx in s.blockIndex..e.blockIndex) {
    val block = blocks.getOrNull(blockIdx) ?: continue
    val full = when (block) {
      is EpubBlock.Heading -> block.text
      is EpubBlock.Paragraph -> block.content.text
      is EpubBlock.Quote -> block.content.text
      is EpubBlock.Image -> ""
    }
    if (full.isEmpty()) continue

    val localStart = if (blockIdx == s.blockIndex) s.charOffset.coerceIn(0, full.length) else 0
    val localEnd = if (blockIdx == e.blockIndex) e.charOffset.coerceIn(localStart, full.length) else full.length
    if (localEnd > localStart) {
      parts.add(full.substring(localStart, localEnd))
    }
  }
  return parts.joinToString("\n\n").trim()
}

internal fun epubBlockText(block: EpubBlock?): String = when (block) {
  is EpubBlock.Heading -> block.text
  is EpubBlock.Paragraph -> block.content.text
  is EpubBlock.Quote -> block.content.text
  is EpubBlock.Image, null -> ""
}

fun expandEpubSelectionToParagraph(
  blocks: List<EpubBlock>,
  selection: EpubTextSelection?,
  fallbackBlockIndex: Int,
  chapterIndex: Int
): EpubTextSelection? {
  if (blocks.isEmpty()) return selection
  if (selection != null) {
    val sBlk = selection.start.blockIndex.coerceIn(0, blocks.lastIndex)
    val eBlk = selection.end.blockIndex.coerceIn(sBlk, blocks.lastIndex)
    val startText = epubBlockText(blocks.getOrNull(sBlk))
    val endText = epubBlockText(blocks.getOrNull(eBlk))
    if (startText.isEmpty() && endText.isEmpty()) return selection

    val startChar = startText.indexOfFirst { !it.isWhitespace() }.let { if (it >= 0) it else 0 }
    val endChar = if (endText.isNotEmpty()) {
      endText.trimEnd().length.let { if (it > 0) it else endText.length }
    } else {
      0
    }
    return EpubTextSelection(
      chapterIndex = chapterIndex,
      anchorA = EpubCharPosition(sBlk, startChar),
      anchorB = EpubCharPosition(eBlk, endChar)
    )
  }

  val safeFallback = fallbackBlockIndex.coerceIn(0, blocks.lastIndex)
  val targetBlk = (safeFallback..blocks.lastIndex).firstOrNull { epubBlockText(blocks[it]).isNotBlank() }
    ?: (safeFallback downTo 0).firstOrNull { epubBlockText(blocks[it]).isNotBlank() }
    ?: return null
  val txt = epubBlockText(blocks[targetBlk])
  val startChar = txt.indexOfFirst { !it.isWhitespace() }.let { if (it >= 0) it else 0 }
  val endChar = txt.trimEnd().length.coerceAtLeast((startChar + 1).coerceAtMost(txt.length))
  return EpubTextSelection(
    chapterIndex = chapterIndex,
    anchorA = EpubCharPosition(targetBlk, startChar),
    anchorB = EpubCharPosition(targetBlk, endChar)
  )
}

private data class EpubSliceKey(
  val pageOrScopeId: Int,
  val blockIndex: Int,
  val startCharInBlock: Int
)

private data class EpubSliceLayoutInfo(
  val key: EpubSliceKey,
  val textLength: Int,
  val layoutResult: TextLayoutResult,
  val posInRoot: Offset
)

private data class EpubSelectionGeometry(
  val anchorAPos: Offset?,
  val anchorBPos: Offset?,
  val selectionBounds: Rect?
)

private fun computeRangeInSlice(
  startBlock: Int,
  startChar: Int,
  endBlock: Int,
  endChar: Int,
  blockIndex: Int,
  sliceStartOffset: Int,
  sliceLength: Int
): IntRange? {
  if (sliceLength <= 0 || blockIndex < startBlock || blockIndex > endBlock) return null

  val sliceEndOffset = sliceStartOffset + sliceLength
  val blockStart = if (blockIndex == startBlock) startChar else 0
  val blockEnd = if (blockIndex == endBlock) endChar else Int.MAX_VALUE

  val overlapStart = maxOf(sliceStartOffset, blockStart)
  val overlapEnd = minOf(sliceEndOffset, blockEnd)
  if (overlapEnd <= overlapStart) return null

  val localStart = (overlapStart - sliceStartOffset).coerceIn(0, sliceLength)
  val localEnd = (overlapEnd - sliceStartOffset).coerceIn(localStart, sliceLength)
  return if (localEnd > localStart) localStart until localEnd else null
}

private fun computeLocalSliceHighlightRange(
  selection: EpubTextSelection?,
  blockIndex: Int,
  sliceStartOffset: Int,
  sliceLength: Int
): IntRange? {
  if (selection == null) return null
  val s = selection.start
  val e = selection.end
  return computeRangeInSlice(
    startBlock = s.blockIndex,
    startChar = s.charOffset,
    endBlock = e.blockIndex,
    endChar = e.charOffset,
    blockIndex = blockIndex,
    sliceStartOffset = sliceStartOffset,
    sliceLength = sliceLength
  )
}

fun resolveSavedHighlightRangeInSlice(
  highlight: HighlightEntity,
  fullBlockText: String,
  blockIndex: Int,
  sliceStartOffset: Int,
  sliceLength: Int
): IntRange? {
  if (highlight.isStickyNoteOnly || sliceLength <= 0 || highlight.blockIndex < 0) return null

  val endBlk = if (highlight.endBlockIndex >= highlight.blockIndex) highlight.endBlockIndex else highlight.blockIndex
  val hasValidOffsets = highlight.startCharOffset >= 0 && (
    (endBlk > highlight.blockIndex && highlight.endCharOffset >= 0) ||
      (endBlk == highlight.blockIndex && highlight.endCharOffset > highlight.startCharOffset)
    )
  if (hasValidOffsets) {
    return computeRangeInSlice(
      startBlock = highlight.blockIndex,
      startChar = highlight.startCharOffset,
      endBlock = endBlk,
      endChar = highlight.endCharOffset,
      blockIndex = blockIndex,
      sliceStartOffset = sliceStartOffset,
      sliceLength = sliceLength
    )
  }

  // Legacy highlight without saved offsets: match exact excerpt substring if present inside block
  if (highlight.blockIndex == blockIndex) {
    val cleanExcerpt = highlight.excerpt.trim()
    if (cleanExcerpt.isNotEmpty() && fullBlockText.isNotEmpty()) {
      val idx = fullBlockText.indexOf(cleanExcerpt)
      if (idx >= 0) {
        return computeRangeInSlice(
          startBlock = blockIndex,
          startChar = idx,
          endBlock = blockIndex,
          endChar = idx + cleanExcerpt.length,
          blockIndex = blockIndex,
          sliceStartOffset = sliceStartOffset,
          sliceLength = sliceLength
        )
      }
    }
    return 0 until sliceLength
  }

  return null
}

private fun computeEpubSelectionGeometry(
  selection: EpubTextSelection?,
  chapterIndex: Int,
  visibleSlices: Collection<EpubSliceLayoutInfo>,
  containerPosInRoot: Offset
): EpubSelectionGeometry {
  if (selection == null || selection.chapterIndex != chapterIndex || visibleSlices.isEmpty()) {
    return EpubSelectionGeometry(null, null, null)
  }

  val isAStart = selection.anchorA <= selection.anchorB
  var bounds: Rect? = null

  fun resolveAnchorPoint(pos: EpubCharPosition, isEndEdge: Boolean): Offset? {
    val matchingSlice = visibleSlices.firstOrNull { info ->
      info.key.blockIndex == pos.blockIndex &&
        pos.charOffset >= info.key.startCharInBlock &&
        pos.charOffset <= info.key.startCharInBlock + info.textLength
    } ?: return null

    val textLen = matchingSlice.layoutResult.layoutInput.text.length
    if (textLen == 0) return null

    val localOffset = (pos.charOffset - matchingSlice.key.startCharInBlock).coerceIn(0, textLen)
    val box = if (isEndEdge) {
      val idx = (localOffset - 1).coerceIn(0, textLen - 1)
      matchingSlice.layoutResult.getBoundingBox(idx)
    } else {
      val idx = localOffset.coerceIn(0, textLen - 1)
      matchingSlice.layoutResult.getBoundingBox(idx)
    }
    // Teardrop tip sits right at the bottom-left (start) or bottom-right (end) edge of the
    // selected character/line, pointing up toward the word without covering any letters.
    val x = (matchingSlice.posInRoot.x - containerPosInRoot.x) + if (isEndEdge) {
      box.right
    } else {
      box.left
    }
    val y = (matchingSlice.posInRoot.y - containerPosInRoot.y) + box.bottom
    return Offset(x, y)
  }

  val posA = resolveAnchorPoint(selection.anchorA, isEndEdge = !isAStart)
  val posB = resolveAnchorPoint(selection.anchorB, isEndEdge = isAStart)

  for (slice in visibleSlices) {
    val range = computeLocalSliceHighlightRange(
      selection = selection,
      blockIndex = slice.key.blockIndex,
      sliceStartOffset = slice.key.startCharInBlock,
      sliceLength = slice.textLength
    ) ?: continue

    val maxLen = slice.layoutResult.layoutInput.text.length
    val safeStart = range.first.coerceIn(0, maxLen)
    val safeEnd = (range.last + 1).coerceIn(safeStart, maxLen)
    if (safeEnd <= safeStart) continue

    val localRect = slice.layoutResult.getPathForRange(safeStart, safeEnd).getBounds()
    val dx = slice.posInRoot.x - containerPosInRoot.x
    val dy = slice.posInRoot.y - containerPosInRoot.y
    val translated = Rect(
      left = localRect.left + dx,
      top = localRect.top + dy,
      right = localRect.right + dx,
      bottom = localRect.bottom + dy
    )
    bounds = if (bounds == null) {
      translated
    } else {
      Rect(
        left = minOf(bounds.left, translated.left),
        top = minOf(bounds.top, translated.top),
        right = maxOf(bounds.right, translated.right),
        bottom = maxOf(bounds.bottom, translated.bottom)
      )
    }
  }

  return EpubSelectionGeometry(posA, posB, bounds)
}

private fun hitTestEpubCharPosition(
  pointerInContainer: Offset,
  containerPosInRoot: Offset,
  visibleSlices: Collection<EpubSliceLayoutInfo>,
  tipVerticalProbeOffsetPx: Float
): EpubCharPosition? {
  if (visibleSlices.isEmpty()) return null
  // The teardrop tip points straight up at the bottom edge of the letter; probe slightly upward
  // into the line so dragging tracks the exact letter above the tip without jumping a line down.
  val targetRoot = Offset(
    x = containerPosInRoot.x + pointerInContainer.x,
    y = containerPosInRoot.y + pointerInContainer.y - tipVerticalProbeOffsetPx
  )

  var bestSlice: EpubSliceLayoutInfo? = null
  var bestScore = Float.MAX_VALUE

  for (slice in visibleSlices) {
    val size = slice.layoutResult.size
    val top = slice.posInRoot.y
    val bottom = top + size.height
    val left = slice.posInRoot.x
    val right = left + size.width

    val dy = when {
      targetRoot.y < top -> top - targetRoot.y
      targetRoot.y > bottom -> targetRoot.y - bottom
      else -> 0f
    }
    val dx = when {
      targetRoot.x < left -> left - targetRoot.x
      targetRoot.x > right -> targetRoot.x - right
      else -> 0f
    }
    val score = dy * 4f + dx
    if (score < bestScore) {
      bestScore = score
      bestSlice = slice
    }
  }

  val chosen = bestSlice ?: return null
  val localOffsetPt = Offset(
    x = (targetRoot.x - chosen.posInRoot.x).coerceIn(0f, chosen.layoutResult.size.width.toFloat().coerceAtLeast(1f)),
    y = (targetRoot.y - chosen.posInRoot.y).coerceIn(0f, (chosen.layoutResult.size.height.toFloat() - 1f).coerceAtLeast(1f))
  )
  val localCharIdx = chosen.layoutResult.getOffsetForPosition(localOffsetPt)
    .coerceIn(0, chosen.textLength)
  return EpubCharPosition(
    blockIndex = chosen.key.blockIndex,
    charOffset = chosen.key.startCharInBlock + localCharIdx
  )
}

private sealed interface PageSliceItem {
  val blockIndex: Int

  data class HeadingSlice(
    override val blockIndex: Int,
    val level: Int,
    val text: String
  ) : PageSliceItem

  data class TextSlice(
    override val blockIndex: Int,
    val text: AnnotatedString,
    val fullBlockText: String,
    val startCharOffsetInBlock: Int = 0
  ) : PageSliceItem

  data class QuoteSlice(
    override val blockIndex: Int,
    val text: AnnotatedString,
    val fullBlockText: String,
    val startCharOffsetInBlock: Int = 0
  ) : PageSliceItem

  data class ImageSlice(
    override val blockIndex: Int,
    val zipEntryPath: String
  ) : PageSliceItem
}

private data class EpubRenderedPage(
  val items: List<PageSliceItem>
)

@Composable
fun EpubReaderContent(
  metadata: EpubMetadata,
  chapterStubs: List<EpubChapterStub>,
  activeChapter: EpubChapter,
  initialSubPage: Int,
  initialVerticalPosition: EpubVerticalScrollPosition? = null,
  preferences: ReaderPreferences,
  surfaceColors: ReaderSurfaceColors,
  imageCache: Map<String, Bitmap>,
  highlights: List<HighlightEntity>,
  ttsActiveBlockIndex: Int?,
  isAutoScrolling: Boolean,
  autoScrollSpeed: Float,
  topOverlayClearanceDp: Dp = 0.dp,
  onRequestImage: (String) -> Unit,
  onToggleControls: () -> Unit,
  onOpenFragmentTools: (
    startBlockIndex: Int,
    endBlockIndex: Int,
    startCharOffset: Int,
    endCharOffset: Int,
    text: String
  ) -> Unit,
  onCopyPlainText: (String) -> Unit,
  onShareSelection: (text: String, locationLabel: String) -> Unit,
  onPositionChanged: (chapterIndex: Int, subPage: Int, overallProgress: Float) -> Unit,
  onVerticalPositionChanged: (
    chapter: EpubChapter,
    position: EpubVerticalScrollPosition,
    overallProgress: Float
  ) -> Unit = { _, _, _ -> },
  onSelectChapter: (chapterIndex: Int) -> Unit = { nextIdx ->
    val prog = (nextIdx + 1).toFloat() / chapterStubs.size.coerceAtLeast(1)
    onPositionChanged(nextIdx, 0, prog)
  },
  onVisibleBlockChanged: (Int) -> Unit = {},
  onActiveSelectionInfoChanged: (
    startBlockIndex: Int,
    endBlockIndex: Int,
    startCharOffset: Int,
    endCharOffset: Int,
    text: String?
  ) -> Unit = { _, _, _, _, _ -> },
  modifier: Modifier = Modifier
) {
  val pagesCache = remember { EpubPagesCache<EpubRenderedPage>() }
  val chapterHighlights = remember(highlights, activeChapter.index) {
    highlights.filter { it.unitIndex == activeChapter.index && it.blockIndex >= 0 }
  }

  val spokenIndexByBlock = remember(activeChapter.index, activeChapter.blocks) {
    buildMap<Int, Int> {
      var spokenCounter = 0
      activeChapter.blocks.forEachIndexed { idx, block ->
        val txt = when (block) {
          is EpubBlock.Heading -> block.text
          is EpubBlock.Paragraph -> block.plainText
          is EpubBlock.Quote -> block.plainText
          is EpubBlock.Image -> ""
        }
        if (txt.isNotBlank()) {
          put(idx, spokenCounter++)
        }
      }
    }
  }

  var activeSelection by remember(activeChapter.index) {
    mutableStateOf<EpubTextSelection?>(null)
  }

  LaunchedEffect(activeSelection, activeChapter.index) {
    val sel = activeSelection
    if (sel != null && sel.chapterIndex == activeChapter.index) {
      val txt = extractSelectedEpubText(activeChapter.blocks, sel)
      if (txt.isNotEmpty()) {
        onActiveSelectionInfoChanged(
          sel.start.blockIndex,
          sel.end.blockIndex,
          sel.start.charOffset,
          sel.end.charOffset,
          txt
        )
      } else {
        onActiveSelectionInfoChanged(-1, -1, -1, -1, null)
      }
    } else {
      onActiveSelectionInfoChanged(-1, -1, -1, -1, null)
    }
  }

  if (preferences.epubScrollMode) {
    key(activeChapter.index) {
      EpubScrollReader(
        metadata = metadata,
        chapterStubs = chapterStubs,
        chapter = activeChapter,
        initialVerticalPosition = initialVerticalPosition,
        surfaceColors = surfaceColors,
        preferences = preferences,
        imageCache = imageCache,
        chapterHighlights = chapterHighlights,
        spokenIndexByBlock = spokenIndexByBlock,
        ttsActiveBlockIndex = ttsActiveBlockIndex,
        isAutoScrolling = isAutoScrolling,
        autoScrollSpeed = autoScrollSpeed,
        topOverlayClearanceDp = topOverlayClearanceDp,
        activeSelection = activeSelection,
        onSelectionChanged = { activeSelection = it },
        onRequestImage = onRequestImage,
        onToggleControls = onToggleControls,
        onOpenFragmentTools = onOpenFragmentTools,
        onCopyPlainText = onCopyPlainText,
        onShareSelection = onShareSelection,
        onVerticalPositionChanged = onVerticalPositionChanged,
        onSelectChapter = { nextIdx ->
          activeSelection = null
          onSelectChapter(nextIdx)
        },
        onVisibleBlockChanged = onVisibleBlockChanged,
        modifier = modifier
      )
    }
  } else {
    key(activeChapter.index) {
      EpubPaginatedReader(
        chapterStubs = chapterStubs,
        chapter = activeChapter,
        initialSubPage = initialSubPage,
        pagesCache = pagesCache,
        surfaceColors = surfaceColors,
        preferences = preferences,
        imageCache = imageCache,
        chapterHighlights = chapterHighlights,
        spokenIndexByBlock = spokenIndexByBlock,
        ttsActiveBlockIndex = ttsActiveBlockIndex,
        topOverlayClearanceDp = topOverlayClearanceDp,
        activeSelection = activeSelection,
        onSelectionChanged = { activeSelection = it },
        onRequestImage = onRequestImage,
        onToggleControls = onToggleControls,
        onOpenFragmentTools = onOpenFragmentTools,
        onCopyPlainText = onCopyPlainText,
        onShareSelection = onShareSelection,
        onPositionChanged = onPositionChanged,
        onSelectChapter = { nextIdx ->
          activeSelection = null
          onSelectChapter(nextIdx)
        },
        onVisibleBlockChanged = onVisibleBlockChanged,
        modifier = modifier
      )
    }
  }
}

@Composable
private fun EpubPaginatedReader(
  chapterStubs: List<EpubChapterStub>,
  chapter: EpubChapter,
  initialSubPage: Int,
  pagesCache: EpubPagesCache<EpubRenderedPage>,
  surfaceColors: ReaderSurfaceColors,
  preferences: ReaderPreferences,
  imageCache: Map<String, Bitmap>,
  chapterHighlights: List<HighlightEntity>,
  spokenIndexByBlock: Map<Int, Int>,
  ttsActiveBlockIndex: Int?,
  topOverlayClearanceDp: Dp,
  activeSelection: EpubTextSelection?,
  onSelectionChanged: (EpubTextSelection?) -> Unit,
  onRequestImage: (String) -> Unit,
  onToggleControls: () -> Unit,
  onOpenFragmentTools: (
    startBlockIndex: Int,
    endBlockIndex: Int,
    startCharOffset: Int,
    endCharOffset: Int,
    text: String
  ) -> Unit,
  onCopyPlainText: (String) -> Unit,
  onShareSelection: (text: String, locationLabel: String) -> Unit,
  onPositionChanged: (chapterIndex: Int, subPage: Int, overallProgress: Float) -> Unit,
  onSelectChapter: (chapterIndex: Int) -> Unit,
  onVisibleBlockChanged: (Int) -> Unit,
  modifier: Modifier = Modifier
) {
  val textMeasurer = rememberTextMeasurer()
  val density = LocalDensity.current
  val scope = rememberCoroutineScope()
  val totalChapters = chapterStubs.size.coerceAtLeast(1)

  val bodyFontFamily = preferences.readerFont.fontFamily()
  val bodyStyle = TextStyle(
    fontFamily = bodyFontFamily,
    fontSize = preferences.fontSizeSp.sp,
    lineHeight = (preferences.fontSizeSp * preferences.lineHeightMultiplier).sp,
    color = surfaceColors.text
  )
  val quoteStyle = bodyStyle.copy(
    fontStyle = FontStyle.Italic,
    color = surfaceColors.secondaryText
  )

  var overlayContainerSize by remember { mutableStateOf(IntSize.Zero) }
  var overlayContainerPosInRoot by remember { mutableStateOf(Offset.Zero) }
  val sliceLayouts = remember(chapter.index) {
    mutableStateMapOf<EpubSliceKey, EpubSliceLayoutInfo>()
  }

  val tipVerticalProbeOffsetPx = with(density) { 8.dp.toPx() }
  val contentInsets = rememberReaderContentInsets()
  val contentPadding = contentInsets.asPaddingValues()
  val controlsTopInsets = rememberReaderControlsTopInsets()
  val controlsBottomInsets = rememberReaderControlsBottomInsets()
  val statusTopDp = contentPadding.calculateTopPadding()
  val navBottomDp = contentPadding.calculateBottomPadding()
  val topSafePx = with(density) {
    (controlsTopInsets.asPaddingValues().calculateTopPadding() + topOverlayClearanceDp).toPx()
  }
  val bottomSafePx = with(density) {
    controlsBottomInsets.asPaddingValues().calculateBottomPadding().toPx()
  }

  val latestSelection by rememberUpdatedState(activeSelection)
  val latestOnSelectionChanged by rememberUpdatedState(onSelectionChanged)

  // Outer BoxWithConstraints has the exact root origin of ReaderSelectionOverlay (no statusBar shift!)
  BoxWithConstraints(
    modifier = modifier
      .fillMaxSize()
      .background(surfaceColors.background)
      .onGloballyPositioned { coords ->
        overlayContainerSize = coords.size
        overlayContainerPosInRoot = coords.positionInRoot()
      }
      .pointerInput(Unit) {
        detectSingleFingerTapAndLongPress(
          onTap = {
            if (latestSelection != null) {
              latestOnSelectionChanged(null)
            }
          }
        )
      }
  ) {
    val contentMaxWidthDp = minOf(maxWidth, 680.dp)
    val horizontalPad = preferences.horizontalPaddingDp.dp
    val availableWidthDp = (contentMaxWidthDp - horizontalPad * 2).coerceAtLeast(180.dp)
    val availableHeightDp = (maxHeight - statusTopDp - navBottomDp - 86.dp).coerceAtLeast(200.dp)

    val availableWidthPx = with(density) { availableWidthDp.roundToPx() }
    val availableHeightPx = with(density) { availableHeightDp.roundToPx() }
    val paragraphGapPx = with(density) { 14.dp.roundToPx() }
    val imageHeightPx = with(density) { 210.dp.roundToPx() }
    val quoteIndentPx = with(density) { 20.dp.roundToPx() }

    val layoutKey = EpubPaginationLayoutKey(
      availableWidthPx = availableWidthPx,
      availableHeightPx = availableHeightPx,
      fontSizeSp = preferences.fontSizeSp,
      lineHeightMultiplier = preferences.lineHeightMultiplier,
      readerFont = preferences.readerFont,
      horizontalPaddingDp = preferences.horizontalPaddingDp
    )
    val pages = remember(chapter.index, chapter.blocks, layoutKey) {
      pagesCache.getOrCompute(chapter.index, layoutKey) {
        paginateChapterBlocks(
          blocks = chapter.blocks,
          textMeasurer = textMeasurer,
          bodyStyle = bodyStyle,
          quoteStyle = quoteStyle,
          maxWidthPx = availableWidthPx,
          maxHeightPx = availableHeightPx,
          quoteWidthPx = (availableWidthPx - quoteIndentPx).coerceAtLeast(120),
          paragraphGapPx = paragraphGapPx,
          imageHeightPx = imageHeightPx,
          density = density
        )
      }
    }

    val safePageCount = pages.size.coerceAtLeast(1)
    val startPage = initialSubPage.coerceIn(0, safePageCount - 1)
    val pagerState = rememberPagerState(initialPage = startPage) { safePageCount }

    LaunchedEffect(chapter.index, safePageCount) {
      val target = initialSubPage.coerceIn(0, safePageCount - 1)
      if (pagerState.currentPage != target) {
        pagerState.scrollToPage(target)
      }
    }

    LaunchedEffect(pages) {
      val sel = activeSelection ?: return@LaunchedEffect
      if (sel.chapterIndex != chapter.index) return@LaunchedEffect
      val targetPage = pages.indexOfFirst { page ->
        page.items.any { item ->
          if (item.blockIndex != sel.start.blockIndex) false
          else when (item) {
            is PageSliceItem.HeadingSlice -> true
            is PageSliceItem.TextSlice ->
              sel.start.charOffset in item.startCharOffsetInBlock..(item.startCharOffsetInBlock + item.text.length)
            is PageSliceItem.QuoteSlice ->
              sel.start.charOffset in item.startCharOffsetInBlock..(item.startCharOffsetInBlock + item.text.length)
            is PageSliceItem.ImageSlice -> false
          }
        }
      }
      if (targetPage >= 0 && targetPage != pagerState.currentPage) {
        pagerState.scrollToPage(targetPage)
      }
    }

    LaunchedEffect(pagerState.currentPage, chapter.index, safePageCount, pages) {
      val chapterBase = chapter.index.toFloat() / totalChapters.toFloat()
      val inChapterFraction = (pagerState.currentPage + 1).toFloat() / safePageCount.toFloat()
      val overall = (chapterBase + inChapterFraction / totalChapters.toFloat()).coerceIn(0f, 1f)
      onPositionChanged(chapter.index, pagerState.currentPage, overall)
      val firstBlock = pages.getOrNull(pagerState.currentPage)?.items?.firstOrNull()?.blockIndex ?: 0
      onVisibleBlockChanged(firstBlock)
    }

    LaunchedEffect(pagerState.currentPage) {
      val sel = activeSelection ?: return@LaunchedEffect
      val page = pages.getOrNull(pagerState.currentPage) ?: return@LaunchedEffect
      val overlapsCurrentPage = page.items.any { item ->
        item.blockIndex in sel.start.blockIndex..sel.end.blockIndex
      }
      if (!overlapsCurrentPage) {
        onSelectionChanged(null)
      }
    }

    LaunchedEffect(ttsActiveBlockIndex, pages) {
      val activeSpoken = ttsActiveBlockIndex ?: return@LaunchedEffect
      val targetBlockIdx = spokenIndexByBlock.entries.firstOrNull { it.value == activeSpoken }?.key
        ?: return@LaunchedEffect
      val targetPageIdx = pages.indexOfFirst { page ->
        page.items.any { it.blockIndex == targetBlockIdx }
      }
      if (targetPageIdx >= 0 && targetPageIdx != pagerState.currentPage) {
        pagerState.animateScrollToPage(targetPageIdx)
      }
    }

    Column(
      modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(contentInsets),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      // Subtle editorial running header (chapter title on left only)
      Row(
        modifier = Modifier
          .widthIn(max = 680.dp)
          .fillMaxWidth()
          .padding(horizontal = horizontalPad, vertical = 10.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = chapter.title,
          style = MaterialTheme.typography.labelSmall,
          color = surfaceColors.secondaryText,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("epub_header_chapter_title")
        )
      }

      Box(
        modifier = Modifier
          .weight(1f)
          .widthIn(max = 680.dp)
          .fillMaxWidth()
          .pointerInput(chapter.index, safePageCount, totalChapters) {
            val swipeThresholdPx = 56.dp.toPx()
            awaitEachGesture {
              val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
              if (latestSelection != null) return@awaitEachGesture
              val startedOnFirstPage = pagerState.currentPage <= 0
              val startedOnLastPage = pagerState.currentPage >= safePageCount - 1
              if (!startedOnFirstPage && !startedOnLastPage) return@awaitEachGesture

              val startPos = down.position
              var multiTouch = false
              var lastPos = startPos

              while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } > 1) {
                  multiTouch = true
                }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                lastPos = change.position
                if (!change.pressed) break
              }

              if (multiTouch || latestSelection != null) return@awaitEachGesture
              when (
                resolveEpubBoundarySwipe(
                  startedOnFirstPage = startedOnFirstPage,
                  startedOnLastPage = startedOnLastPage,
                  chapterIndex = chapter.index,
                  totalChapters = totalChapters,
                  dx = lastPos.x - startPos.x,
                  dy = lastPos.y - startPos.y,
                  swipeThresholdPx = swipeThresholdPx
                )
              ) {
                EpubBoundaryAction.NEXT_CHAPTER -> {
                  onSelectChapter(chapter.index + 1)
                }
                EpubBoundaryAction.PREVIOUS_CHAPTER -> {
                  onSelectChapter(chapter.index - 1)
                }
                EpubBoundaryAction.NONE -> Unit
              }
            }
          }
      ) {
        HorizontalPager(
          state = pagerState,
          userScrollEnabled = activeSelection == null,
          modifier = Modifier.fillMaxSize()
        ) { pageIdx ->
          val page = pages[pageIdx]
          var pageWidthPx by remember { mutableFloatStateOf(availableWidthPx.toFloat()) }

          val handlePageTap: (Float) -> Unit = { xInPage ->
            if (latestSelection != null) {
              latestOnSelectionChanged(null)
            } else {
              val w = pageWidthPx.coerceAtLeast(1f)
              when {
                xInPage < w * 0.22f -> {
                  if (pageIdx > 0) {
                    scope.launch { pagerState.animateScrollToPage(pageIdx - 1) }
                  } else if (chapter.index > 0) {
                    onSelectChapter(chapter.index - 1)
                  }
                }
                xInPage > w * 0.78f -> {
                  if (pageIdx < safePageCount - 1) {
                    scope.launch { pagerState.animateScrollToPage(pageIdx + 1) }
                  } else if (chapter.index < totalChapters - 1) {
                    onSelectChapter(chapter.index + 1)
                  }
                }
                else -> onToggleControls()
              }
            }
          }
          val latestHandlePageTap by rememberUpdatedState(handlePageTap)

          Box(
            modifier = Modifier
              .fillMaxSize()
              .onGloballyPositioned { coords ->
                pageWidthPx = coords.size.width.toFloat()
              }
              .pointerInput(pageIdx, chapter.index, safePageCount) {
                detectSingleFingerTapAndLongPress(
                  onTap = { offset -> latestHandlePageTap(offset.x) }
                )
              }
              .padding(horizontal = horizontalPad, vertical = 6.dp)
          ) {
            Column(
              modifier = Modifier.fillMaxSize(),
              verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
              page.items.forEach { item ->
                val blockHighlights = remember(chapterHighlights, item.blockIndex) {
                  chapterHighlights.filter { hl ->
                    val endBlk = if (hl.endBlockIndex >= hl.blockIndex) hl.endBlockIndex else hl.blockIndex
                    item.blockIndex in hl.blockIndex..endBlk
                  }
                }
                val spokenIdx = spokenIndexByBlock[item.blockIndex]
                val isTtsActive = ttsActiveBlockIndex != null && spokenIdx == ttsActiveBlockIndex

                when (item) {
                  is PageSliceItem.HeadingSlice -> {
                    val headingSize = when (item.level) {
                      1 -> (preferences.fontSizeSp + 8f).sp
                      2 -> (preferences.fontSizeSp + 4f).sp
                      else -> (preferences.fontSizeSp + 2f).sp
                    }
                    val sliceKey = EpubSliceKey(pageIdx, item.blockIndex, 0)
                    SelectableTextBlockWrapper(
                      sliceKey = sliceKey,
                      startCharInBlock = 0,
                      rawText = item.text,
                      fullBlockText = item.text,
                      activeSelection = activeSelection,
                      chapterIndex = chapter.index,
                      selectionColor = surfaceColors.accent.copy(alpha = 0.28f),
                      savedHighlights = blockHighlights,
                      isDarkTheme = surfaceColors.isDark,
                      isTtsActive = isTtsActive,
                      accentColor = surfaceColors.accent,
                      sliceLayouts = sliceLayouts,
                      onTapXInPage = handlePageTap,
                      onSelectWord = { charOffset ->
                        val range = expandWordRangeAtOffset(item.text, charOffset)
                        onSelectionChanged(
                          EpubTextSelection(
                            chapterIndex = chapter.index,
                            anchorA = EpubCharPosition(item.blockIndex, range.first),
                            anchorB = EpubCharPosition(item.blockIndex, range.last + 1)
                          )
                        )
                      }
                    ) { onTextLayout, textModifier ->
                      Text(
                        text = item.text,
                        fontFamily = PlayfairDisplayFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = headingSize,
                        lineHeight = (headingSize.value * 1.25f).sp,
                        color = surfaceColors.text,
                        onTextLayout = onTextLayout,
                        modifier = textModifier
                      )
                    }
                  }

                  is PageSliceItem.TextSlice -> {
                    val sliceKey = EpubSliceKey(pageIdx, item.blockIndex, item.startCharOffsetInBlock)
                    val fullBlockText = (chapter.blocks.getOrNull(item.blockIndex) as? EpubBlock.Paragraph)?.content?.text
                      ?: item.fullBlockText
                    SelectableTextBlockWrapper(
                      sliceKey = sliceKey,
                      startCharInBlock = item.startCharOffsetInBlock,
                      rawText = item.text.text,
                      fullBlockText = fullBlockText,
                      activeSelection = activeSelection,
                      chapterIndex = chapter.index,
                      selectionColor = surfaceColors.accent.copy(alpha = 0.28f),
                      savedHighlights = blockHighlights,
                      isDarkTheme = surfaceColors.isDark,
                      isTtsActive = isTtsActive,
                      accentColor = surfaceColors.accent,
                      sliceLayouts = sliceLayouts,
                      onTapXInPage = handlePageTap,
                      onSelectWord = { localChar ->
                        val absOffset = (item.startCharOffsetInBlock + localChar).coerceIn(0, (fullBlockText.length - 1).coerceAtLeast(0))
                        val range = expandWordRangeAtOffset(fullBlockText, absOffset)
                        onSelectionChanged(
                          EpubTextSelection(
                            chapterIndex = chapter.index,
                            anchorA = EpubCharPosition(item.blockIndex, range.first),
                            anchorB = EpubCharPosition(item.blockIndex, range.last + 1)
                          )
                        )
                      }
                    ) { onTextLayout, textModifier ->
                      Text(
                        text = item.text,
                        style = bodyStyle,
                        onTextLayout = onTextLayout,
                        modifier = textModifier
                      )
                    }
                  }

                  is PageSliceItem.QuoteSlice -> {
                    val sliceKey = EpubSliceKey(pageIdx, item.blockIndex, item.startCharOffsetInBlock)
                    val fullBlockText = (chapter.blocks.getOrNull(item.blockIndex) as? EpubBlock.Quote)?.content?.text
                      ?: item.fullBlockText
                    SelectableTextBlockWrapper(
                      sliceKey = sliceKey,
                      startCharInBlock = item.startCharOffsetInBlock,
                      rawText = item.text.text,
                      fullBlockText = fullBlockText,
                      activeSelection = activeSelection,
                      chapterIndex = chapter.index,
                      selectionColor = surfaceColors.accent.copy(alpha = 0.28f),
                      savedHighlights = blockHighlights,
                      isDarkTheme = surfaceColors.isDark,
                      isTtsActive = isTtsActive,
                      accentColor = surfaceColors.accent,
                      sliceLayouts = sliceLayouts,
                      onTapXInPage = handlePageTap,
                      onSelectWord = { localChar ->
                        val absOffset = (item.startCharOffsetInBlock + localChar).coerceIn(0, (fullBlockText.length - 1).coerceAtLeast(0))
                        val range = expandWordRangeAtOffset(fullBlockText, absOffset)
                        onSelectionChanged(
                          EpubTextSelection(
                            chapterIndex = chapter.index,
                            anchorA = EpubCharPosition(item.blockIndex, range.first),
                            anchorB = EpubCharPosition(item.blockIndex, range.last + 1)
                          )
                        )
                      }
                    ) { onTextLayout, textModifier ->
                      Row(modifier = Modifier.fillMaxWidth()) {
                        Box(
                          modifier = Modifier
                            .width(3.dp)
                            .height(48.dp)
                            .background(surfaceColors.accent, RoundedCornerShape(2.dp))
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                          text = item.text,
                          style = quoteStyle,
                          onTextLayout = onTextLayout,
                          modifier = Modifier.weight(1f).then(textModifier)
                        )
                      }
                    }
                  }

                  is PageSliceItem.ImageSlice -> {
                    LaunchedEffect(item.zipEntryPath) {
                      onRequestImage(item.zipEntryPath)
                    }
                    val bmp = imageCache[item.zipEntryPath]
                    if (bmp != null) {
                      Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Ilustración del capítulo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                          .fillMaxWidth()
                          .height(200.dp)
                          .clip(RoundedCornerShape(10.dp))
                      )
                    }
                  }
                }
              }

            }
          }
        }
      }

      // Subtle footer page counter only
      Row(
        modifier = Modifier
          .widthIn(max = 680.dp)
          .fillMaxWidth()
          .padding(horizontal = horizontalPad, vertical = 10.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "Pág. ${pagerState.currentPage + 1} de $safePageCount",
          style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
          color = surfaceColors.secondaryText,
          modifier = Modifier.testTag("epub_footer_page_counter")
        )
      }
    }

    val currentPageSlices = sliceLayouts.values.filter { it.key.pageOrScopeId == pagerState.currentPage }
    val geometry = computeEpubSelectionGeometry(
      selection = activeSelection,
      chapterIndex = chapter.index,
      visibleSlices = currentPageSlices,
      containerPosInRoot = overlayContainerPosInRoot
    )
    val selectedText = remember(activeSelection, chapter.blocks) {
      if (activeSelection != null && activeSelection.chapterIndex == chapter.index) {
        extractSelectedEpubText(chapter.blocks, activeSelection)
      } else ""
    }
    val locationLabel = "cap. ${chapter.index + 1}, pág. ${pagerState.currentPage + 1}"

    if (activeSelection != null && selectedText.isNotEmpty()) {
      ReaderSelectionOverlay(
        containerSize = overlayContainerSize,
        topSafeInsetPx = topSafePx,
        bottomSafeInsetPx = bottomSafePx,
        surfaceColors = surfaceColors,
        handlesVisible = geometry.anchorAPos != null || geometry.anchorBPos != null,
        menuVisible = geometry.selectionBounds != null,
        anchorAPos = geometry.anchorAPos,
        anchorBPos = geometry.anchorBPos,
        selectionBounds = geometry.selectionBounds,
        copyEnabled = true,
        highlightEnabled = true,
        handleShape = SelectionHandleShape.TEARDROP,
        onDragAnchorA = { ptr ->
          val hit = hitTestEpubCharPosition(
            pointerInContainer = ptr,
            containerPosInRoot = overlayContainerPosInRoot,
            visibleSlices = currentPageSlices,
            tipVerticalProbeOffsetPx = tipVerticalProbeOffsetPx
          )
          if (hit != null && hit != activeSelection.anchorB) {
            onSelectionChanged(activeSelection.copy(anchorA = hit))
          }
        },
        onDragAnchorB = { ptr ->
          val hit = hitTestEpubCharPosition(
            pointerInContainer = ptr,
            containerPosInRoot = overlayContainerPosInRoot,
            visibleSlices = currentPageSlices,
            tipVerticalProbeOffsetPx = tipVerticalProbeOffsetPx
          )
          if (hit != null && hit != activeSelection.anchorA) {
            onSelectionChanged(activeSelection.copy(anchorB = hit))
          }
        },
        onCopyPlain = {
          onCopyPlainText(selectedText)
          onSelectionChanged(null)
        },
        onSelectParagraph = {
          val fallbackBlock = pages.getOrNull(pagerState.currentPage)?.items?.firstOrNull()?.blockIndex ?: 0
          onSelectionChanged(
            expandEpubSelectionToParagraph(
              blocks = chapter.blocks,
              selection = activeSelection,
              fallbackBlockIndex = fallbackBlock,
              chapterIndex = chapter.index
            )
          )
        },
        onHighlight = {
          onOpenFragmentTools(
            activeSelection.start.blockIndex,
            activeSelection.end.blockIndex,
            activeSelection.start.charOffset,
            activeSelection.end.charOffset,
            selectedText
          )
          onSelectionChanged(null)
        },
        onShare = {
          onShareSelection(selectedText, locationLabel)
          onSelectionChanged(null)
        }
      )
    }
  }
}

@Composable
private fun SelectableTextBlockWrapper(
  sliceKey: EpubSliceKey,
  startCharInBlock: Int,
  rawText: String,
  fullBlockText: String,
  activeSelection: EpubTextSelection?,
  chapterIndex: Int,
  selectionColor: Color,
  savedHighlights: List<HighlightEntity>,
  isDarkTheme: Boolean,
  isTtsActive: Boolean,
  accentColor: Color,
  sliceLayouts: MutableMap<EpubSliceKey, EpubSliceLayoutInfo>,
  onTapXInPage: (Float) -> Unit,
  onSelectWord: (localCharOffset: Int) -> Unit,
  textContent: @Composable (onTextLayout: (TextLayoutResult) -> Unit, textModifier: Modifier) -> Unit
) {
  var blockOffsetX by remember { mutableFloatStateOf(0f) }
  var latestLayout by remember(sliceKey) { mutableStateOf<TextLayoutResult?>(null) }
  var latestPosInRoot by remember(sliceKey) { mutableStateOf(Offset.Zero) }
  val latestOnTapXInPage by rememberUpdatedState(onTapXInPage)
  val latestOnSelectWord by rememberUpdatedState(onSelectWord)

  DisposableEffect(sliceKey) {
    onDispose { sliceLayouts.remove(sliceKey) }
  }

  val activeRange = remember(activeSelection, chapterIndex, sliceKey.blockIndex, startCharInBlock, rawText.length) {
    if (activeSelection != null && activeSelection.chapterIndex == chapterIndex) {
      computeLocalSliceHighlightRange(
        selection = activeSelection,
        blockIndex = sliceKey.blockIndex,
        sliceStartOffset = startCharInBlock,
        sliceLength = rawText.length
      )
    } else null
  }

  val savedSpanRanges = remember(savedHighlights, fullBlockText, sliceKey.blockIndex, startCharInBlock, rawText.length, isDarkTheme) {
    savedHighlights.mapNotNull { hl ->
      val range = resolveSavedHighlightRangeInSlice(
        highlight = hl,
        fullBlockText = fullBlockText,
        blockIndex = sliceKey.blockIndex,
        sliceStartOffset = startCharInBlock,
        sliceLength = rawText.length
      ) ?: return@mapNotNull null
      val wash = if (isDarkTheme) hl.color.washDark else hl.color.washLight
      range to wash
    }
  }

  val stickyNotes = remember(savedHighlights, sliceKey.blockIndex) {
    savedHighlights.filter { it.blockIndex == sliceKey.blockIndex && it.stickyNote.isNotBlank() }
  }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .then(
        if (isTtsActive) {
          Modifier
            .background(accentColor.copy(alpha = 0.14f))
            .border(1.dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
        } else Modifier
      )
      .onGloballyPositioned { coords ->
        blockOffsetX = coords.positionInParent().x
      }
      .padding(
        horizontal = if (isTtsActive) 6.dp else 0.dp,
        vertical = if (isTtsActive) 4.dp else 0.dp
      )
  ) {
    val textModifier = Modifier
      .onGloballyPositioned { coords ->
        val pos = coords.positionInRoot()
        if (latestPosInRoot != pos) {
          latestPosInRoot = pos
          latestLayout?.let { layout ->
            sliceLayouts[sliceKey] = EpubSliceLayoutInfo(
              key = sliceKey,
              textLength = rawText.length,
              layoutResult = layout,
              posInRoot = pos
            )
          }
        }
      }
      .drawBehind {
        val layout = latestLayout ?: return@drawBehind
        val maxLen = layout.layoutInput.text.length

        // Saved persistent character-level highlights (never colors the whole paragraph!)
        savedSpanRanges.forEach { (range, washColor) ->
          val s = range.first.coerceIn(0, maxLen)
          val e = (range.last + 1).coerceIn(s, maxLen)
          if (e > s) {
            drawPath(
              path = layout.getPathForRange(s, e),
              color = washColor
            )
          }
        }

        // Active live selection wash
        val range = activeRange
        if (range != null) {
          val s = range.first.coerceIn(0, maxLen)
          val e = (range.last + 1).coerceIn(s, maxLen)
          if (e > s) {
            drawPath(
              path = layout.getPathForRange(s, e),
              color = selectionColor
            )
          }
        }
      }
      .pointerInput(sliceKey, rawText) {
        detectSingleFingerTapAndLongPress(
          longPressTimeoutMs = SELECTION_LONG_PRESS_DELAY_MS,
          maxTapDurationMs = 320L,
          onLongPress = { localOffset ->
            val layout = latestLayout
            val localChar = if (layout != null && rawText.isNotEmpty()) {
              layout.getOffsetForPosition(localOffset).coerceIn(0, rawText.lastIndex)
            } else 0
            latestOnSelectWord(localChar)
          },
          onTap = { local -> latestOnTapXInPage(blockOffsetX + local.x) }
        )
      }

    textContent(
      { result ->
        latestLayout = result
        sliceLayouts[sliceKey] = EpubSliceLayoutInfo(
          key = sliceKey,
          textLength = rawText.length,
          layoutResult = result,
          posInRoot = latestPosInRoot
        )
      },
      textModifier
    )

    stickyNotes.forEach { noteHighlight ->
      Spacer(Modifier.height(4.dp))
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
          .fillMaxWidth()
          .background(noteHighlight.color.dotColor.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
          .padding(horizontal = 8.dp, vertical = 4.dp)
      ) {
        Box(
          modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(noteHighlight.color.dotColor)
        )
        Spacer(Modifier.width(6.dp))
        Text(
          text = "Nota: ${noteHighlight.stickyNote}",
          style = MaterialTheme.typography.labelSmall,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
  }
}

@Composable
private fun EpubScrollReader(
  metadata: EpubMetadata,
  chapterStubs: List<EpubChapterStub>,
  chapter: EpubChapter,
  initialVerticalPosition: EpubVerticalScrollPosition?,
  surfaceColors: ReaderSurfaceColors,
  preferences: ReaderPreferences,
  imageCache: Map<String, Bitmap>,
  chapterHighlights: List<HighlightEntity>,
  spokenIndexByBlock: Map<Int, Int>,
  ttsActiveBlockIndex: Int?,
  isAutoScrolling: Boolean,
  autoScrollSpeed: Float,
  topOverlayClearanceDp: Dp,
  activeSelection: EpubTextSelection?,
  onSelectionChanged: (EpubTextSelection?) -> Unit,
  onRequestImage: (String) -> Unit,
  onToggleControls: () -> Unit,
  onOpenFragmentTools: (
    startBlockIndex: Int,
    endBlockIndex: Int,
    startCharOffset: Int,
    endCharOffset: Int,
    text: String
  ) -> Unit,
  onCopyPlainText: (String) -> Unit,
  onShareSelection: (text: String, locationLabel: String) -> Unit,
  onVerticalPositionChanged: (
    chapter: EpubChapter,
    position: EpubVerticalScrollPosition,
    overallProgress: Float
  ) -> Unit,
  onSelectChapter: (Int) -> Unit,
  onVisibleBlockChanged: (Int) -> Unit,
  modifier: Modifier = Modifier
) {
  val chapterId = remember(chapter.index, chapter.zipPath) { epubChapterUniqueId(chapter) }
  val layoutSignature = remember(
    preferences.fontSizeSp,
    preferences.lineHeightMultiplier,
    preferences.readerFont,
    preferences.horizontalPaddingDp
  ) {
    EpubVerticalLayoutSignature(
      fontSizeSp = preferences.fontSizeSp,
      lineHeightMultiplier = preferences.lineHeightMultiplier,
      readerFont = preferences.readerFont,
      horizontalPaddingDp = preferences.horizontalPaddingDp
    )
  }
  val initialScrollTarget = remember(chapterId) {
    resolveVerticalScrollRestoreTarget(
      saved = initialVerticalPosition,
      blockCount = chapter.blocks.size,
      currentLayout = layoutSignature
    )
  }
  val listState = rememberLazyListState(
    initialFirstVisibleItemIndex = initialScrollTarget.first,
    initialFirstVisibleItemScrollOffset = initialScrollTarget.second
  )
  val density = LocalDensity.current
  val totalChapters = chapterStubs.size.coerceAtLeast(1)
  val coverImageBlock = remember(chapter.index, chapter.blocks, metadata.coverZipPath) {
    findVerticalEpubCoverImageBlock(chapter, metadata)
  }

  var overlayContainerSize by remember { mutableStateOf(IntSize.Zero) }
  var overlayContainerPosInRoot by remember { mutableStateOf(Offset.Zero) }
  val sliceLayouts = remember(chapter.index) {
    mutableStateMapOf<EpubSliceKey, EpubSliceLayoutInfo>()
  }

  var lastAppliedLayoutSignature by remember(chapterId) {
    mutableStateOf(initialVerticalPosition?.layoutSignature ?: layoutSignature)
  }
  var activeAnchor by remember(chapterId) {
    mutableStateOf(initialVerticalPosition)
  }
  var pendingLayoutRestoreAnchor by remember(chapterId) {
    mutableStateOf(
      if (initialVerticalPosition != null &&
        initialVerticalPosition.layoutSignature != null &&
        initialVerticalPosition.layoutSignature != layoutSignature
      ) {
        initialVerticalPosition
      } else {
        null
      }
    )
  }

  LaunchedEffect(layoutSignature) {
    if (layoutSignature != lastAppliedLayoutSignature && pendingLayoutRestoreAnchor == null) {
      pendingLayoutRestoreAnchor = activeAnchor ?: EpubVerticalScrollPosition(
        chapterId = chapterId,
        chapterIndex = chapter.index,
        blockIndex = listState.firstVisibleItemIndex.coerceAtLeast(0),
        scrollOffsetPx = listState.firstVisibleItemScrollOffset.coerceAtLeast(0),
        layoutSignature = lastAppliedLayoutSignature
      )
    }
  }

  val tipVerticalProbeOffsetPx = with(density) { 8.dp.toPx() }
  val contentInsets = rememberReaderContentInsets()
  val controlsTopInsets = rememberReaderControlsTopInsets()
  val controlsBottomInsets = rememberReaderControlsBottomInsets()
  val topSafePx = with(density) {
    (controlsTopInsets.asPaddingValues().calculateTopPadding() + topOverlayClearanceDp).toPx()
  }
  val bottomSafePx = with(density) {
    controlsBottomInsets.asPaddingValues().calculateBottomPadding().toPx()
  }

  val latestOnVerticalPositionChanged by rememberUpdatedState(onVerticalPositionChanged)

  fun captureCurrentVerticalPosition(): EpubVerticalScrollPosition {
    val maxIdx = (chapter.blocks.size - 1).coerceAtLeast(0)
    val firstIdx = listState.firstVisibleItemIndex.coerceIn(0, maxIdx)
    val firstOffset = listState.firstVisibleItemScrollOffset.coerceAtLeast(0)
    val visibleItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == firstIdx }
    val itemHeight = visibleItem?.size?.coerceAtLeast(1) ?: 0
    val fraction = if (itemHeight > 0) (firstOffset.toFloat() / itemHeight.toFloat()).coerceIn(0f, 1f) else 0f
    val layoutResult = sliceLayouts[EpubSliceKey(0, firstIdx, 0)]?.layoutResult
    val charOffset = if (layoutResult != null && layoutResult.layoutInput.text.isNotEmpty() && firstOffset > 0) {
      val line = layoutResult.getLineForVerticalPosition(firstOffset.toFloat().coerceAtLeast(0f))
      layoutResult.getLineStart(line).coerceIn(0, layoutResult.layoutInput.text.length - 1)
    } else {
      val rawLen = epubBlockText(chapter.blocks.getOrNull(firstIdx)).length
      if (rawLen > 0) (fraction * rawLen).roundToInt().coerceIn(0, rawLen - 1) else 0
    }
    return EpubVerticalScrollPosition(
      chapterId = chapterId,
      chapterIndex = chapter.index,
      blockIndex = firstIdx,
      scrollOffsetPx = firstOffset,
      blockScrollFraction = fraction,
      characterOffsetInBlock = charOffset,
      layoutSignature = lastAppliedLayoutSignature
    )
  }

  fun computeOverallProgress(blockIndex: Int, atBottom: Boolean): Float {
    val totalBlocks = chapter.blocks.size.coerceAtLeast(1)
    val chapterBase = chapter.index.toFloat() / totalChapters.toFloat()
    val inChapterFraction = if (coverImageBlock != null || atBottom) {
      1f
    } else {
      ((blockIndex + 1).toFloat() / totalBlocks.toFloat()).coerceIn(0f, 1f)
    }
    return (chapterBase + inChapterFraction / totalChapters.toFloat()).coerceIn(0f, 1f)
  }

  LaunchedEffect(pendingLayoutRestoreAnchor, layoutSignature) {
    val anchor = pendingLayoutRestoreAnchor ?: return@LaunchedEffect
    if (coverImageBlock != null || chapter.blocks.isEmpty()) {
      lastAppliedLayoutSignature = layoutSignature
      pendingLayoutRestoreAnchor = null
      return@LaunchedEffect
    }
    val targetBlock = anchor.blockIndex.coerceIn(0, chapter.blocks.lastIndex)
    if (listState.firstVisibleItemIndex != targetBlock) {
      listState.scrollToItem(targetBlock, 0)
    }
    var itemHeightPx: Int? = null
    var lineTopPx: Int? = null
    repeat(3) {
      withFrameNanos { }
      itemHeightPx = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetBlock }?.size
      val textLayout = sliceLayouts[EpubSliceKey(0, targetBlock, 0)]?.layoutResult
      if (textLayout != null && anchor.characterOffsetInBlock > 0 && textLayout.layoutInput.text.isNotEmpty()) {
        val safeChar = anchor.characterOffsetInBlock.coerceIn(0, textLayout.layoutInput.text.length - 1)
        val line = textLayout.getLineForOffset(safeChar)
        lineTopPx = textLayout.getLineTop(line).roundToInt().coerceAtLeast(0)
      }
      if (lineTopPx != null || (anchor.characterOffsetInBlock <= 0 && itemHeightPx != null)) {
        return@repeat
      }
    }
    val (resolvedBlock, resolvedOffset) = resolveVerticalScrollRestoreTarget(
      saved = anchor,
      blockCount = chapter.blocks.size,
      currentLayout = layoutSignature,
      newItemHeightPx = itemHeightPx,
      newLineTopPx = lineTopPx
    )
    listState.scrollToItem(resolvedBlock, resolvedOffset)
    lastAppliedLayoutSignature = layoutSignature
    val updated = captureCurrentVerticalPosition().copy(layoutSignature = layoutSignature)
    activeAnchor = updated
    pendingLayoutRestoreAnchor = null
    latestOnVerticalPositionChanged(
      chapter,
      updated,
      computeOverallProgress(updated.blockIndex, !listState.canScrollForward)
    )
  }

  LaunchedEffect(chapterId, listState) {
    snapshotFlow {
      Triple(
        listState.firstVisibleItemIndex,
        listState.firstVisibleItemScrollOffset,
        listState.isScrollInProgress
      )
    }.collect { (firstIdx, _, isScrolling) ->
      onVisibleBlockChanged(firstIdx)
      if (coverImageBlock == null && pendingLayoutRestoreAnchor == null && lastAppliedLayoutSignature == layoutSignature) {
        val pos = captureCurrentVerticalPosition()
        activeAnchor = pos
        if (!isScrolling || pos.blockIndex > 0 || pos.scrollOffsetPx > 0) {
          latestOnVerticalPositionChanged(
            chapter,
            pos,
            computeOverallProgress(pos.blockIndex, !listState.canScrollForward)
          )
        }
      }
    }
  }

  DisposableEffect(chapterId) {
    onDispose {
      if (coverImageBlock == null && pendingLayoutRestoreAnchor == null) {
        val finalPos = captureCurrentVerticalPosition()
        latestOnVerticalPositionChanged(
          chapter,
          finalPos,
          computeOverallProgress(finalPos.blockIndex, !listState.canScrollForward)
        )
      }
    }
  }

  LaunchedEffect(isAutoScrolling, autoScrollSpeed, chapter.index) {
    if (!isAutoScrolling) return@LaunchedEffect
    while (isActive) {
      if (listState.canScrollForward) {
        listState.scrollBy(autoScrollSpeed * 1.15f)
      }
      delay(16L)
    }
  }

  LaunchedEffect(ttsActiveBlockIndex, chapter.index) {
    val activeSpoken = ttsActiveBlockIndex ?: return@LaunchedEffect
    val blockIdx = spokenIndexByBlock.entries.firstOrNull { it.value == activeSpoken }?.key
      ?: return@LaunchedEffect
    if (blockIdx in chapter.blocks.indices) {
      listState.animateScrollToItem(blockIdx)
    }
  }

  val bodyFontFamily = preferences.readerFont.fontFamily()
  val bodyStyle = TextStyle(
    fontFamily = bodyFontFamily,
    fontSize = preferences.fontSizeSp.sp,
    lineHeight = (preferences.fontSizeSp * preferences.lineHeightMultiplier).sp,
    color = surfaceColors.text
  )

  val latestSelection by rememberUpdatedState(activeSelection)
  val latestOnSelectionChanged by rememberUpdatedState(onSelectionChanged)
  val latestOnToggleControls by rememberUpdatedState(onToggleControls)
  val latestOnSelectChapter by rememberUpdatedState(onSelectChapter)

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(surfaceColors.background)
      .onGloballyPositioned { coords ->
        overlayContainerSize = coords.size
        overlayContainerPosInRoot = coords.positionInRoot()
      }
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(contentInsets)
        .pointerInput(Unit) {
          detectSingleFingerTapAndLongPress(
            onTap = {
              if (latestSelection != null) latestOnSelectionChanged(null)
              else latestOnToggleControls()
            }
          )
        }
        .pointerInput(chapter.index, totalChapters, coverImageBlock != null) {
          val verticalSwipeThresholdPx = 52.dp.toPx()
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (latestSelection != null) return@awaitEachGesture
            val isCoverStage = coverImageBlock != null
            val startedAtTop = isCoverStage || !listState.canScrollBackward
            val startedAtBottom = isCoverStage || !listState.canScrollForward
            if (!startedAtTop && !startedAtBottom) return@awaitEachGesture

            val startPos = down.position
            var multiTouch = false
            var lastPos = startPos

            while (true) {
              val event = awaitPointerEvent(PointerEventPass.Initial)
              if (event.changes.count { it.pressed } > 1) {
                multiTouch = true
              }
              val change = event.changes.firstOrNull { it.id == down.id } ?: break
              lastPos = change.position
              if (!change.pressed) break
            }

            if (multiTouch || latestSelection != null) return@awaitEachGesture
            when (
              resolveEpubVerticalBoundaryScroll(
                startedAtTop = startedAtTop,
                startedAtBottom = startedAtBottom,
                stillAtTop = isCoverStage || !listState.canScrollBackward,
                stillAtBottom = isCoverStage || !listState.canScrollForward,
                chapterIndex = chapter.index,
                totalChapters = totalChapters,
                dx = lastPos.x - startPos.x,
                dy = lastPos.y - startPos.y,
                swipeThresholdPx = verticalSwipeThresholdPx
              )
            ) {
              EpubBoundaryAction.NEXT_CHAPTER -> {
                if (coverImageBlock == null && pendingLayoutRestoreAnchor == null) {
                  val pos = captureCurrentVerticalPosition()
                  latestOnVerticalPositionChanged(
                    chapter,
                    pos,
                    computeOverallProgress(pos.blockIndex, !listState.canScrollForward)
                  )
                }
                latestOnSelectChapter(chapter.index + 1)
              }
              EpubBoundaryAction.PREVIOUS_CHAPTER -> {
                if (coverImageBlock == null && pendingLayoutRestoreAnchor == null) {
                  val pos = captureCurrentVerticalPosition()
                  latestOnVerticalPositionChanged(
                    chapter,
                    pos,
                    computeOverallProgress(pos.blockIndex, !listState.canScrollForward)
                  )
                }
                latestOnSelectChapter(chapter.index - 1)
              }
              EpubBoundaryAction.NONE -> Unit
            }
          }
        },
      contentAlignment = if (coverImageBlock != null) Alignment.Center else Alignment.TopCenter
    ) {
      if (coverImageBlock != null) {
        LaunchedEffect(coverImageBlock.zipEntryPath) {
          onRequestImage(coverImageBlock.zipEntryPath)
        }
        val bmp = imageCache[coverImageBlock.zipEntryPath]
        BoxWithConstraints(
          modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = preferences.horizontalPaddingDp.dp, vertical = 20.dp)
            .testTag("epub_vertical_cover_container"),
          contentAlignment = Alignment.Center
        ) {
          if (bmp != null) {
            val constrainedMaxW = minOf(maxWidth, 680.dp)
            val (targetW, targetH) = computeFittedCoverDimensions(
              imageWidthPx = bmp.width,
              imageHeightPx = bmp.height,
              maxWidthDp = constrainedMaxW.value,
              maxHeightDp = maxHeight.value
            )
            Image(
              bitmap = bmp.asImageBitmap(),
              contentDescription = coverImageBlock.alt.ifBlank { "Portada del libro" },
              contentScale = ContentScale.Fit,
              modifier = Modifier
                .size(width = targetW.dp, height = targetH.dp)
                .clip(RoundedCornerShape(10.dp))
                .testTag("epub_vertical_cover_image")
            )
          }
        }
      } else {
        LazyColumn(
          state = listState,
          userScrollEnabled = activeSelection == null,
          contentPadding = PaddingValues(
            horizontal = preferences.horizontalPaddingDp.dp,
            vertical = 24.dp
          ),
          verticalArrangement = Arrangement.spacedBy(16.dp),
          modifier = Modifier
            .widthIn(max = 680.dp)
            .fillMaxHeight()
            .pointerInput(Unit) {
              detectSingleFingerTapAndLongPress(
                onTap = {
                  if (latestSelection != null) latestOnSelectionChanged(null)
                  else latestOnToggleControls()
                }
              )
            }
            .testTag("epub_scroll_list")
        ) {
        itemsIndexed(chapter.blocks) { blockIndex, block ->
          val blockHighlights = remember(chapterHighlights, blockIndex) {
            chapterHighlights.filter { hl ->
              val endBlk = if (hl.endBlockIndex >= hl.blockIndex) hl.endBlockIndex else hl.blockIndex
              blockIndex in hl.blockIndex..endBlk
            }
          }
          val spokenIdx = spokenIndexByBlock[blockIndex]
          val isTtsActive = ttsActiveBlockIndex != null && spokenIdx == ttsActiveBlockIndex

          when (block) {
            is EpubBlock.Heading -> {
              val headingSize = when (block.level) {
                1 -> (preferences.fontSizeSp + 8f).sp
                2 -> (preferences.fontSizeSp + 4f).sp
                else -> (preferences.fontSizeSp + 2f).sp
              }
              val sliceKey = EpubSliceKey(0, blockIndex, 0)
              SelectableTextBlockWrapper(
                sliceKey = sliceKey,
                startCharInBlock = 0,
                rawText = block.text,
                fullBlockText = block.text,
                activeSelection = activeSelection,
                chapterIndex = chapter.index,
                selectionColor = surfaceColors.accent.copy(alpha = 0.28f),
                savedHighlights = blockHighlights,
                isDarkTheme = surfaceColors.isDark,
                isTtsActive = isTtsActive,
                accentColor = surfaceColors.accent,
                sliceLayouts = sliceLayouts,
                onTapXInPage = {
                  if (latestSelection != null) latestOnSelectionChanged(null) else latestOnToggleControls()
                },
                onSelectWord = { localChar ->
                  val range = expandWordRangeAtOffset(block.text, localChar)
                  onSelectionChanged(
                    EpubTextSelection(
                      chapterIndex = chapter.index,
                      anchorA = EpubCharPosition(blockIndex, range.first),
                      anchorB = EpubCharPosition(blockIndex, range.last + 1)
                    )
                  )
                }
              ) { onTextLayout, textModifier ->
                Text(
                  text = block.text,
                  fontFamily = PlayfairDisplayFamily,
                  fontWeight = FontWeight.Bold,
                  fontSize = headingSize,
                  lineHeight = (headingSize.value * 1.25f).sp,
                  color = surfaceColors.text,
                  onTextLayout = onTextLayout,
                  modifier = textModifier.padding(top = 8.dp)
                )
              }
            }

            is EpubBlock.Paragraph -> {
              val sliceKey = EpubSliceKey(0, blockIndex, 0)
              SelectableTextBlockWrapper(
                sliceKey = sliceKey,
                startCharInBlock = 0,
                rawText = block.content.text,
                fullBlockText = block.content.text,
                activeSelection = activeSelection,
                chapterIndex = chapter.index,
                selectionColor = surfaceColors.accent.copy(alpha = 0.28f),
                savedHighlights = blockHighlights,
                isDarkTheme = surfaceColors.isDark,
                isTtsActive = isTtsActive,
                accentColor = surfaceColors.accent,
                sliceLayouts = sliceLayouts,
                onTapXInPage = {
                  if (latestSelection != null) latestOnSelectionChanged(null) else latestOnToggleControls()
                },
                onSelectWord = { localChar ->
                  val range = expandWordRangeAtOffset(block.content.text, localChar)
                  onSelectionChanged(
                    EpubTextSelection(
                      chapterIndex = chapter.index,
                      anchorA = EpubCharPosition(blockIndex, range.first),
                      anchorB = EpubCharPosition(blockIndex, range.last + 1)
                    )
                  )
                }
              ) { onTextLayout, textModifier ->
                Text(
                  text = block.content,
                  style = bodyStyle,
                  onTextLayout = onTextLayout,
                  modifier = textModifier
                )
              }
            }

            is EpubBlock.Quote -> {
              val sliceKey = EpubSliceKey(0, blockIndex, 0)
              Surface(
                color = surfaceColors.surface,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
              ) {
                Row(modifier = Modifier.padding(14.dp)) {
                  Box(
                    modifier = Modifier
                      .width(3.dp)
                      .height(40.dp)
                      .background(surfaceColors.accent, RoundedCornerShape(2.dp))
                  )
                  Spacer(Modifier.width(12.dp))
                  Box(modifier = Modifier.weight(1f)) {
                    SelectableTextBlockWrapper(
                      sliceKey = sliceKey,
                      startCharInBlock = 0,
                      rawText = block.content.text,
                      fullBlockText = block.content.text,
                      activeSelection = activeSelection,
                      chapterIndex = chapter.index,
                      selectionColor = surfaceColors.accent.copy(alpha = 0.28f),
                      savedHighlights = blockHighlights,
                      isDarkTheme = surfaceColors.isDark,
                      isTtsActive = isTtsActive,
                      accentColor = surfaceColors.accent,
                      sliceLayouts = sliceLayouts,
                      onTapXInPage = {
                        if (latestSelection != null) latestOnSelectionChanged(null) else latestOnToggleControls()
                      },
                      onSelectWord = { localChar ->
                        val range = expandWordRangeAtOffset(block.content.text, localChar)
                        onSelectionChanged(
                          EpubTextSelection(
                            chapterIndex = chapter.index,
                            anchorA = EpubCharPosition(blockIndex, range.first),
                            anchorB = EpubCharPosition(blockIndex, range.last + 1)
                          )
                        )
                      }
                    ) { onTextLayout, textModifier ->
                      Text(
                        text = block.content,
                        style = bodyStyle.copy(
                          fontStyle = FontStyle.Italic,
                          color = surfaceColors.secondaryText
                        ),
                        onTextLayout = onTextLayout,
                        modifier = textModifier
                      )
                    }
                  }
                }
              }
            }

            is EpubBlock.Image -> {
              LaunchedEffect(block.zipEntryPath) {
                onRequestImage(block.zipEntryPath)
              }
              val bmp = imageCache[block.zipEntryPath]
              if (bmp != null) {
                Image(
                  bitmap = bmp.asImageBitmap(),
                  contentDescription = block.alt.ifBlank { "Ilustración del libro" },
                  contentScale = ContentScale.Fit,
                  modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(10.dp))
                )
              }
            }
          }
        }
      }
      }
    }

    val scrollSlices = sliceLayouts.values
    val geometry = computeEpubSelectionGeometry(
      selection = activeSelection,
      chapterIndex = chapter.index,
      visibleSlices = scrollSlices,
      containerPosInRoot = overlayContainerPosInRoot
    )
    val selectedText = remember(activeSelection, chapter.blocks) {
      if (activeSelection != null && activeSelection.chapterIndex == chapter.index) {
        extractSelectedEpubText(chapter.blocks, activeSelection)
      } else ""
    }
    val locationLabel = "cap. ${chapter.index + 1}"

    if (activeSelection != null && selectedText.isNotEmpty()) {
      ReaderSelectionOverlay(
        containerSize = overlayContainerSize,
        topSafeInsetPx = topSafePx,
        bottomSafeInsetPx = bottomSafePx,
        surfaceColors = surfaceColors,
        handlesVisible = geometry.anchorAPos != null || geometry.anchorBPos != null,
        menuVisible = geometry.selectionBounds != null,
        anchorAPos = geometry.anchorAPos,
        anchorBPos = geometry.anchorBPos,
        selectionBounds = geometry.selectionBounds,
        copyEnabled = true,
        highlightEnabled = true,
        handleShape = SelectionHandleShape.TEARDROP,
        onDragAnchorA = { ptr ->
          val hit = hitTestEpubCharPosition(
            pointerInContainer = ptr,
            containerPosInRoot = overlayContainerPosInRoot,
            visibleSlices = scrollSlices,
            tipVerticalProbeOffsetPx = tipVerticalProbeOffsetPx
          )
          if (hit != null && hit != activeSelection.anchorB) {
            onSelectionChanged(activeSelection.copy(anchorA = hit))
          }
        },
        onDragAnchorB = { ptr ->
          val hit = hitTestEpubCharPosition(
            pointerInContainer = ptr,
            containerPosInRoot = overlayContainerPosInRoot,
            visibleSlices = scrollSlices,
            tipVerticalProbeOffsetPx = tipVerticalProbeOffsetPx
          )
          if (hit != null && hit != activeSelection.anchorA) {
            onSelectionChanged(activeSelection.copy(anchorB = hit))
          }
        },
        onCopyPlain = {
          onCopyPlainText(selectedText)
          onSelectionChanged(null)
        },
        onSelectParagraph = {
          onSelectionChanged(
            expandEpubSelectionToParagraph(
              blocks = chapter.blocks,
              selection = activeSelection,
              fallbackBlockIndex = listState.firstVisibleItemIndex,
              chapterIndex = chapter.index
            )
          )
        },
        onHighlight = {
          onOpenFragmentTools(
            activeSelection.start.blockIndex,
            activeSelection.end.blockIndex,
            activeSelection.start.charOffset,
            activeSelection.end.charOffset,
            selectedText
          )
          onSelectionChanged(null)
        },
        onShare = {
          onShareSelection(selectedText, locationLabel)
          onSelectionChanged(null)
        }
      )
    }
  }
}

private fun paginateChapterBlocks(
  blocks: List<EpubBlock>,
  textMeasurer: TextMeasurer,
  bodyStyle: TextStyle,
  quoteStyle: TextStyle,
  maxWidthPx: Int,
  maxHeightPx: Int,
  quoteWidthPx: Int,
  paragraphGapPx: Int,
  imageHeightPx: Int,
  density: androidx.compose.ui.unit.Density
): List<EpubRenderedPage> {
  val pages = mutableListOf<EpubRenderedPage>()
  var currentItems = mutableListOf<PageSliceItem>()
  var usedHeightPx = 0

  fun flushPage() {
    if (currentItems.isNotEmpty()) {
      pages.add(EpubRenderedPage(currentItems))
      currentItems = mutableListOf()
      usedHeightPx = 0
    }
  }

  fun addTextWithSplit(
    blockIndex: Int,
    annotated: AnnotatedString,
    plainText: String,
    style: TextStyle,
    widthPx: Int,
    isQuote: Boolean
  ) {
    var remaining = annotated
    var blockCharOffset = 0
    while (remaining.text.isNotEmpty()) {
      val layout = textMeasurer.measure(
        text = remaining,
        style = style,
        constraints = Constraints(maxWidth = widthPx)
      )
      val gap = if (currentItems.isEmpty()) 0 else paragraphGapPx
      val available = maxHeightPx - usedHeightPx - gap

      if (layout.size.height <= available) {
        currentItems.add(
          if (isQuote) PageSliceItem.QuoteSlice(blockIndex, remaining, plainText, blockCharOffset)
          else PageSliceItem.TextSlice(blockIndex, remaining, plainText, blockCharOffset)
        )
        usedHeightPx += gap + layout.size.height
        break
      }

      var fittingLines = 0
      for (line in 0 until layout.lineCount) {
        if (layout.getLineBottom(line) <= available) {
          fittingLines = line + 1
        } else {
          break
        }
      }

      if (fittingLines == 0) {
        if (currentItems.isNotEmpty()) {
          flushPage()
          continue
        } else {
          fittingLines = 1
        }
      }

      val splitOffset = layout.getLineEnd(fittingLines - 1, visibleEnd = true).coerceIn(1, remaining.length)
      val head = remaining.subSequence(0, splitOffset)
      val tailText = remaining.subSequence(splitOffset, remaining.length)
      val trimmedTailStart = tailText.text.indexOfFirst { !it.isWhitespace() }

      currentItems.add(
        if (isQuote) PageSliceItem.QuoteSlice(blockIndex, head, plainText, blockCharOffset)
        else PageSliceItem.TextSlice(blockIndex, head, plainText, blockCharOffset)
      )
      flushPage()

      if (trimmedTailStart >= 0) {
        blockCharOffset += splitOffset + trimmedTailStart
        remaining = tailText.subSequence(trimmedTailStart, tailText.length)
      } else {
        remaining = AnnotatedString("")
      }
    }
  }

  blocks.forEachIndexed { blockIdx, block ->
    when (block) {
      is EpubBlock.Heading -> {
        val headingSp = when (block.level) {
          1 -> bodyStyle.fontSize.value + 8f
          2 -> bodyStyle.fontSize.value + 4f
          else -> bodyStyle.fontSize.value + 2f
        }
        val headingStyle = TextStyle(
          fontFamily = PlayfairDisplayFamily,
          fontWeight = FontWeight.Bold,
          fontSize = headingSp.sp,
          lineHeight = (headingSp * 1.25f).sp
        )
        val layout = textMeasurer.measure(
          text = block.text,
          style = headingStyle,
          constraints = Constraints(maxWidth = maxWidthPx)
        )
        val gap = if (currentItems.isEmpty()) 0 else paragraphGapPx
        if (usedHeightPx + gap + layout.size.height > maxHeightPx && currentItems.isNotEmpty()) {
          flushPage()
        }
        val afterGap = if (currentItems.isEmpty()) 0 else paragraphGapPx
        currentItems.add(PageSliceItem.HeadingSlice(blockIdx, block.level, block.text))
        usedHeightPx += afterGap + layout.size.height
      }
      is EpubBlock.Paragraph -> {
        addTextWithSplit(blockIdx, block.content, block.plainText, bodyStyle, maxWidthPx, isQuote = false)
      }
      is EpubBlock.Quote -> {
        addTextWithSplit(blockIdx, block.content, block.plainText, quoteStyle, quoteWidthPx, isQuote = true)
      }
      is EpubBlock.Image -> {
        val gap = if (currentItems.isEmpty()) 0 else paragraphGapPx
        val imgMargin = with(density) { 8.dp.roundToPx() }
        if (usedHeightPx + gap + imageHeightPx + imgMargin > maxHeightPx && currentItems.isNotEmpty()) {
          flushPage()
        }
        val afterGap = if (currentItems.isEmpty()) 0 else paragraphGapPx
        currentItems.add(PageSliceItem.ImageSlice(blockIdx, block.zipEntryPath))
        usedHeightPx += afterGap + imageHeightPx
      }
    }
  }

  flushPage()
  if (pages.isEmpty()) {
    pages.add(EpubRenderedPage(emptyList()))
  }
  return pages
}
