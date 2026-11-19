package com.readicop.lettor.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readicop.lettor.data.local.HighlightEntity
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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

private fun computeLocalSliceHighlightRange(
  selection: EpubTextSelection?,
  blockIndex: Int,
  sliceStartOffset: Int,
  sliceLength: Int
): IntRange? {
  if (selection == null || sliceLength <= 0) return null
  val s = selection.start
  val e = selection.end
  if (blockIndex < s.blockIndex || blockIndex > e.blockIndex) return null

  val sliceEndOffset = sliceStartOffset + sliceLength
  val blockStart = if (blockIndex == s.blockIndex) s.charOffset else 0
  val blockEnd = if (blockIndex == e.blockIndex) e.charOffset else Int.MAX_VALUE

  val overlapStart = maxOf(sliceStartOffset, blockStart)
  val overlapEnd = minOf(sliceEndOffset, blockEnd)
  if (overlapEnd <= overlapStart) return null

  val localStart = (overlapStart - sliceStartOffset).coerceIn(0, sliceLength)
  val localEnd = (overlapEnd - sliceStartOffset).coerceIn(localStart, sliceLength)
  return if (localEnd > localStart) localStart until localEnd else null
}

private fun computeEpubSelectionGeometry(
  selection: EpubTextSelection?,
  chapterIndex: Int,
  visibleSlices: Collection<EpubSliceLayoutInfo>,
  containerPosInRoot: Offset,
  dotDropPx: Float
): EpubSelectionGeometry {
  if (selection == null || selection.chapterIndex != chapterIndex || visibleSlices.isEmpty()) {
    return EpubSelectionGeometry(null, null, null)
  }

  val isAStart = selection.anchorA <= selection.anchorB
  var posA: Offset? = null
  var posB: Offset? = null
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
    val x = (matchingSlice.posInRoot.x - containerPosInRoot.x) + if (isEndEdge) box.right else box.left
    val y = (matchingSlice.posInRoot.y - containerPosInRoot.y) + box.bottom + dotDropPx
    return Offset(x, y)
  }

  posA = resolveAnchorPoint(selection.anchorA, isEndEdge = !isAStart)
  posB = resolveAnchorPoint(selection.anchorB, isEndEdge = isAStart)

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
  dotDropPx: Float
): EpubCharPosition? {
  if (visibleSlices.isEmpty()) return null
  val targetRoot = Offset(
    x = containerPosInRoot.x + pointerInContainer.x,
    y = containerPosInRoot.y + pointerInContainer.y - dotDropPx
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
  preferences: ReaderPreferences,
  surfaceColors: ReaderSurfaceColors,
  imageCache: Map<String, Bitmap>,
  highlights: List<HighlightEntity>,
  ttsActiveBlockIndex: Int?,
  isAutoScrolling: Boolean,
  autoScrollSpeed: Float,
  topOverlayClearanceDp: androidx.compose.ui.unit.Dp = 0.dp,
  onRequestImage: (String) -> Unit,
  onToggleControls: () -> Unit,
  onOpenFragmentTools: (blockIndex: Int, text: String) -> Unit,
  onCopyPlainText: (String) -> Unit,
  onCopyCitation: (text: String, locationLabel: String) -> Unit,
  onShareSelection: (text: String, locationLabel: String) -> Unit,
  onPositionChanged: (chapterIndex: Int, subPage: Int, overallProgress: Float) -> Unit,
  modifier: Modifier = Modifier
) {
  val chapterHighlights = remember(highlights, activeChapter.index) {
    highlights
      .filter { it.unitIndex == activeChapter.index && it.blockIndex >= 0 }
      .associateBy { it.blockIndex }
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

  // Character-anchored selection state across the current chapter
  var activeSelection by remember(activeChapter.index) {
    mutableStateOf<EpubTextSelection?>(null)
  }

  if (preferences.epubScrollMode) {
    EpubScrollReader(
      chapterStubs = chapterStubs,
      chapter = activeChapter,
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
      onCopyCitation = onCopyCitation,
      onShareSelection = onShareSelection,
      onSelectChapter = { nextIdx ->
        activeSelection = null
        val prog = (nextIdx + 1).toFloat() / chapterStubs.size.coerceAtLeast(1)
        onPositionChanged(nextIdx, 0, prog)
      },
      modifier = modifier
    )
  } else {
    EpubPaginatedReader(
      metadata = metadata,
      chapterStubs = chapterStubs,
      chapter = activeChapter,
      initialSubPage = initialSubPage,
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
      onCopyCitation = onCopyCitation,
      onShareSelection = onShareSelection,
      onPositionChanged = onPositionChanged,
      modifier = modifier
    )
  }
}

@Composable
private fun EpubPaginatedReader(
  metadata: EpubMetadata,
  chapterStubs: List<EpubChapterStub>,
  chapter: EpubChapter,
  initialSubPage: Int,
  surfaceColors: ReaderSurfaceColors,
  preferences: ReaderPreferences,
  imageCache: Map<String, Bitmap>,
  chapterHighlights: Map<Int, HighlightEntity>,
  spokenIndexByBlock: Map<Int, Int>,
  ttsActiveBlockIndex: Int?,
  topOverlayClearanceDp: androidx.compose.ui.unit.Dp,
  activeSelection: EpubTextSelection?,
  onSelectionChanged: (EpubTextSelection?) -> Unit,
  onRequestImage: (String) -> Unit,
  onToggleControls: () -> Unit,
  onOpenFragmentTools: (blockIndex: Int, text: String) -> Unit,
  onCopyPlainText: (String) -> Unit,
  onCopyCitation: (text: String, locationLabel: String) -> Unit,
  onShareSelection: (text: String, locationLabel: String) -> Unit,
  onPositionChanged: (chapterIndex: Int, subPage: Int, overallProgress: Float) -> Unit,
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

  val dotDropPx = with(density) { 7.dp.toPx() }
  val topSafePx = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().let {
    with(density) { (it + topOverlayClearanceDp).toPx() }
  }
  val bottomSafePx = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().let {
    with(density) { it.toPx() }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(surfaceColors.background)
      .onGloballyPositioned { coords ->
        overlayContainerSize = coords.size
        overlayContainerPosInRoot = coords.positionInRoot()
      }
  ) {
    BoxWithConstraints(
      modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.statusBars)
        .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
      val contentMaxWidthDp = minOf(maxWidth, 680.dp)
      val horizontalPad = preferences.horizontalPaddingDp.dp
      val availableWidthDp = (contentMaxWidthDp - horizontalPad * 2).coerceAtLeast(180.dp)
      val availableHeightDp = (maxHeight - 86.dp).coerceAtLeast(200.dp)

      val availableWidthPx = with(density) { availableWidthDp.roundToPx() }
      val availableHeightPx = with(density) { availableHeightDp.roundToPx() }
      val paragraphGapPx = with(density) { 14.dp.roundToPx() }
      val imageHeightPx = with(density) { 210.dp.roundToPx() }
      val quoteIndentPx = with(density) { 20.dp.roundToPx() }

      val pages = remember(
        chapter.index,
        chapter.blocks,
        availableWidthPx,
        availableHeightPx,
        preferences.fontSizeSp,
        preferences.lineHeightMultiplier,
        preferences.readerFont,
        preferences.horizontalPaddingDp
      ) {
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

      val safePageCount = pages.size.coerceAtLeast(1)
      val startPage = initialSubPage.coerceIn(0, safePageCount - 1)
      val pagerState = rememberPagerState(initialPage = startPage) { safePageCount }

      LaunchedEffect(chapter.index, safePageCount) {
        val target = initialSubPage.coerceIn(0, safePageCount - 1)
        if (pagerState.currentPage != target) {
          pagerState.scrollToPage(target)
        }
      }

      // If text relayout (font size, margins, line spacing) moves the active selection start to another sub-page,
      // keep the user anchored on the page containing their selected words.
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

      LaunchedEffect(pagerState.currentPage, chapter.index, safePageCount) {
        val chapterBase = chapter.index.toFloat() / totalChapters.toFloat()
        val inChapterFraction = (pagerState.currentPage + 1).toFloat() / safePageCount.toFloat()
        val overall = (chapterBase + inChapterFraction / totalChapters.toFloat()).coerceIn(0f, 1f)
        onPositionChanged(chapter.index, pagerState.currentPage, overall)
      }

      // Clear selection when user swipes manually to a page that does not contain the selection
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

      // Auto-advance sub-page when TTS moves to a block rendered on a later page
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
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        // Subtle editorial running header
        Row(
          modifier = Modifier
            .widthIn(max = 680.dp)
            .fillMaxWidth()
            .padding(horizontal = horizontalPad, vertical = 10.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = chapter.title,
            style = MaterialTheme.typography.labelSmall,
            color = surfaceColors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
          )
          Spacer(Modifier.width(12.dp))
          Text(
            text = "Cap. ${chapter.index + 1}/$totalChapters",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
            color = surfaceColors.secondaryText
          )
        }

        Box(
          modifier = Modifier
            .weight(1f)
            .widthIn(max = 680.dp)
            .fillMaxWidth()
        ) {
          HorizontalPager(
            state = pagerState,
            userScrollEnabled = activeSelection == null,
            modifier = Modifier.fillMaxSize()
          ) { pageIdx ->
            val page = pages[pageIdx]
            var pageWidthPx by remember { mutableFloatStateOf(availableWidthPx.toFloat()) }

            val handlePageTap: (Float) -> Unit = { xInPage ->
              if (activeSelection != null) {
                onSelectionChanged(null)
              } else {
                val w = pageWidthPx.coerceAtLeast(1f)
                when {
                  xInPage < w * 0.22f -> {
                    if (pageIdx > 0) {
                      scope.launch { pagerState.animateScrollToPage(pageIdx - 1) }
                    } else if (chapter.index > 0) {
                      val prevIdx = chapter.index - 1
                      val prog = (prevIdx + 1).toFloat() / totalChapters
                      onPositionChanged(prevIdx, 0, prog)
                    }
                  }
                  xInPage > w * 0.78f -> {
                    if (pageIdx < safePageCount - 1) {
                      scope.launch { pagerState.animateScrollToPage(pageIdx + 1) }
                    } else if (chapter.index < totalChapters - 1) {
                      val nextIdx = chapter.index + 1
                      val prog = (nextIdx + 1).toFloat() / totalChapters
                      onPositionChanged(nextIdx, 0, prog)
                    }
                  }
                  else -> onToggleControls()
                }
              }
            }

            Box(
              modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coords ->
                  pageWidthPx = coords.size.width.toFloat()
                }
                .pointerInput(pageIdx, chapter.index, safePageCount, activeSelection != null) {
                  detectTapGestures { offset -> handlePageTap(offset.x) }
                }
                .padding(horizontal = horizontalPad, vertical = 6.dp)
            ) {
              Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
              ) {
                page.items.forEach { item ->
                  val highlight = chapterHighlights[item.blockIndex]
                  val spokenIdx = spokenIndexByBlock[item.blockIndex]
                  val isTtsActive = ttsActiveBlockIndex != null && spokenIdx == ttsActiveBlockIndex
                  val blockWash = when {
                    highlight != null -> if (surfaceColors.isDark) highlight.color.washDark else highlight.color.washLight
                    isTtsActive -> surfaceColors.accent.copy(alpha = 0.14f)
                    else -> Color.Transparent
                  }

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
                        activeSelection = activeSelection,
                        chapterIndex = chapter.index,
                        selectionColor = surfaceColors.accent.copy(alpha = 0.28f),
                        backgroundColor = blockWash,
                        isTtsActive = isTtsActive,
                        accentColor = surfaceColors.accent,
                        highlight = highlight,
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
                      SelectableTextBlockWrapper(
                        sliceKey = sliceKey,
                        startCharInBlock = item.startCharOffsetInBlock,
                        rawText = item.text.text,
                        activeSelection = activeSelection,
                        chapterIndex = chapter.index,
                        selectionColor = surfaceColors.accent.copy(alpha = 0.28f),
                        backgroundColor = blockWash,
                        isTtsActive = isTtsActive,
                        accentColor = surfaceColors.accent,
                        highlight = highlight,
                        sliceLayouts = sliceLayouts,
                        onTapXInPage = handlePageTap,
                        onSelectWord = { localChar ->
                          val fullBlockText = (chapter.blocks.getOrNull(item.blockIndex) as? EpubBlock.Paragraph)?.content?.text
                            ?: item.fullBlockText
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
                      SelectableTextBlockWrapper(
                        sliceKey = sliceKey,
                        startCharInBlock = item.startCharOffsetInBlock,
                        rawText = item.text.text,
                        activeSelection = activeSelection,
                        chapterIndex = chapter.index,
                        selectionColor = surfaceColors.accent.copy(alpha = 0.28f),
                        backgroundColor = blockWash,
                        isTtsActive = isTtsActive,
                        accentColor = surfaceColors.accent,
                        highlight = highlight,
                        sliceLayouts = sliceLayouts,
                        onTapXInPage = handlePageTap,
                        onSelectWord = { localChar ->
                          val fullBlockText = (chapter.blocks.getOrNull(item.blockIndex) as? EpubBlock.Quote)?.content?.text
                            ?: item.fullBlockText
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

                // Next chapter prompt on final page
                if (pageIdx == safePageCount - 1 && chapter.index < totalChapters - 1) {
                  Spacer(Modifier.weight(1f))
                  HorizontalDivider(color = surfaceColors.divider)
                  Row(
                    modifier = Modifier
                      .fillMaxWidth()
                      .clickable {
                        onSelectionChanged(null)
                        val nextIdx = chapter.index + 1
                        val prog = (nextIdx + 1).toFloat() / totalChapters
                        onPositionChanged(nextIdx, 0, prog)
                      }
                      .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Column(modifier = Modifier.weight(1f)) {
                      Text(
                        text = "SIGUIENTE CAPÍTULO",
                        style = MaterialTheme.typography.labelSmall,
                        color = surfaceColors.accent
                      )
                      Text(
                        text = chapterStubs[chapter.index + 1].title,
                        style = MaterialTheme.typography.titleSmall,
                        color = surfaceColors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                      )
                    }
                    Icon(
                      imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                      contentDescription = "Ir al siguiente capítulo",
                      tint = surfaceColors.accent
                    )
                  }
                }
              }
            }
          }
        }

        // Subtle footer page counter
        Row(
          modifier = Modifier
            .widthIn(max = 680.dp)
            .fillMaxWidth()
            .padding(horizontal = horizontalPad, vertical = 10.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = metadata.title,
            style = MaterialTheme.typography.labelSmall,
            color = surfaceColors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
          )
          Spacer(Modifier.width(12.dp))
          Text(
            text = "Pág. ${pagerState.currentPage + 1} de $safePageCount",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
            color = surfaceColors.secondaryText
          )
        }
      }

      // Compute visible slices for the current page & overlay Canvas handles + menu
      val currentPageSlices = sliceLayouts.values.filter { it.key.pageOrScopeId == pagerState.currentPage }
      val geometry = computeEpubSelectionGeometry(
        selection = activeSelection,
        chapterIndex = chapter.index,
        visibleSlices = currentPageSlices,
        containerPosInRoot = overlayContainerPosInRoot,
        dotDropPx = dotDropPx
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
          onDragAnchorA = { ptr ->
            val hit = hitTestEpubCharPosition(ptr, overlayContainerPosInRoot, currentPageSlices, dotDropPx)
            if (hit != null && hit != activeSelection.anchorB) {
              onSelectionChanged(activeSelection.copy(anchorA = hit))
            }
          },
          onDragAnchorB = { ptr ->
            val hit = hitTestEpubCharPosition(ptr, overlayContainerPosInRoot, currentPageSlices, dotDropPx)
            if (hit != null && hit != activeSelection.anchorA) {
              onSelectionChanged(activeSelection.copy(anchorB = hit))
            }
          },
          onCopyPlain = {
            onCopyPlainText(selectedText)
            onSelectionChanged(null)
          },
          onCopyCitation = {
            onCopyCitation(selectedText, locationLabel)
            onSelectionChanged(null)
          },
          onHighlight = {
            val blockIdx = activeSelection.start.blockIndex
            onOpenFragmentTools(blockIdx, selectedText)
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
}

@Composable
private fun SelectableTextBlockWrapper(
  sliceKey: EpubSliceKey,
  startCharInBlock: Int,
  rawText: String,
  activeSelection: EpubTextSelection?,
  chapterIndex: Int,
  selectionColor: Color,
  backgroundColor: Color,
  isTtsActive: Boolean,
  accentColor: Color,
  highlight: HighlightEntity?,
  sliceLayouts: MutableMap<EpubSliceKey, EpubSliceLayoutInfo>,
  onTapXInPage: (Float) -> Unit,
  onSelectWord: (localCharOffset: Int) -> Unit,
  textContent: @Composable (onTextLayout: (TextLayoutResult) -> Unit, textModifier: Modifier) -> Unit
) {
  var blockOffsetX by remember { mutableFloatStateOf(0f) }
  var latestLayout by remember(sliceKey) { mutableStateOf<TextLayoutResult?>(null) }
  var latestPosInRoot by remember(sliceKey) { mutableStateOf(Offset.Zero) }

  DisposableEffect(sliceKey) {
    onDispose { sliceLayouts.remove(sliceKey) }
  }

  val highlightRange = remember(activeSelection, chapterIndex, sliceKey.blockIndex, startCharInBlock, rawText.length) {
    if (activeSelection != null && activeSelection.chapterIndex == chapterIndex) {
      computeLocalSliceHighlightRange(
        selection = activeSelection,
        blockIndex = sliceKey.blockIndex,
        sliceStartOffset = startCharInBlock,
        sliceLength = rawText.length
      )
    } else null
  }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .then(
        if (backgroundColor != Color.Transparent) Modifier.background(backgroundColor)
        else Modifier
      )
      .then(
        if (isTtsActive) Modifier.border(1.dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
        else Modifier
      )
      .onGloballyPositioned { coords ->
        blockOffsetX = coords.positionInParent().x
      }
      .padding(
        horizontal = if (backgroundColor != Color.Transparent) 6.dp else 0.dp,
        vertical = if (backgroundColor != Color.Transparent) 4.dp else 0.dp
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
        val layout = latestLayout
        val range = highlightRange
        if (layout != null && range != null) {
          val maxLen = layout.layoutInput.text.length
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
        detectTapGestures(
          onLongPress = { localOffset ->
            val layout = latestLayout
            val localChar = if (layout != null && rawText.isNotEmpty()) {
              layout.getOffsetForPosition(localOffset).coerceIn(0, rawText.lastIndex)
            } else 0
            onSelectWord(localChar)
          },
          onTap = { local -> onTapXInPage(blockOffsetX + local.x) }
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

    if (highlight != null && highlight.stickyNote.isNotBlank()) {
      Spacer(Modifier.height(4.dp))
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
          .fillMaxWidth()
          .background(highlight.color.dotColor.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
          .padding(horizontal = 8.dp, vertical = 4.dp)
      ) {
        Box(
          modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(highlight.color.dotColor)
        )
        Spacer(Modifier.width(6.dp))
        Text(
          text = "Nota: ${highlight.stickyNote}",
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
  chapterStubs: List<EpubChapterStub>,
  chapter: EpubChapter,
  surfaceColors: ReaderSurfaceColors,
  preferences: ReaderPreferences,
  imageCache: Map<String, Bitmap>,
  chapterHighlights: Map<Int, HighlightEntity>,
  spokenIndexByBlock: Map<Int, Int>,
  ttsActiveBlockIndex: Int?,
  isAutoScrolling: Boolean,
  autoScrollSpeed: Float,
  topOverlayClearanceDp: androidx.compose.ui.unit.Dp,
  activeSelection: EpubTextSelection?,
  onSelectionChanged: (EpubTextSelection?) -> Unit,
  onRequestImage: (String) -> Unit,
  onToggleControls: () -> Unit,
  onOpenFragmentTools: (blockIndex: Int, text: String) -> Unit,
  onCopyPlainText: (String) -> Unit,
  onCopyCitation: (text: String, locationLabel: String) -> Unit,
  onShareSelection: (text: String, locationLabel: String) -> Unit,
  onSelectChapter: (Int) -> Unit,
  modifier: Modifier = Modifier
) {
  val listState = rememberLazyListState()
  val density = LocalDensity.current
  val totalChapters = chapterStubs.size.coerceAtLeast(1)

  var overlayContainerSize by remember { mutableStateOf(IntSize.Zero) }
  var overlayContainerPosInRoot by remember { mutableStateOf(Offset.Zero) }
  val sliceLayouts = remember(chapter.index) {
    mutableStateMapOf<EpubSliceKey, EpubSliceLayoutInfo>()
  }

  val dotDropPx = with(density) { 7.dp.toPx() }
  val topSafePx = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().let {
    with(density) { (it + topOverlayClearanceDp).toPx() }
  }
  val bottomSafePx = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().let {
    with(density) { it.toPx() }
  }

  LaunchedEffect(chapter.index) {
    listState.scrollToItem(0)
  }

  // Smooth Auto-Scroll coroutine
  LaunchedEffect(isAutoScrolling, autoScrollSpeed, chapter.index) {
    if (!isAutoScrolling) return@LaunchedEffect
    while (isActive) {
      if (listState.canScrollForward) {
        listState.scrollBy(autoScrollSpeed * 1.15f)
      }
      delay(16L)
    }
  }

  // Follow active TTS paragraph in scroll mode
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
        .windowInsetsPadding(WindowInsets.statusBars)
        .windowInsetsPadding(WindowInsets.navigationBars)
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null
        ) {
          if (activeSelection != null) onSelectionChanged(null) else onToggleControls()
        },
      contentAlignment = Alignment.TopCenter
    ) {
      LazyColumn(
        state = listState,
        contentPadding = PaddingValues(
          horizontal = preferences.horizontalPaddingDp.dp,
          vertical = 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
          .widthIn(max = 680.dp)
          .fillMaxHeight()
          .testTag("epub_scroll_list")
      ) {
        itemsIndexed(chapter.blocks) { blockIndex, block ->
          val highlight = chapterHighlights[blockIndex]
          val spokenIdx = spokenIndexByBlock[blockIndex]
          val isTtsActive = ttsActiveBlockIndex != null && spokenIdx == ttsActiveBlockIndex
          val blockWash = when {
            highlight != null -> if (surfaceColors.isDark) highlight.color.washDark else highlight.color.washLight
            isTtsActive -> surfaceColors.accent.copy(alpha = 0.14f)
            else -> Color.Transparent
          }

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
                activeSelection = activeSelection,
                chapterIndex = chapter.index,
                selectionColor = surfaceColors.accent.copy(alpha = 0.28f),
                backgroundColor = blockWash,
                isTtsActive = isTtsActive,
                accentColor = surfaceColors.accent,
                highlight = highlight,
                sliceLayouts = sliceLayouts,
                onTapXInPage = {
                  if (activeSelection != null) onSelectionChanged(null) else onToggleControls()
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
                activeSelection = activeSelection,
                chapterIndex = chapter.index,
                selectionColor = surfaceColors.accent.copy(alpha = 0.28f),
                backgroundColor = blockWash,
                isTtsActive = isTtsActive,
                accentColor = surfaceColors.accent,
                highlight = highlight,
                sliceLayouts = sliceLayouts,
                onTapXInPage = {
                  if (activeSelection != null) onSelectionChanged(null) else onToggleControls()
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
                color = if (blockWash != Color.Transparent) blockWash else surfaceColors.surface,
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
                      activeSelection = activeSelection,
                      chapterIndex = chapter.index,
                      selectionColor = surfaceColors.accent.copy(alpha = 0.28f),
                      backgroundColor = Color.Transparent,
                      isTtsActive = isTtsActive,
                      accentColor = surfaceColors.accent,
                      highlight = highlight,
                      sliceLayouts = sliceLayouts,
                      onTapXInPage = {
                        if (activeSelection != null) onSelectionChanged(null) else onToggleControls()
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

        item {
          Spacer(Modifier.height(16.dp))
          HorizontalDivider(color = surfaceColors.divider)
          Spacer(Modifier.height(16.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            if (chapter.index > 0) {
              OutlinedButton(onClick = { onSelectChapter(chapter.index - 1) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Anterior")
              }
            } else {
              Spacer(Modifier.width(1.dp))
            }

            if (chapter.index < totalChapters - 1) {
              FilledTonalButton(onClick = { onSelectChapter(chapter.index + 1) }) {
                Text("Siguiente capítulo")
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
              }
            }
          }
          Spacer(Modifier.height(48.dp))
        }
      }
    }

    val scrollSlices = sliceLayouts.values
    val geometry = computeEpubSelectionGeometry(
      selection = activeSelection,
      chapterIndex = chapter.index,
      visibleSlices = scrollSlices,
      containerPosInRoot = overlayContainerPosInRoot,
      dotDropPx = dotDropPx
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
        onDragAnchorA = { ptr ->
          val hit = hitTestEpubCharPosition(ptr, overlayContainerPosInRoot, scrollSlices, dotDropPx)
          if (hit != null && hit != activeSelection.anchorB) {
            onSelectionChanged(activeSelection.copy(anchorA = hit))
          }
        },
        onDragAnchorB = { ptr ->
          val hit = hitTestEpubCharPosition(ptr, overlayContainerPosInRoot, scrollSlices, dotDropPx)
          if (hit != null && hit != activeSelection.anchorA) {
            onSelectionChanged(activeSelection.copy(anchorB = hit))
          }
        },
        onCopyPlain = {
          onCopyPlainText(selectedText)
          onSelectionChanged(null)
        },
        onCopyCitation = {
          onCopyCitation(selectedText, locationLabel)
          onSelectionChanged(null)
        },
        onHighlight = {
          onOpenFragmentTools(activeSelection.start.blockIndex, selectedText)
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
