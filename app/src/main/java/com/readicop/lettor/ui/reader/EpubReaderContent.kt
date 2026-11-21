package com.readicop.lettor.ui.reader

import android.graphics.Bitmap
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import kotlin.math.sign
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal enum class EpubBoundaryAction {
  NONE,
  NEXT_CHAPTER,
  PREVIOUS_CHAPTER
}

internal enum class EpubHorizontalTapAction {
  PREVIOUS_PAGE,
  TOGGLE_CONTROLS,
  NEXT_PAGE
}

internal const val HORIZONTAL_EPUB_LEFT_ZONE_FRACTION = 0.28f
internal const val HORIZONTAL_EPUB_RIGHT_ZONE_FRACTION = 0.72f

internal fun resolveEpubHorizontalTapAction(
  tapX: Float,
  containerWidthPx: Float
): EpubHorizontalTapAction {
  if (containerWidthPx <= 0f) return EpubHorizontalTapAction.TOGGLE_CONTROLS
  val nx = (tapX / containerWidthPx).coerceIn(0f, 1f)
  return when {
    nx < HORIZONTAL_EPUB_LEFT_ZONE_FRACTION -> EpubHorizontalTapAction.PREVIOUS_PAGE
    nx > HORIZONTAL_EPUB_RIGHT_ZONE_FRACTION -> EpubHorizontalTapAction.NEXT_PAGE
    else -> EpubHorizontalTapAction.TOGGLE_CONTROLS
  }
}

internal fun resolveEpubBoundarySwipe(
  startedOnFirstPage: Boolean,
  startedOnLastPage: Boolean,
  chapterIndex: Int,
  totalChapters: Int,
  dx: Float,
  dy: Float,
  swipeThresholdPx: Float,
  pagerSettledOnStartPage: Boolean = true,
  pagerScrollInProgress: Boolean = false
): EpubBoundaryAction {
  if (pagerScrollInProgress || !pagerSettledOnStartPage) return EpubBoundaryAction.NONE
  if (abs(dx) < swipeThresholdPx) return EpubBoundaryAction.NONE
  if (abs(dx) <= abs(dy) * 0.95f) return EpubBoundaryAction.NONE
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
  swipeThresholdPx: Float,
  gestureScrolledInsideList: Boolean = false,
  gestureStartedScrollInProgress: Boolean = false,
  overscrollAccumulatedY: Float = dy
): EpubBoundaryAction {
  if (gestureScrolledInsideList || gestureStartedScrollInProgress) return EpubBoundaryAction.NONE
  if (abs(overscrollAccumulatedY) < swipeThresholdPx) return EpubBoundaryAction.NONE
  if (abs(dy) < swipeThresholdPx) return EpubBoundaryAction.NONE
  if (abs(dy) <= abs(dx) * 1.15f) return EpubBoundaryAction.NONE
  return when {
    overscrollAccumulatedY <= -swipeThresholdPx && dy < 0f && startedAtBottom && stillAtBottom && chapterIndex < totalChapters - 1 ->
      EpubBoundaryAction.NEXT_CHAPTER
    overscrollAccumulatedY >= swipeThresholdPx && dy > 0f && startedAtTop && stillAtTop && chapterIndex > 0 ->
      EpubBoundaryAction.PREVIOUS_CHAPTER
    else -> EpubBoundaryAction.NONE
  }
}

internal fun resolveEpubVerticalOrHorizontalBoundaryAction(
  startedAtTop: Boolean,
  startedAtBottom: Boolean,
  stillAtTop: Boolean,
  stillAtBottom: Boolean,
  chapterIndex: Int,
  totalChapters: Int,
  dx: Float,
  dy: Float,
  swipeThresholdPx: Float,
  gestureScrolledInsideList: Boolean = false,
  gestureStartedScrollInProgress: Boolean = false,
  overscrollAccumulatedY: Float = dy
): EpubBoundaryAction {
  val vertical = resolveEpubVerticalBoundaryScroll(
    startedAtTop = startedAtTop,
    startedAtBottom = startedAtBottom,
    stillAtTop = stillAtTop,
    stillAtBottom = stillAtBottom,
    chapterIndex = chapterIndex,
    totalChapters = totalChapters,
    dx = dx,
    dy = dy,
    swipeThresholdPx = swipeThresholdPx,
    gestureScrolledInsideList = gestureScrolledInsideList,
    gestureStartedScrollInProgress = gestureStartedScrollInProgress,
    overscrollAccumulatedY = overscrollAccumulatedY
  )
  if (vertical != EpubBoundaryAction.NONE) return vertical
  if (gestureScrolledInsideList || gestureStartedScrollInProgress) return EpubBoundaryAction.NONE
  if (abs(dx) < swipeThresholdPx || abs(dx) <= abs(dy) * 1.25f) return EpubBoundaryAction.NONE
  return when {
    dx < -swipeThresholdPx && startedAtBottom && stillAtBottom && chapterIndex < totalChapters - 1 ->
      EpubBoundaryAction.NEXT_CHAPTER
    dx > swipeThresholdPx && startedAtTop && stillAtTop && chapterIndex > 0 ->
      EpubBoundaryAction.PREVIOUS_CHAPTER
    else -> EpubBoundaryAction.NONE
  }
}

data class EpubPaginationLayoutKey(
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

data class EpubHorizontalPosition(
  val chapterId: String,
  val chapterIndex: Int,
  val subPage: Int = 0,
  val anchorBlockIndex: Int = 0,
  val anchorCharOffsetInBlock: Int = 0,
  val chapterProgressFraction: Float = 0f,
  val hasSemanticAnchor: Boolean = false,
  val layoutKey: EpubPaginationLayoutKey? = null
)

data class EpubChapterAnchor(
  val chapterId: String,
  val chapterIndex: Int,
  val blockIndex: Int = 0,
  val charOffsetInBlock: Int = 0,
  val blockScrollFraction: Float = 0f,
  val chapterProgressFraction: Float = 0f
)

internal fun epubChapterUniqueId(
  chapterIndex: Int,
  zipPath: String,
  chapterStubs: List<EpubChapterStub> = emptyList()
): String {
  val normalized = zipPath.trim()
  if (normalized.isBlank()) return "chapter_$chapterIndex"
  val hasDuplicate = chapterStubs.count { it.zipPath.trim() == normalized } > 1
  return if (hasDuplicate) "$normalized#$chapterIndex" else normalized
}

internal fun epubChapterUniqueId(
  chapter: EpubChapter,
  chapterStubs: List<EpubChapterStub> = emptyList()
): String = epubChapterUniqueId(chapter.index, chapter.zipPath, chapterStubs)

internal fun resolveVerticalScrollRestoreTarget(
  saved: EpubVerticalScrollPosition?,
  blockCount: Int,
  currentLayout: EpubVerticalLayoutSignature,
  newItemHeightPx: Int? = null,
  newLineTopPx: Int? = null
): Pair<Int, Int> {
  if (saved == null || blockCount <= 0) return 0 to 0
  val targetBlock = saved.blockIndex.coerceIn(0, blockCount - 1)
  if (saved.layoutSignature == currentLayout) {
    return targetBlock to saved.scrollOffsetPx.coerceAtLeast(0)
  }
  if (saved.layoutSignature == null && saved.characterOffsetInBlock <= 0 && saved.blockScrollFraction <= 0f) {
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

private val HORIZONTAL_COVER_PATH_KEYWORDS = listOf(
  "cover",
  "portada",
  "cubierta",
  "titlepage",
  "title-page",
  "title_page",
  "frontcover",
  "jacket"
)

internal fun findHorizontalEpubCoverImageBlock(
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
    HORIZONTAL_COVER_PATH_KEYWORDS.any { keyword ->
      chapter.title.contains(keyword, ignoreCase = true) ||
        chapter.zipPath.contains(keyword, ignoreCase = true) ||
        singleImage.zipEntryPath.contains(keyword, ignoreCase = true)
    }

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

internal sealed interface PageSliceItem {
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

internal data class EpubRenderedPage(
  val items: List<PageSliceItem>
)

internal fun extractHorizontalAnchorForPage(
  pages: List<EpubRenderedPage>,
  pageIndex: Int
): Pair<Int, Int> {
  val page = pages.getOrNull(pageIndex) ?: return 0 to 0
  val firstItem = page.items.firstOrNull() ?: return 0 to 0
  return when (firstItem) {
    is PageSliceItem.HeadingSlice -> firstItem.blockIndex to 0
    is PageSliceItem.TextSlice -> firstItem.blockIndex to firstItem.startCharOffsetInBlock
    is PageSliceItem.QuoteSlice -> firstItem.blockIndex to firstItem.startCharOffsetInBlock
    is PageSliceItem.ImageSlice -> firstItem.blockIndex to 0
  }
}

internal fun resolveHorizontalPageRestoreTarget(
  saved: EpubHorizontalPosition?,
  pages: List<EpubRenderedPage>,
  currentLayoutKey: EpubPaginationLayoutKey
): Int {
  if (saved == null || pages.isEmpty()) return 0
  val lastIdx = pages.lastIndex
  if (!saved.hasSemanticAnchor || (saved.layoutKey != null && saved.layoutKey == currentLayoutKey)) {
    return saved.subPage.coerceIn(0, lastIdx)
  }

  val targetBlock = saved.anchorBlockIndex.coerceAtLeast(0)
  val targetChar = saved.anchorCharOffsetInBlock.coerceAtLeast(0)

  val exactIdx = pages.indexOfFirst { page ->
    page.items.any { item ->
      if (item.blockIndex != targetBlock) return@any false
      when (item) {
        is PageSliceItem.HeadingSlice -> true
        is PageSliceItem.ImageSlice -> true
        is PageSliceItem.TextSlice ->
          targetChar in item.startCharOffsetInBlock..(item.startCharOffsetInBlock + item.text.length)
        is PageSliceItem.QuoteSlice ->
          targetChar in item.startCharOffsetInBlock..(item.startCharOffsetInBlock + item.text.length)
      }
    }
  }
  if (exactIdx >= 0) return exactIdx

  val closestBeforeInBlock = pages.indexOfLast { page ->
    page.items.any { item ->
      if (item.blockIndex != targetBlock) return@any false
      val startOffset = when (item) {
        is PageSliceItem.TextSlice -> item.startCharOffsetInBlock
        is PageSliceItem.QuoteSlice -> item.startCharOffsetInBlock
        is PageSliceItem.HeadingSlice, is PageSliceItem.ImageSlice -> 0
      }
      startOffset <= targetChar
    }
  }
  if (closestBeforeInBlock >= 0) return closestBeforeInBlock

  val firstForBlockOrAfter = pages.indexOfFirst { page ->
    page.items.any { it.blockIndex >= targetBlock }
  }
  if (firstForBlockOrAfter >= 0) return firstForBlockOrAfter

  if (targetBlock == 0 && targetChar == 0 && saved.chapterProgressFraction > 0f) {
    return (saved.chapterProgressFraction.coerceIn(0f, 1f) * lastIdx.toFloat())
      .roundToInt()
      .coerceIn(0, lastIdx)
  }

  return saved.subPage.coerceIn(0, lastIdx)
}

@Composable
fun EpubReaderContent(
  metadata: EpubMetadata,
  chapterStubs: List<EpubChapterStub>,
  activeChapter: EpubChapter,
  loadedChapters: Map<Int, EpubChapter> = mapOf(activeChapter.index to activeChapter),
  scrollJumpRequestVersion: Long = 0L,
  initialSubPage: Int,
  initialHorizontalPosition: EpubHorizontalPosition? = null,
  horizontalChapterPositions: Map<String, EpubHorizontalPosition> = emptyMap(),
  initialVerticalPosition: EpubVerticalScrollPosition? = null,
  verticalChapterPositions: Map<String, EpubVerticalScrollPosition> = emptyMap(),
  preferences: ReaderPreferences,
  surfaceColors: ReaderSurfaceColors,
  imageCache: Map<String, Bitmap>,
  highlights: List<HighlightEntity>,
  ttsActiveBlockIndex: Int?,
  isAutoScrolling: Boolean,
  autoScrollSpeed: Float,
  topOverlayClearanceDp: Dp = 0.dp,
  onRequestImage: (String) -> Unit,
  onRequestChapter: (Int) -> Unit = {},
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
  onHorizontalPositionChanged: (
    chapter: EpubChapter,
    position: EpubHorizontalPosition,
    overallProgress: Float
  ) -> Unit = { _, _, _ -> },
  onVerticalPositionChanged: (
    chapter: EpubChapter,
    position: EpubVerticalScrollPosition,
    overallProgress: Float
  ) -> Unit = { _, _, _ -> },
  onSelectChapter: (chapterIndex: Int) -> Unit = { nextIdx ->
    val prog = (nextIdx + 1).toFloat() / chapterStubs.size.coerceAtLeast(1)
    onPositionChanged(nextIdx, 0, prog)
  },
  openAtEnd: Boolean = false,
  onSelectChapterBoundary: (chapterIndex: Int, openAtEnd: Boolean) -> Unit = { nextIdx, _ ->
    onSelectChapter(nextIdx)
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

  var activeSelection by remember(preferences.epubScrollMode) {
    mutableStateOf<EpubTextSelection?>(null)
  }

  LaunchedEffect(activeSelection, activeChapter.index, loadedChapters) {
    val sel = activeSelection
    if (sel != null) {
      val selChapter = loadedChapters[sel.chapterIndex]
        ?: if (sel.chapterIndex == activeChapter.index) activeChapter else null
      val txt = if (selChapter != null) extractSelectedEpubText(selChapter.blocks, sel) else ""
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

  val bookKey = remember(metadata.title, chapterStubs.size, chapterStubs.firstOrNull()?.zipPath) {
    "${metadata.title}_${chapterStubs.size}_${chapterStubs.firstOrNull()?.zipPath.orEmpty()}"
  }

  if (preferences.epubScrollMode) {
    key(bookKey) {
      EpubScrollReader(
        metadata = metadata,
        chapterStubs = chapterStubs,
        chapter = activeChapter,
        loadedChapters = loadedChapters,
        scrollJumpRequestVersion = scrollJumpRequestVersion,
        initialVerticalPosition = initialVerticalPosition,
        verticalChapterPositions = verticalChapterPositions,
        initialOpenAtEnd = openAtEnd,
        surfaceColors = surfaceColors,
        preferences = preferences,
        imageCache = imageCache,
        allHighlights = highlights,
        spokenIndexByBlock = spokenIndexByBlock,
        ttsActiveBlockIndex = ttsActiveBlockIndex,
        isAutoScrolling = isAutoScrolling,
        autoScrollSpeed = autoScrollSpeed,
        topOverlayClearanceDp = topOverlayClearanceDp,
        activeSelection = activeSelection,
        onSelectionChanged = { activeSelection = it },
        onRequestImage = onRequestImage,
        onRequestChapter = onRequestChapter,
        onToggleControls = onToggleControls,
        onOpenFragmentTools = onOpenFragmentTools,
        onCopyPlainText = onCopyPlainText,
        onShareSelection = onShareSelection,
        onVerticalPositionChanged = onVerticalPositionChanged,
        onVisibleBlockChanged = onVisibleBlockChanged,
        modifier = modifier
      )
    }
  } else {
    key(bookKey) {
      EpubPaginatedReader(
        metadata = metadata,
        chapterStubs = chapterStubs,
        chapter = activeChapter,
        loadedChapters = loadedChapters,
        scrollJumpRequestVersion = scrollJumpRequestVersion,
        initialSubPage = initialSubPage,
        initialHorizontalPosition = initialHorizontalPosition,
        horizontalChapterPositions = horizontalChapterPositions,
        initialOpenAtEnd = openAtEnd,
        pagesCache = pagesCache,
        surfaceColors = surfaceColors,
        preferences = preferences,
        imageCache = imageCache,
        allHighlights = highlights,
        spokenIndexByBlock = spokenIndexByBlock,
        ttsActiveBlockIndex = ttsActiveBlockIndex,
        topOverlayClearanceDp = topOverlayClearanceDp,
        activeSelection = activeSelection,
        onSelectionChanged = { activeSelection = it },
        onRequestImage = onRequestImage,
        onRequestChapter = onRequestChapter,
        onToggleControls = onToggleControls,
        onOpenFragmentTools = onOpenFragmentTools,
        onCopyPlainText = onCopyPlainText,
        onShareSelection = onShareSelection,
        onHorizontalPositionChanged = onHorizontalPositionChanged,
        onVisibleBlockChanged = onVisibleBlockChanged,
        modifier = modifier
      )
    }
  }
}

internal sealed interface EpubHorizontalPageSlot {
  val key: String
  val chapterIndex: Int

  data class CoverPageSlot(
    override val chapterIndex: Int,
    val chapter: EpubChapter,
    val blockIndex: Int,
    val coverImage: EpubBlock.Image
  ) : EpubHorizontalPageSlot {
    override val key: String = "h_ch_${chapterIndex}_cover"
  }

  data class ChapterPageSlot(
    override val chapterIndex: Int,
    val chapter: EpubChapter,
    val subPageIndex: Int,
    val chapterPageCount: Int,
    val page: EpubRenderedPage
  ) : EpubHorizontalPageSlot {
    override val key: String = "h_ch_${chapterIndex}_p_${subPageIndex}"
  }

  data class LoadingSlot(
    override val chapterIndex: Int,
    val title: String
  ) : EpubHorizontalPageSlot {
    override val key: String = "h_ch_${chapterIndex}_loading"
  }
}

internal fun buildHorizontalPageSlots(
  chapterStubs: List<EpubChapterStub>,
  loadedChapters: Map<Int, EpubChapter>,
  metadata: EpubMetadata,
  paginateChapter: (EpubChapter) -> List<EpubRenderedPage>
): List<EpubHorizontalPageSlot> {
  val slots = mutableListOf<EpubHorizontalPageSlot>()
  val indices = if (chapterStubs.isNotEmpty()) {
    chapterStubs.indices
  } else {
    val maxIdx = loadedChapters.keys.maxOrNull() ?: 0
    0..maxIdx
  }
  for (chIdx in indices) {
    val ch = loadedChapters[chIdx]
    if (ch == null) {
      val label = chapterStubs.getOrNull(chIdx)?.title ?: "Capítulo ${chIdx + 1}"
      slots.add(EpubHorizontalPageSlot.LoadingSlot(chapterIndex = chIdx, title = label))
    } else {
      val coverImg = findHorizontalEpubCoverImageBlock(ch, metadata)
      if (coverImg != null) {
        val coverBlkIdx = ch.blocks.indexOf(coverImg).coerceAtLeast(0)
        slots.add(
          EpubHorizontalPageSlot.CoverPageSlot(
            chapterIndex = chIdx,
            chapter = ch,
            blockIndex = coverBlkIdx,
            coverImage = coverImg
          )
        )
      } else {
        val chPages = paginateChapter(ch)
        val safeCount = chPages.size.coerceAtLeast(1)
        chPages.forEachIndexed { subIdx, renderedPage ->
          slots.add(
            EpubHorizontalPageSlot.ChapterPageSlot(
              chapterIndex = chIdx,
              chapter = ch,
              subPageIndex = subIdx,
              chapterPageCount = safeCount,
              page = renderedPage
            )
          )
        }
      }
    }
  }
  return slots
}

internal fun resolveHorizontalFlatPageIndex(
  slots: List<EpubHorizontalPageSlot>,
  chapterIndex: Int,
  subPageIndex: Int
): Int {
  if (slots.isEmpty()) return 0
  val chapterSlotIndices = slots.indices.filter { slots[it].chapterIndex == chapterIndex }
  if (chapterSlotIndices.isNotEmpty()) {
    val exact = chapterSlotIndices.firstOrNull { flatIdx ->
      when (val s = slots[flatIdx]) {
        is EpubHorizontalPageSlot.ChapterPageSlot -> s.subPageIndex == subPageIndex
        is EpubHorizontalPageSlot.CoverPageSlot, is EpubHorizontalPageSlot.LoadingSlot -> true
      }
    }
    if (exact != null) return exact
    return if (subPageIndex < 0) chapterSlotIndices.first() else chapterSlotIndices.last()
  }
  return slots.indexOfFirst { it.chapterIndex >= chapterIndex }.coerceAtLeast(0)
}

private fun pagesForChapterFromSlots(
  slots: List<EpubHorizontalPageSlot>,
  chapterIndex: Int
): List<EpubRenderedPage> =
  slots.mapNotNull { slot ->
    if (slot.chapterIndex == chapterIndex && slot is EpubHorizontalPageSlot.ChapterPageSlot) {
      slot.page
    } else {
      null
    }
  }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpubPaginatedReader(
  metadata: EpubMetadata,
  chapterStubs: List<EpubChapterStub>,
  chapter: EpubChapter,
  loadedChapters: Map<Int, EpubChapter>,
  scrollJumpRequestVersion: Long,
  initialSubPage: Int,
  initialHorizontalPosition: EpubHorizontalPosition?,
  horizontalChapterPositions: Map<String, EpubHorizontalPosition>,
  initialOpenAtEnd: Boolean,
  pagesCache: EpubPagesCache<EpubRenderedPage>,
  surfaceColors: ReaderSurfaceColors,
  preferences: ReaderPreferences,
  imageCache: Map<String, Bitmap>,
  allHighlights: List<HighlightEntity>,
  spokenIndexByBlock: Map<Int, Int>,
  ttsActiveBlockIndex: Int?,
  topOverlayClearanceDp: Dp,
  activeSelection: EpubTextSelection?,
  onSelectionChanged: (EpubTextSelection?) -> Unit,
  onRequestImage: (String) -> Unit,
  onRequestChapter: (Int) -> Unit,
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
  onHorizontalPositionChanged: (
    chapter: EpubChapter,
    position: EpubHorizontalPosition,
    overallProgress: Float
  ) -> Unit,
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
  val sliceLayouts = remember {
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
  val latestContainerTapHandler = remember { mutableStateOf<(Float) -> Unit>({}) }

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
          maxTapDurationMs = 350L,
          onTap = { local ->
            latestContainerTapHandler.value(overlayContainerPosInRoot.x + local.x)
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

    val effectiveIncomingLoaded = remember(loadedChapters, chapter) {
      if (loadedChapters.containsKey(chapter.index)) loadedChapters
      else loadedChapters + (chapter.index to chapter)
    }

    fun buildSlots(
      chaptersMap: Map<Int, EpubChapter>,
      targetLayout: EpubPaginationLayoutKey,
      bStyle: TextStyle,
      qStyle: TextStyle
    ): List<EpubHorizontalPageSlot> = buildHorizontalPageSlots(
      chapterStubs = chapterStubs,
      loadedChapters = chaptersMap,
      metadata = metadata
    ) { ch ->
      pagesCache.getOrCompute(ch.index, targetLayout) {
        paginateChapterBlocks(
          blocks = ch.blocks,
          textMeasurer = textMeasurer,
          bodyStyle = bStyle,
          quoteStyle = qStyle,
          maxWidthPx = targetLayout.availableWidthPx,
          maxHeightPx = targetLayout.availableHeightPx,
          quoteWidthPx = (targetLayout.availableWidthPx - quoteIndentPx).coerceAtLeast(120),
          paragraphGapPx = paragraphGapPx,
          imageHeightPx = imageHeightPx,
          density = density
        )
      }
    }

    val initialChapterId = remember { epubChapterUniqueId(chapter, chapterStubs) }
    var committedLoadedChapters by remember { mutableStateOf(effectiveIncomingLoaded) }
    var pageSlots by remember {
      mutableStateOf(buildSlots(effectiveIncomingLoaded, layoutKey, bodyStyle, quoteStyle))
    }

    val initialAnchor = remember {
      if (initialOpenAtEnd) {
        null
      } else {
        horizontalChapterPositions[initialChapterId]
          ?: initialHorizontalPosition
          ?: EpubHorizontalPosition(
            chapterId = initialChapterId,
            chapterIndex = chapter.index,
            subPage = initialSubPage.coerceAtLeast(0)
          )
      }
    }
    val startFlatPage = remember {
      val chPages = pagesForChapterFromSlots(pageSlots, chapter.index)
      val targetSub = if (initialOpenAtEnd) {
        (chPages.size - 1).coerceAtLeast(0)
      } else {
        resolveHorizontalPageRestoreTarget(
          saved = initialAnchor,
          pages = chPages,
          currentLayoutKey = layoutKey
        )
      }
      resolveHorizontalFlatPageIndex(pageSlots, chapter.index, targetSub)
    }

    val pagerState = rememberPagerState(initialPage = startFlatPage) { pageSlots.size.coerceAtLeast(1) }
    var lastAppliedLayoutKey by remember { mutableStateOf(layoutKey) }
    var activeAnchor by remember { mutableStateOf(initialAnchor) }
    var lastHandledJumpVersion by remember { mutableLongStateOf(scrollJumpRequestVersion) }
    var animatedTapTargetPage by remember { mutableStateOf<Int?>(null) }

    val latestChapter by rememberUpdatedState(chapter)
    val latestIncomingLoaded by rememberUpdatedState(effectiveIncomingLoaded)
    val latestHorizontalPositions by rememberUpdatedState(horizontalChapterPositions)
    val latestOpenAtEnd by rememberUpdatedState(initialOpenAtEnd)
    val latestBodyStyle by rememberUpdatedState(bodyStyle)
    val latestQuoteStyle by rememberUpdatedState(quoteStyle)
    val latestOnHorizontalPositionChanged by rememberUpdatedState(onHorizontalPositionChanged)
    val latestOnRequestChapter by rememberUpdatedState(onRequestChapter)
    val latestOnToggleControls by rememberUpdatedState(onToggleControls)

    val handleTapByRootX: (Float) -> Unit = { rootX ->
      if (latestSelection != null) {
        latestOnSelectionChanged(null)
      } else {
        val containerWidthPx = if (overlayContainerSize.width > 0) {
          overlayContainerSize.width.toFloat()
        } else {
          availableWidthPx.toFloat().coerceAtLeast(1f)
        }
        val xInContainer = (rootX - overlayContainerPosInRoot.x).coerceIn(0f, containerWidthPx)
        when (resolveEpubHorizontalTapAction(xInContainer, containerWidthPx)) {
          EpubHorizontalTapAction.PREVIOUS_PAGE -> {
            val basePage = animatedTapTargetPage ?: pagerState.currentPage
            val target = (basePage - 1).coerceAtLeast(0)
            if (target != pagerState.currentPage || (animatedTapTargetPage != null && animatedTapTargetPage != target)) {
              animatedTapTargetPage = target
              scope.launch {
                try {
                  pagerState.animateScrollToPage(
                    page = target,
                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
                  )
                } finally {
                  if (animatedTapTargetPage == target) {
                    animatedTapTargetPage = null
                  }
                }
              }
            }
          }
          EpubHorizontalTapAction.NEXT_PAGE -> {
            val lastIdx = pageSlots.lastIndex.coerceAtLeast(0)
            val basePage = animatedTapTargetPage ?: pagerState.currentPage
            val target = (basePage + 1).coerceAtMost(lastIdx)
            if (target != pagerState.currentPage || (animatedTapTargetPage != null && animatedTapTargetPage != target)) {
              animatedTapTargetPage = target
              scope.launch {
                try {
                  pagerState.animateScrollToPage(
                    page = target,
                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
                  )
                } finally {
                  if (animatedTapTargetPage == target) {
                    animatedTapTargetPage = null
                  }
                }
              }
            }
          }
          EpubHorizontalTapAction.TOGGLE_CONTROLS -> latestOnToggleControls()
        }
      }
    }
    val latestHandleTapByRootX by rememberUpdatedState(handleTapByRootX)
    latestContainerTapHandler.value = latestHandleTapByRootX

    fun buildPositionForSlot(
      slot: EpubHorizontalPageSlot,
      targetLayout: EpubPaginationLayoutKey
    ): Pair<EpubChapter, Pair<EpubHorizontalPosition, Float>>? {
      return when (slot) {
        is EpubHorizontalPageSlot.CoverPageSlot -> {
          val ch = slot.chapter
          val chId = epubChapterUniqueId(ch, chapterStubs)
          val chapterBase = ch.index.toFloat() / totalChapters.toFloat()
          val overall = (chapterBase + 1f / totalChapters.toFloat()).coerceIn(0f, 1f)
          ch to (
            EpubHorizontalPosition(
              chapterId = chId,
              chapterIndex = ch.index,
              subPage = 0,
              anchorBlockIndex = slot.blockIndex,
              anchorCharOffsetInBlock = 0,
              chapterProgressFraction = 1f,
              hasSemanticAnchor = true,
              layoutKey = targetLayout
            ) to overall
            )
        }
        is EpubHorizontalPageSlot.ChapterPageSlot -> {
          val ch = slot.chapter
          val chId = epubChapterUniqueId(ch, chapterStubs)
          val count = slot.chapterPageCount.coerceAtLeast(1)
          val safeSub = slot.subPageIndex.coerceIn(0, count - 1)
          val chPages = pagesForChapterFromSlots(pageSlots, ch.index)
          val (anchorBlock, anchorChar) = if (chPages.isNotEmpty()) {
            extractHorizontalAnchorForPage(chPages, safeSub)
          } else {
            val item = slot.page.items.firstOrNull()
            val blk = item?.blockIndex ?: 0
            val chr = when (item) {
              is PageSliceItem.TextSlice -> item.startCharOffsetInBlock
              is PageSliceItem.QuoteSlice -> item.startCharOffsetInBlock
              else -> 0
            }
            blk to chr
          }
          val inChapterFraction = ((safeSub + 1).toFloat() / count.toFloat()).coerceIn(0f, 1f)
          val chapterBase = ch.index.toFloat() / totalChapters.toFloat()
          val overall = (chapterBase + inChapterFraction / totalChapters.toFloat()).coerceIn(0f, 1f)
          ch to (
            EpubHorizontalPosition(
              chapterId = chId,
              chapterIndex = ch.index,
              subPage = safeSub,
              anchorBlockIndex = anchorBlock,
              anchorCharOffsetInBlock = anchorChar,
              chapterProgressFraction = inChapterFraction,
              hasSemanticAnchor = true,
              layoutKey = targetLayout
            ) to overall
            )
        }
        is EpubHorizontalPageSlot.LoadingSlot -> null
      }
    }

    // Re-paginate cleanly on layout/typography/viewport changes while keeping exact semantic anchor
    LaunchedEffect(layoutKey) {
      if (layoutKey == lastAppliedLayoutKey) return@LaunchedEffect
      val currentSlot = pageSlots.getOrNull(pagerState.currentPage.coerceIn(0, pageSlots.lastIndex.coerceAtLeast(0)))
      val targetChIdx = currentSlot?.chapterIndex ?: latestChapter.index
      val anchorToRestore = when {
        currentSlot != null -> buildPositionForSlot(currentSlot, lastAppliedLayoutKey)?.second?.first
        else -> null
      } ?: activeAnchor?.takeIf { it.chapterIndex == targetChIdx }
        ?: latestHorizontalPositions[epubChapterUniqueId(latestChapter, chapterStubs)]
        ?: initialAnchor

      val newSlots = buildSlots(committedLoadedChapters, layoutKey, latestBodyStyle, latestQuoteStyle)
      pageSlots = newSlots
      lastAppliedLayoutKey = layoutKey

      val targetChPages = pagesForChapterFromSlots(newSlots, targetChIdx)
      val resolvedSub = resolveHorizontalPageRestoreTarget(
        saved = anchorToRestore,
        pages = targetChPages,
        currentLayoutKey = layoutKey
      )
      val targetFlat = resolveHorizontalFlatPageIndex(newSlots, targetChIdx, resolvedSub)
      if (pagerState.currentPage != targetFlat) {
        pagerState.scrollToPage(targetFlat)
      }
      newSlots.getOrNull(targetFlat)?.let { settledSlot ->
        buildPositionForSlot(settledSlot, layoutKey)?.let { (ch, pair) ->
          val (updatedPos, overall) = pair
          activeAnchor = updatedPos
          latestOnHorizontalPositionChanged(ch, updatedPos, overall)
          onVisibleBlockChanged(updatedPos.anchorBlockIndex)
        }
      }
    }

    // Commit newly loaded neighbor chapters without shifting the visible page index mid-swipe
    LaunchedEffect(effectiveIncomingLoaded) {
      if (effectiveIncomingLoaded == committedLoadedChapters) return@LaunchedEffect
      if (pagerState.isScrollInProgress) {
        snapshotFlow { pagerState.isScrollInProgress }.first { !it }
      }
      val latestLoadedMap = latestIncomingLoaded
      if (latestLoadedMap == committedLoadedChapters) return@LaunchedEffect

      val oldSlot = pageSlots.getOrNull(pagerState.currentPage.coerceIn(0, pageSlots.lastIndex.coerceAtLeast(0)))
      val targetChIdx = oldSlot?.chapterIndex ?: latestChapter.index
      val targetSub = when (oldSlot) {
        is EpubHorizontalPageSlot.ChapterPageSlot -> oldSlot.subPageIndex
        is EpubHorizontalPageSlot.CoverPageSlot -> 0
        is EpubHorizontalPageSlot.LoadingSlot, null -> {
          val ch = latestLoadedMap[targetChIdx]
          if (ch != null) {
            val chId = epubChapterUniqueId(ch, chapterStubs)
            latestHorizontalPositions[chId]?.subPage ?: 0
          } else 0
        }
      }

      val newSlots = buildSlots(latestLoadedMap, layoutKey, latestBodyStyle, latestQuoteStyle)
      committedLoadedChapters = latestLoadedMap
      pageSlots = newSlots

      val resolvedSub = if (oldSlot is EpubHorizontalPageSlot.LoadingSlot && latestLoadedMap.containsKey(targetChIdx)) {
        val ch = latestLoadedMap.getValue(targetChIdx)
        val chId = epubChapterUniqueId(ch, chapterStubs)
        val chPages = pagesForChapterFromSlots(newSlots, targetChIdx)
        resolveHorizontalPageRestoreTarget(
          saved = latestHorizontalPositions[chId],
          pages = chPages,
          currentLayoutKey = layoutKey
        )
      } else {
        targetSub
      }
      val compensatedFlat = resolveHorizontalFlatPageIndex(newSlots, targetChIdx, resolvedSub)
      if (pagerState.currentPage != compensatedFlat) {
        pagerState.scrollToPage(compensatedFlat)
      }
    }

    // Handle explicit jumps (TOC, progress slider, bookmark, search hit, or mode switch)
    LaunchedEffect(scrollJumpRequestVersion) {
      if (scrollJumpRequestVersion == lastHandledJumpVersion) return@LaunchedEffect
      lastHandledJumpVersion = scrollJumpRequestVersion
      val targetCh = latestChapter
      val latestLoadedMap = latestIncomingLoaded
      if (latestLoadedMap != committedLoadedChapters) {
        committedLoadedChapters = latestLoadedMap
        pageSlots = buildSlots(latestLoadedMap, layoutKey, latestBodyStyle, latestQuoteStyle)
      }
      val chId = epubChapterUniqueId(targetCh, chapterStubs)
      val chPages = pagesForChapterFromSlots(pageSlots, targetCh.index)
      val targetSub = if (latestOpenAtEnd) {
        (chPages.size - 1).coerceAtLeast(0)
      } else {
        resolveHorizontalPageRestoreTarget(
          saved = latestHorizontalPositions[chId],
          pages = chPages,
          currentLayoutKey = layoutKey
        )
      }
      val targetFlat = resolveHorizontalFlatPageIndex(pageSlots, targetCh.index, targetSub)
      if (pagerState.currentPage != targetFlat) {
        pagerState.scrollToPage(targetFlat)
      }
      pageSlots.getOrNull(targetFlat)?.let { settledSlot ->
        buildPositionForSlot(settledSlot, layoutKey)?.let { (ch, pair) ->
          val (updatedPos, overall) = pair
          activeAnchor = updatedPos
          latestOnHorizontalPositionChanged(ch, updatedPos, overall)
          onVisibleBlockChanged(updatedPos.anchorBlockIndex)
        }
      }
    }

    // Keep active selection visible after layout changes
    LaunchedEffect(pageSlots) {
      val sel = activeSelection ?: return@LaunchedEffect
      val targetFlat = pageSlots.indexOfFirst { slot ->
        slot.chapterIndex == sel.chapterIndex &&
          slot is EpubHorizontalPageSlot.ChapterPageSlot &&
          slot.page.items.any { item ->
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
      if (targetFlat >= 0 && targetFlat != pagerState.currentPage) {
        pagerState.scrollToPage(targetFlat)
      }
    }

    // Track current slot, preload [visibleChapter - 3 .. visibleChapter + 3], and report semantic position
    LaunchedEffect(pagerState.currentPage, pageSlots, lastAppliedLayoutKey) {
      val currentFlat = pagerState.currentPage.coerceIn(0, pageSlots.lastIndex.coerceAtLeast(0))
      val currentSlot = pageSlots.getOrNull(currentFlat) ?: return@LaunchedEffect
      val start = (currentSlot.chapterIndex - 3).coerceAtLeast(0)
      val end = (currentSlot.chapterIndex + 3).coerceAtMost(totalChapters - 1)
      for (idx in start..end) {
        if (!committedLoadedChapters.containsKey(idx)) {
          latestOnRequestChapter(idx)
        }
      }
      if (lastAppliedLayoutKey != layoutKey) return@LaunchedEffect
      buildPositionForSlot(currentSlot, layoutKey)?.let { (ch, pair) ->
        val (pos, overall) = pair
        activeAnchor = pos
        latestOnHorizontalPositionChanged(ch, pos, overall)
        onVisibleBlockChanged(pos.anchorBlockIndex)
      }
    }

    DisposableEffect(Unit) {
      onDispose {
        if (pageSlots.isNotEmpty() && lastAppliedLayoutKey == layoutKey) {
          val settledFlat = pagerState.currentPage.coerceIn(0, pageSlots.lastIndex)
          pageSlots.getOrNull(settledFlat)?.let { settledSlot ->
            buildPositionForSlot(settledSlot, layoutKey)?.let { (ch, pair) ->
              latestOnHorizontalPositionChanged(ch, pair.first, pair.second)
            }
          }
        }
      }
    }

    LaunchedEffect(pagerState.currentPage) {
      val sel = activeSelection ?: return@LaunchedEffect
      val slot = pageSlots.getOrNull(pagerState.currentPage) ?: return@LaunchedEffect
      val overlapsCurrentPage = slot.chapterIndex == sel.chapterIndex &&
        slot is EpubHorizontalPageSlot.ChapterPageSlot &&
        slot.page.items.any { item ->
          item.blockIndex in sel.start.blockIndex..sel.end.blockIndex
        }
      if (!overlapsCurrentPage) {
        onSelectionChanged(null)
      }
    }

    LaunchedEffect(ttsActiveBlockIndex, chapter.index, pageSlots) {
      val activeSpoken = ttsActiveBlockIndex ?: return@LaunchedEffect
      val targetBlockIdx = spokenIndexByBlock.entries.firstOrNull { it.value == activeSpoken }?.key
        ?: return@LaunchedEffect
      val targetFlatIdx = pageSlots.indexOfFirst { slot ->
        slot.chapterIndex == chapter.index &&
          slot is EpubHorizontalPageSlot.ChapterPageSlot &&
          slot.page.items.any { it.blockIndex == targetBlockIdx }
      }
      if (targetFlatIdx >= 0 && targetFlatIdx != pagerState.currentPage) {
        pagerState.animateScrollToPage(targetFlatIdx)
      }
    }

    val currentFlatClamped = pagerState.currentPage.coerceIn(0, pageSlots.lastIndex.coerceAtLeast(0))
    val currentSlot = pageSlots.getOrNull(currentFlatClamped)
    val visibleChapter = when (currentSlot) {
      is EpubHorizontalPageSlot.CoverPageSlot -> currentSlot.chapter
      is EpubHorizontalPageSlot.ChapterPageSlot -> currentSlot.chapter
      else -> chapter
    }
    val visibleChapterTitle = when (currentSlot) {
      is EpubHorizontalPageSlot.CoverPageSlot -> currentSlot.chapter.title
      is EpubHorizontalPageSlot.ChapterPageSlot -> currentSlot.chapter.title
      is EpubHorizontalPageSlot.LoadingSlot -> currentSlot.title
      null -> chapter.title
    }
    val visibleSubPage = when (currentSlot) {
      is EpubHorizontalPageSlot.ChapterPageSlot -> currentSlot.subPageIndex
      else -> 0
    }
    val visibleChapterPageCount = when (currentSlot) {
      is EpubHorizontalPageSlot.ChapterPageSlot -> currentSlot.chapterPageCount
      else -> 1
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
          text = visibleChapterTitle,
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
      ) {
        CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
          HorizontalPager(
            state = pagerState,
            key = { idx -> pageSlots.getOrNull(idx)?.key ?: "h_empty_$idx" },
            userScrollEnabled = activeSelection == null,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize()
          ) { flatIdx ->
            val slot = pageSlots.getOrNull(flatIdx) ?: return@HorizontalPager
            var pageBoxPosInRoot by remember { mutableStateOf(Offset.Zero) }

            Box(
              modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coords ->
                  pageBoxPosInRoot = coords.positionInRoot()
                }
                .pointerInput(slot.key) {
                  detectSingleFingerTapAndLongPress(
                    maxTapDurationMs = 350L,
                    onTap = { offset -> latestHandleTapByRootX(pageBoxPosInRoot.x + offset.x) }
                  )
                }
                .padding(horizontal = horizontalPad, vertical = 6.dp),
              contentAlignment = when (slot) {
                is EpubHorizontalPageSlot.CoverPageSlot, is EpubHorizontalPageSlot.LoadingSlot -> Alignment.Center
                is EpubHorizontalPageSlot.ChapterPageSlot -> Alignment.TopStart
              }
            ) {
              when (slot) {
                is EpubHorizontalPageSlot.LoadingSlot -> {
                  LaunchedEffect(slot.chapterIndex) {
                    latestOnRequestChapter(slot.chapterIndex)
                  }
                  Text(
                    text = slot.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = surfaceColors.secondaryText.copy(alpha = 0.65f)
                  )
                }

                is EpubHorizontalPageSlot.CoverPageSlot -> {
                  val coverImageBlock = slot.coverImage
                  LaunchedEffect(coverImageBlock.zipEntryPath) {
                    onRequestImage(coverImageBlock.zipEntryPath)
                  }
                  val bmp = imageCache[coverImageBlock.zipEntryPath]
                  BoxWithConstraints(
                    modifier = Modifier
                      .fillMaxSize()
                      .padding(vertical = 14.dp)
                      .testTag("epub_horizontal_cover_container"),
                    contentAlignment = Alignment.Center
                  ) {
                    if (bmp != null) {
                      val (targetW, targetH) = computeFittedCoverDimensions(
                        imageWidthPx = bmp.width,
                        imageHeightPx = bmp.height,
                        maxWidthDp = maxWidth.value,
                        maxHeightDp = maxHeight.value
                      )
                      Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = coverImageBlock.alt.ifBlank { "Portada del libro" },
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                          .size(width = targetW.dp, height = targetH.dp)
                          .clip(RoundedCornerShape(10.dp))
                          .testTag("epub_horizontal_cover_image")
                      )
                    }
                  }
                }

                is EpubHorizontalPageSlot.ChapterPageSlot -> {
                  val slotChapter = slot.chapter
                  val slotHighlights = remember(allHighlights, slot.chapterIndex) {
                    allHighlights.filter { it.unitIndex == slot.chapterIndex && it.blockIndex >= 0 }
                  }
                  val scopeId = slot.chapterIndex * 10_000 + slot.subPageIndex
                  Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                  ) {
                    slot.page.items.forEach { item ->
                      val blockHighlights = remember(slotHighlights, item.blockIndex) {
                        slotHighlights.filter { hl ->
                          val endBlk = if (hl.endBlockIndex >= hl.blockIndex) hl.endBlockIndex else hl.blockIndex
                          item.blockIndex in hl.blockIndex..endBlk
                        }
                      }
                      val spokenIdx = if (slot.chapterIndex == chapter.index) spokenIndexByBlock[item.blockIndex] else null
                      val isTtsActive = ttsActiveBlockIndex != null && spokenIdx == ttsActiveBlockIndex

                      when (item) {
                        is PageSliceItem.HeadingSlice -> {
                          val headingSize = when (item.level) {
                            1 -> (preferences.fontSizeSp + 8f).sp
                            2 -> (preferences.fontSizeSp + 4f).sp
                            else -> (preferences.fontSizeSp + 2f).sp
                          }
                          val sliceKey = EpubSliceKey(scopeId, item.blockIndex, 0)
                          SelectableTextBlockWrapper(
                            sliceKey = sliceKey,
                            startCharInBlock = 0,
                            rawText = item.text,
                            fullBlockText = item.text,
                            activeSelection = activeSelection,
                            chapterIndex = slot.chapterIndex,
                            selectionColor = surfaceColors.accent.copy(alpha = 0.28f),
                            savedHighlights = blockHighlights,
                            isDarkTheme = surfaceColors.isDark,
                            isTtsActive = isTtsActive,
                            accentColor = surfaceColors.accent,
                            sliceLayouts = sliceLayouts,
                            onTapXInPage = latestHandleTapByRootX,
                            onSelectWord = { charOffset ->
                              val range = expandWordRangeAtOffset(item.text, charOffset)
                              onSelectionChanged(
                                EpubTextSelection(
                                  chapterIndex = slot.chapterIndex,
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
                          val sliceKey = EpubSliceKey(scopeId, item.blockIndex, item.startCharOffsetInBlock)
                          val fullBlockText = (slotChapter.blocks.getOrNull(item.blockIndex) as? EpubBlock.Paragraph)?.content?.text
                            ?: item.fullBlockText
                          SelectableTextBlockWrapper(
                            sliceKey = sliceKey,
                            startCharInBlock = item.startCharOffsetInBlock,
                            rawText = item.text.text,
                            fullBlockText = fullBlockText,
                            activeSelection = activeSelection,
                            chapterIndex = slot.chapterIndex,
                            selectionColor = surfaceColors.accent.copy(alpha = 0.28f),
                            savedHighlights = blockHighlights,
                            isDarkTheme = surfaceColors.isDark,
                            isTtsActive = isTtsActive,
                            accentColor = surfaceColors.accent,
                            sliceLayouts = sliceLayouts,
                            onTapXInPage = latestHandleTapByRootX,
                            onSelectWord = { localChar ->
                              val absOffset = (item.startCharOffsetInBlock + localChar).coerceIn(0, (fullBlockText.length - 1).coerceAtLeast(0))
                              val range = expandWordRangeAtOffset(fullBlockText, absOffset)
                              onSelectionChanged(
                                EpubTextSelection(
                                  chapterIndex = slot.chapterIndex,
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
                          val sliceKey = EpubSliceKey(scopeId, item.blockIndex, item.startCharOffsetInBlock)
                          val fullBlockText = (slotChapter.blocks.getOrNull(item.blockIndex) as? EpubBlock.Quote)?.content?.text
                            ?: item.fullBlockText
                          SelectableTextBlockWrapper(
                            sliceKey = sliceKey,
                            startCharInBlock = item.startCharOffsetInBlock,
                            rawText = item.text.text,
                            fullBlockText = fullBlockText,
                            activeSelection = activeSelection,
                            chapterIndex = slot.chapterIndex,
                            selectionColor = surfaceColors.accent.copy(alpha = 0.28f),
                            savedHighlights = blockHighlights,
                            isDarkTheme = surfaceColors.isDark,
                            isTtsActive = isTtsActive,
                            accentColor = surfaceColors.accent,
                            sliceLayouts = sliceLayouts,
                            onTapXInPage = latestHandleTapByRootX,
                            onSelectWord = { localChar ->
                              val absOffset = (item.startCharOffsetInBlock + localChar).coerceIn(0, (fullBlockText.length - 1).coerceAtLeast(0))
                              val range = expandWordRangeAtOffset(fullBlockText, absOffset)
                              onSelectionChanged(
                                EpubTextSelection(
                                  chapterIndex = slot.chapterIndex,
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
          text = "Pág. ${visibleSubPage + 1} de $visibleChapterPageCount",
          style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
          color = surfaceColors.secondaryText,
          modifier = Modifier.testTag("epub_footer_page_counter")
        )
      }
    }

    val selChapterIdx = activeSelection?.chapterIndex ?: visibleChapter.index
    val selChapter = committedLoadedChapters[selChapterIdx] ?: visibleChapter
    val currentScopeId = visibleChapter.index * 10_000 + visibleSubPage
    val currentPageSlices = sliceLayouts.values.filter { it.key.pageOrScopeId == currentScopeId }
    val geometry = computeEpubSelectionGeometry(
      selection = activeSelection,
      chapterIndex = selChapterIdx,
      visibleSlices = currentPageSlices,
      containerPosInRoot = overlayContainerPosInRoot
    )
    val selectedText = remember(activeSelection, selChapter.blocks) {
      if (activeSelection != null && activeSelection.chapterIndex == selChapterIdx) {
        extractSelectedEpubText(selChapter.blocks, activeSelection)
      } else ""
    }
    val locationLabel = "cap. ${visibleChapter.index + 1}, pág. ${visibleSubPage + 1}"

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
          val fallbackBlock = (currentSlot as? EpubHorizontalPageSlot.ChapterPageSlot)
            ?.page?.items?.firstOrNull()?.blockIndex ?: 0
          onSelectionChanged(
            expandEpubSelectionToParagraph(
              blocks = selChapter.blocks,
              selection = activeSelection,
              fallbackBlockIndex = fallbackBlock,
              chapterIndex = selChapterIdx
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
          maxTapDurationMs = 350L,
          onLongPress = { localOffset ->
            val layout = latestLayout
            val localChar = if (layout != null && rawText.isNotEmpty()) {
              layout.getOffsetForPosition(localOffset).coerceIn(0, rawText.lastIndex)
            } else 0
            latestOnSelectWord(localChar)
          },
          onTap = { local ->
            val tapX = if (latestPosInRoot != Offset.Zero) {
              latestPosInRoot.x + local.x
            } else {
              blockOffsetX + local.x
            }
            latestOnTapXInPage(tapX)
          }
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

internal sealed interface EpubVerticalFeedEntry {
  val key: String
  val chapterIndex: Int

  data class ChapterDivider(override val chapterIndex: Int) : EpubVerticalFeedEntry {
    override val key: String = "ch_divider_$chapterIndex"
  }

  data class CoverPage(
    override val chapterIndex: Int,
    val blockIndex: Int,
    val imageBlock: EpubBlock.Image
  ) : EpubVerticalFeedEntry {
    override val key: String = "ch_${chapterIndex}_cover"
  }

  data class BlockEntry(
    override val chapterIndex: Int,
    val blockIndex: Int,
    val block: EpubBlock
  ) : EpubVerticalFeedEntry {
    override val key: String = "ch_${chapterIndex}_blk_$blockIndex"
  }

  data class LoadingPlaceholder(
    override val chapterIndex: Int,
    val title: String
  ) : EpubVerticalFeedEntry {
    override val key: String = "ch_loading_$chapterIndex"
  }
}

internal fun buildVerticalScrollFeedEntries(
  chapterStubs: List<EpubChapterStub>,
  loadedChapters: Map<Int, EpubChapter>,
  metadata: EpubMetadata
): List<EpubVerticalFeedEntry> {
  val entries = mutableListOf<EpubVerticalFeedEntry>()
  val indices = if (chapterStubs.isNotEmpty()) {
    chapterStubs.indices
  } else {
    val maxIdx = loadedChapters.keys.maxOrNull() ?: 0
    0..maxIdx
  }
  for (chIdx in indices) {
    if (chIdx > 0) {
      entries.add(EpubVerticalFeedEntry.ChapterDivider(chapterIndex = chIdx))
    }
    val ch = loadedChapters[chIdx]
    if (ch == null) {
      val label = chapterStubs.getOrNull(chIdx)?.title ?: "Capítulo ${chIdx + 1}"
      entries.add(EpubVerticalFeedEntry.LoadingPlaceholder(chapterIndex = chIdx, title = label))
    } else {
      val coverImg = findVerticalEpubCoverImageBlock(ch, metadata)
      if (coverImg != null) {
        val coverBlkIdx = ch.blocks.indexOf(coverImg).coerceAtLeast(0)
        entries.add(EpubVerticalFeedEntry.CoverPage(chIdx, coverBlkIdx, coverImg))
      } else {
        ch.blocks.forEachIndexed { blkIdx, block ->
          entries.add(EpubVerticalFeedEntry.BlockEntry(chIdx, blkIdx, block))
        }
      }
    }
  }
  return entries
}

internal fun resolveVerticalFeedIndex(
  feedEntries: List<EpubVerticalFeedEntry>,
  chapterIndex: Int,
  blockIndex: Int
): Int {
  if (feedEntries.isEmpty()) return 0
  val exactBlock = feedEntries.indexOfFirst { entry ->
    entry.chapterIndex == chapterIndex && when (entry) {
      is EpubVerticalFeedEntry.CoverPage -> true
      is EpubVerticalFeedEntry.BlockEntry -> entry.blockIndex == blockIndex
      is EpubVerticalFeedEntry.ChapterDivider, is EpubVerticalFeedEntry.LoadingPlaceholder -> false
    }
  }
  if (exactBlock >= 0) return exactBlock

  val firstContentOfChapter = feedEntries.indexOfFirst { entry ->
    entry.chapterIndex == chapterIndex && entry !is EpubVerticalFeedEntry.ChapterDivider
  }
  if (firstContentOfChapter >= 0) return firstContentOfChapter

  return feedEntries.indexOfFirst { it.chapterIndex >= chapterIndex }.coerceAtLeast(0)
}

internal const val VERTICAL_EPUB_FLING_VELOCITY_SCALE = 0.68f

internal fun scaleVerticalFlingVelocity(
  rawVelocity: Float,
  factor: Float = VERTICAL_EPUB_FLING_VELOCITY_SCALE
): Float = rawVelocity * factor.coerceIn(0.1f, 1f)

@Composable
private fun rememberDampedVerticalFlingBehavior(
  velocityScale: Float = VERTICAL_EPUB_FLING_VELOCITY_SCALE
): FlingBehavior {
  val baseFling = ScrollableDefaults.flingBehavior()
  return remember(baseFling, velocityScale) {
    object : FlingBehavior {
      override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        return with(baseFling) {
          performFling(scaleVerticalFlingVelocity(initialVelocity, velocityScale))
        }
      }
    }
  }
}

@Composable
private fun EpubScrollReader(
  metadata: EpubMetadata,
  chapterStubs: List<EpubChapterStub>,
  chapter: EpubChapter,
  loadedChapters: Map<Int, EpubChapter>,
  scrollJumpRequestVersion: Long,
  initialVerticalPosition: EpubVerticalScrollPosition?,
  verticalChapterPositions: Map<String, EpubVerticalScrollPosition>,
  initialOpenAtEnd: Boolean,
  surfaceColors: ReaderSurfaceColors,
  preferences: ReaderPreferences,
  imageCache: Map<String, Bitmap>,
  allHighlights: List<HighlightEntity>,
  spokenIndexByBlock: Map<Int, Int>,
  ttsActiveBlockIndex: Int?,
  isAutoScrolling: Boolean,
  autoScrollSpeed: Float,
  topOverlayClearanceDp: Dp,
  activeSelection: EpubTextSelection?,
  onSelectionChanged: (EpubTextSelection?) -> Unit,
  onRequestImage: (String) -> Unit,
  onRequestChapter: (Int) -> Unit,
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
  onVisibleBlockChanged: (Int) -> Unit,
  modifier: Modifier = Modifier
) {
  val totalChapters = chapterStubs.size.coerceAtLeast(1)
  val effectiveLoadedChapters = remember(loadedChapters, chapter) {
    if (loadedChapters.containsKey(chapter.index)) loadedChapters
    else loadedChapters + (chapter.index to chapter)
  }
  val feedEntries = remember(chapterStubs, effectiveLoadedChapters, metadata.coverZipPath) {
    buildVerticalScrollFeedEntries(chapterStubs, effectiveLoadedChapters, metadata)
  }
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

  val initialChapterId = remember { epubChapterUniqueId(chapter, chapterStubs) }
  val initialScrollTarget = remember {
    val saved = verticalChapterPositions[initialChapterId] ?: initialVerticalPosition
    if (initialOpenAtEnd) {
      val lastBlk = chapter.blocks.lastIndex.coerceAtLeast(0)
      resolveVerticalFeedIndex(feedEntries, chapter.index, lastBlk) to 0
    } else {
      val (blk, off) = resolveVerticalScrollRestoreTarget(
        saved = saved,
        blockCount = chapter.blocks.size,
        currentLayout = layoutSignature
      )
      resolveVerticalFeedIndex(feedEntries, chapter.index, blk) to off
    }
  }
  val listState = rememberLazyListState(
    initialFirstVisibleItemIndex = initialScrollTarget.first,
    initialFirstVisibleItemScrollOffset = initialScrollTarget.second
  )
  val density = LocalDensity.current

  var overlayContainerSize by remember { mutableStateOf(IntSize.Zero) }
  var overlayContainerPosInRoot by remember { mutableStateOf(Offset.Zero) }
  val sliceLayouts = remember {
    mutableStateMapOf<EpubSliceKey, EpubSliceLayoutInfo>()
  }

  var lastAppliedLayoutSignature by remember {
    mutableStateOf(
      if (initialOpenAtEnd) layoutSignature
      else ((verticalChapterPositions[initialChapterId] ?: initialVerticalPosition)?.layoutSignature ?: layoutSignature)
    )
  }
  var activeAnchor by remember {
    mutableStateOf(
      if (initialOpenAtEnd) null
      else (verticalChapterPositions[initialChapterId] ?: initialVerticalPosition)
    )
  }
  var pendingLayoutRestoreAnchor by remember {
    val initialSaved = verticalChapterPositions[initialChapterId] ?: initialVerticalPosition
    mutableStateOf(
      if (!initialOpenAtEnd &&
        initialSaved != null &&
        initialSaved.layoutSignature != null &&
        initialSaved.layoutSignature != layoutSignature
      ) {
        initialSaved
      } else {
        null
      }
    )
  }

  val latestFeedEntries by rememberUpdatedState(feedEntries)
  val latestLoadedChapters by rememberUpdatedState(effectiveLoadedChapters)
  val latestChapter by rememberUpdatedState(chapter)
  val latestVerticalPositions by rememberUpdatedState(verticalChapterPositions)
  val latestOpenAtEnd by rememberUpdatedState(initialOpenAtEnd)
  val latestOnVerticalPositionChanged by rememberUpdatedState(onVerticalPositionChanged)
  val latestOnRequestChapter by rememberUpdatedState(onRequestChapter)
  val latestSelection by rememberUpdatedState(activeSelection)
  val latestOnSelectionChanged by rememberUpdatedState(onSelectionChanged)
  val latestOnToggleControls by rememberUpdatedState(onToggleControls)

  fun captureCurrentVerticalPosition(): Pair<EpubChapter, EpubVerticalScrollPosition>? {
    val entries = latestFeedEntries
    if (entries.isEmpty()) return null
    val firstIdx = listState.firstVisibleItemIndex.coerceIn(0, entries.lastIndex)
    val entry = entries[firstIdx]
    val targetChIdx = entry.chapterIndex
    val ch = latestLoadedChapters[targetChIdx] ?: return null
    val chId = epubChapterUniqueId(ch, chapterStubs)
    val blkIdx = when (entry) {
      is EpubVerticalFeedEntry.BlockEntry -> entry.blockIndex
      is EpubVerticalFeedEntry.CoverPage -> entry.blockIndex
      is EpubVerticalFeedEntry.ChapterDivider, is EpubVerticalFeedEntry.LoadingPlaceholder -> 0
    }.coerceIn(0, (ch.blocks.size - 1).coerceAtLeast(0))
    val firstOffset = listState.firstVisibleItemScrollOffset.coerceAtLeast(0)
    val visibleItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == firstIdx }
    val itemHeight = visibleItem?.size?.coerceAtLeast(1) ?: 0
    val fraction = if (itemHeight > 0) (firstOffset.toFloat() / itemHeight.toFloat()).coerceIn(0f, 1f) else 0f
    val layoutResult = sliceLayouts[EpubSliceKey(targetChIdx, blkIdx, 0)]?.layoutResult
    val charOffset = if (layoutResult != null && layoutResult.layoutInput.text.isNotEmpty() && firstOffset > 0) {
      val line = layoutResult.getLineForVerticalPosition(firstOffset.toFloat().coerceAtLeast(0f))
      layoutResult.getLineStart(line).coerceIn(0, layoutResult.layoutInput.text.length - 1)
    } else {
      val rawLen = epubBlockText(ch.blocks.getOrNull(blkIdx)).length
      if (rawLen > 0) (fraction * rawLen).roundToInt().coerceIn(0, rawLen - 1) else 0
    }
    return ch to EpubVerticalScrollPosition(
      chapterId = chId,
      chapterIndex = targetChIdx,
      blockIndex = blkIdx,
      scrollOffsetPx = firstOffset,
      blockScrollFraction = fraction,
      characterOffsetInBlock = charOffset,
      layoutSignature = lastAppliedLayoutSignature
    )
  }

  fun computeOverallProgress(targetCh: EpubChapter, blockIndex: Int, isCover: Boolean): Float {
    val totalBlocks = targetCh.blocks.size.coerceAtLeast(1)
    val chapterBase = targetCh.index.toFloat() / totalChapters.toFloat()
    val atGlobalEnd = !listState.canScrollForward && targetCh.index >= totalChapters - 1
    val inChapterFraction = if (isCover || atGlobalEnd) {
      1f
    } else {
      ((blockIndex + 1).toFloat() / totalBlocks.toFloat()).coerceIn(0f, 1f)
    }
    return (chapterBase + inChapterFraction / totalChapters.toFloat()).coerceIn(0f, 1f)
  }

  var lastHandledJumpVersion by remember { mutableLongStateOf(scrollJumpRequestVersion) }
  var hasCompletedInitialLayout by remember { mutableStateOf(false) }

  // Handle explicit chapter jumps (TOC, progress slider, bookmark, search hit, or mode switch)
  LaunchedEffect(scrollJumpRequestVersion) {
    if (scrollJumpRequestVersion == lastHandledJumpVersion) return@LaunchedEffect
    lastHandledJumpVersion = scrollJumpRequestVersion
    val targetCh = latestChapter
    val chId = epubChapterUniqueId(targetCh, chapterStubs)
    val saved = latestVerticalPositions[chId]
    val (targetBlk, targetOff) = if (latestOpenAtEnd) {
      targetCh.blocks.lastIndex.coerceAtLeast(0) to 0
    } else {
      resolveVerticalScrollRestoreTarget(
        saved = saved,
        blockCount = targetCh.blocks.size,
        currentLayout = layoutSignature
      )
    }
    val flatIdx = resolveVerticalFeedIndex(latestFeedEntries, targetCh.index, targetBlk)
    listState.scrollToItem(flatIdx, targetOff)
    withFrameNanos { }
    val captured = captureCurrentVerticalPosition()
    if (captured != null) {
      val (ch, pos) = captured
      val settled = pos.copy(layoutSignature = layoutSignature)
      activeAnchor = settled
      lastAppliedLayoutSignature = layoutSignature
      pendingLayoutRestoreAnchor = null
      latestOnVerticalPositionChanged(
        ch,
        settled,
        computeOverallProgress(ch, settled.blockIndex, findVerticalEpubCoverImageBlock(ch, metadata) != null)
      )
    }
  }

  LaunchedEffect(layoutSignature) {
    if (layoutSignature != lastAppliedLayoutSignature && pendingLayoutRestoreAnchor == null) {
      val captured = captureCurrentVerticalPosition()
      pendingLayoutRestoreAnchor = activeAnchor ?: captured?.second ?: EpubVerticalScrollPosition(
        chapterId = epubChapterUniqueId(latestChapter, chapterStubs),
        chapterIndex = latestChapter.index,
        blockIndex = 0,
        scrollOffsetPx = 0,
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

  LaunchedEffect(pendingLayoutRestoreAnchor, layoutSignature) {
    val anchor = pendingLayoutRestoreAnchor ?: return@LaunchedEffect
    val targetCh = latestLoadedChapters[anchor.chapterIndex] ?: latestChapter
    if (targetCh.blocks.isEmpty()) {
      lastAppliedLayoutSignature = layoutSignature
      pendingLayoutRestoreAnchor = null
      hasCompletedInitialLayout = true
      return@LaunchedEffect
    }
    val targetBlock = anchor.blockIndex.coerceIn(0, targetCh.blocks.lastIndex)
    val initialFlat = resolveVerticalFeedIndex(latestFeedEntries, targetCh.index, targetBlock)
    if (listState.firstVisibleItemIndex != initialFlat) {
      listState.scrollToItem(initialFlat, 0)
    }
    var itemHeightPx: Int? = null
    var lineTopPx: Int? = null
    repeat(6) {
      withFrameNanos { }
      val currentFlat = resolveVerticalFeedIndex(latestFeedEntries, targetCh.index, targetBlock)
      itemHeightPx = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == currentFlat }?.size
      val textLayout = sliceLayouts[EpubSliceKey(targetCh.index, targetBlock, 0)]?.layoutResult
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
      blockCount = targetCh.blocks.size,
      currentLayout = layoutSignature,
      newItemHeightPx = itemHeightPx,
      newLineTopPx = lineTopPx
    )
    val resolvedFlat = resolveVerticalFeedIndex(latestFeedEntries, targetCh.index, resolvedBlock)
    listState.scrollToItem(resolvedFlat, resolvedOffset)
    withFrameNanos { }
    lastAppliedLayoutSignature = layoutSignature
    val captured = captureCurrentVerticalPosition()
    if (captured != null) {
      val (ch, pos) = captured
      val updated = pos.copy(layoutSignature = layoutSignature)
      activeAnchor = updated
      pendingLayoutRestoreAnchor = null
      hasCompletedInitialLayout = true
      latestOnVerticalPositionChanged(
        ch,
        updated,
        computeOverallProgress(ch, updated.blockIndex, findVerticalEpubCoverImageBlock(ch, metadata) != null)
      )
    } else {
      pendingLayoutRestoreAnchor = null
      hasCompletedInitialLayout = true
    }
  }

  // Track visible chapter/block and preload nearby chapters seamlessly in both directions
  LaunchedEffect(listState) {
    snapshotFlow {
      val visibleCount = listState.layoutInfo.visibleItemsInfo.size
      listOf(
        listState.firstVisibleItemIndex,
        listState.firstVisibleItemScrollOffset,
        if (listState.isScrollInProgress) 1 else 0,
        visibleCount
      )
    }.collect { snapshot ->
      val firstIdx = snapshot[0]
      val isScrolling = snapshot[2] == 1
      val visibleCount = snapshot[3]
      val entry = latestFeedEntries.getOrNull(firstIdx)
      if (entry != null) {
        val start = (entry.chapterIndex - 3).coerceAtLeast(0)
        val end = (entry.chapterIndex + 3).coerceAtMost(totalChapters - 1)
        for (idx in start..end) {
          if (!latestLoadedChapters.containsKey(idx)) {
            latestOnRequestChapter(idx)
          }
        }
      }
      if (visibleCount <= 0) return@collect
      if (pendingLayoutRestoreAnchor != null || lastAppliedLayoutSignature != layoutSignature) return@collect

      hasCompletedInitialLayout = true
      val captured = captureCurrentVerticalPosition() ?: return@collect
      val (visibleChapter, pos) = captured
      onVisibleBlockChanged(pos.blockIndex)
      activeAnchor = pos
      if (!isScrolling || pos.blockIndex > 0 || pos.scrollOffsetPx > 0 || visibleChapter.index != latestChapter.index) {
        val isCover = entry is EpubVerticalFeedEntry.CoverPage
        latestOnVerticalPositionChanged(
          visibleChapter,
          pos,
          computeOverallProgress(visibleChapter, pos.blockIndex, isCover)
        )
      }
    }
  }

  DisposableEffect(Unit) {
    onDispose {
      if (pendingLayoutRestoreAnchor == null &&
        lastAppliedLayoutSignature == layoutSignature &&
        hasCompletedInitialLayout &&
        listState.layoutInfo.visibleItemsInfo.isNotEmpty()
      ) {
        val captured = captureCurrentVerticalPosition()
        if (captured != null) {
          val (ch, finalPos) = captured
          latestOnVerticalPositionChanged(
            ch,
            finalPos,
            computeOverallProgress(ch, finalPos.blockIndex, findVerticalEpubCoverImageBlock(ch, metadata) != null)
          )
        }
      }
    }
  }

  LaunchedEffect(isAutoScrolling, autoScrollSpeed) {
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
      val flatIdx = resolveVerticalFeedIndex(latestFeedEntries, chapter.index, blockIdx)
      listState.animateScrollToItem(flatIdx)
    }
  }

  val bodyFontFamily = preferences.readerFont.fontFamily()
  val bodyStyle = TextStyle(
    fontFamily = bodyFontFamily,
    fontSize = preferences.fontSizeSp.sp,
    lineHeight = (preferences.fontSizeSp * preferences.lineHeightMultiplier).sp,
    color = surfaceColors.text
  )

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
        },
      contentAlignment = Alignment.TopCenter
    ) {
      val verticalFlingBehavior = rememberDampedVerticalFlingBehavior()
      LazyColumn(
        state = listState,
        flingBehavior = verticalFlingBehavior,
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
        items(
          items = feedEntries,
          key = { it.key }
        ) { entry ->
          when (entry) {
            is EpubVerticalFeedEntry.ChapterDivider -> {
              HorizontalDivider(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(vertical = 18.dp)
                  .testTag("epub_vertical_chapter_divider_${entry.chapterIndex}"),
                thickness = 1.dp,
                color = surfaceColors.secondaryText.copy(alpha = 0.35f)
              )
            }

            is EpubVerticalFeedEntry.LoadingPlaceholder -> {
              LaunchedEffect(entry.chapterIndex) {
                latestOnRequestChapter(entry.chapterIndex)
              }
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .heightIn(min = 120.dp)
                  .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
              ) {
                Text(
                  text = entry.title,
                  style = MaterialTheme.typography.labelMedium,
                  color = surfaceColors.secondaryText.copy(alpha = 0.65f)
                )
              }
            }

            is EpubVerticalFeedEntry.CoverPage -> {
              val coverBlock = entry.imageBlock
              LaunchedEffect(coverBlock.zipEntryPath) {
                onRequestImage(coverBlock.zipEntryPath)
              }
              val bmp = imageCache[coverBlock.zipEntryPath]
              BoxWithConstraints(
                modifier = Modifier
                  .fillParentMaxSize()
                  .padding(vertical = 20.dp)
                  .testTag("epub_vertical_cover_container"),
                contentAlignment = Alignment.Center
              ) {
                if (bmp != null) {
                  val (targetW, targetH) = computeFittedCoverDimensions(
                    imageWidthPx = bmp.width,
                    imageHeightPx = bmp.height,
                    maxWidthDp = maxWidth.value,
                    maxHeightDp = maxHeight.value
                  )
                  Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = coverBlock.alt.ifBlank { "Portada del libro" },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                      .size(width = targetW.dp, height = targetH.dp)
                      .clip(RoundedCornerShape(10.dp))
                      .testTag("epub_vertical_cover_image")
                  )
                }
              }
            }

            is EpubVerticalFeedEntry.BlockEntry -> {
              val block = entry.block
              val blockIndex = entry.blockIndex
              val blockChapterIndex = entry.chapterIndex
              val blockHighlights = remember(allHighlights, blockChapterIndex, blockIndex) {
                allHighlights.filter { hl ->
                  if (hl.unitIndex != blockChapterIndex || hl.blockIndex < 0) return@filter false
                  val endBlk = if (hl.endBlockIndex >= hl.blockIndex) hl.endBlockIndex else hl.blockIndex
                  blockIndex in hl.blockIndex..endBlk
                }
              }
              val spokenIdx = if (blockChapterIndex == chapter.index) spokenIndexByBlock[blockIndex] else null
              val isTtsActive = ttsActiveBlockIndex != null && spokenIdx == ttsActiveBlockIndex

              when (block) {
                is EpubBlock.Heading -> {
                  val headingSize = when (block.level) {
                    1 -> (preferences.fontSizeSp + 8f).sp
                    2 -> (preferences.fontSizeSp + 4f).sp
                    else -> (preferences.fontSizeSp + 2f).sp
                  }
                  val sliceKey = EpubSliceKey(blockChapterIndex, blockIndex, 0)
                  SelectableTextBlockWrapper(
                    sliceKey = sliceKey,
                    startCharInBlock = 0,
                    rawText = block.text,
                    fullBlockText = block.text,
                    activeSelection = activeSelection,
                    chapterIndex = blockChapterIndex,
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
                          chapterIndex = blockChapterIndex,
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
                  val sliceKey = EpubSliceKey(blockChapterIndex, blockIndex, 0)
                  SelectableTextBlockWrapper(
                    sliceKey = sliceKey,
                    startCharInBlock = 0,
                    rawText = block.content.text,
                    fullBlockText = block.content.text,
                    activeSelection = activeSelection,
                    chapterIndex = blockChapterIndex,
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
                          chapterIndex = blockChapterIndex,
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
                  val sliceKey = EpubSliceKey(blockChapterIndex, blockIndex, 0)
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
                          chapterIndex = blockChapterIndex,
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
                                chapterIndex = blockChapterIndex,
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
    }

    val selChapterIdx = activeSelection?.chapterIndex ?: chapter.index
    val selChapter = effectiveLoadedChapters[selChapterIdx] ?: chapter
    val scrollSlices = sliceLayouts.values.filter { it.key.pageOrScopeId == selChapterIdx }
    val geometry = computeEpubSelectionGeometry(
      selection = activeSelection,
      chapterIndex = selChapterIdx,
      visibleSlices = scrollSlices,
      containerPosInRoot = overlayContainerPosInRoot
    )
    val selectedText = remember(activeSelection, selChapter.blocks) {
      if (activeSelection != null) {
        extractSelectedEpubText(selChapter.blocks, activeSelection)
      } else ""
    }
    val locationLabel = "cap. ${selChapterIdx + 1}"

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
              blocks = selChapter.blocks,
              selection = activeSelection,
              fallbackBlockIndex = activeSelection.start.blockIndex,
              chapterIndex = selChapterIdx
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
