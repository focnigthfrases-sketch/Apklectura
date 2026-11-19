package com.readicop.lettor.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.readicop.lettor.data.local.HighlightEntity
import com.readicop.lettor.data.preferences.ReaderPreferences
import com.readicop.lettor.data.preferences.ReaderTheme
import com.readicop.lettor.domain.engine.PdfBookSession
import com.readicop.lettor.domain.engine.PdfGlyphWord
import com.readicop.lettor.domain.engine.PdfPageTextData
import com.readicop.lettor.domain.engine.PdfZoomTile
import com.readicop.lettor.domain.engine.computePdfZoomTileRegion
import com.readicop.lettor.ui.theme.JetBrainsMonoFamily
import com.readicop.lettor.ui.theme.ReaderSurfaceColors
import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive

data class PdfWordSelection(
  val pageIndex: Int,
  val anchorWordA: Int,
  val anchorWordB: Int
) {
  val startWord: Int get() = minOf(anchorWordA, anchorWordB)
  val endWord: Int get() = maxOf(anchorWordA, anchorWordB)
}

private data class PdfPageRenderMetrics(
  val pageIndex: Int,
  val viewportPosInRoot: Offset,
  val viewportSize: IntSize,
  val basePageSizePx: Size,
  val scale: Float,
  val panOffset: Offset
)

private data class PdfSelectionGeometry(
  val anchorAPos: Offset?,
  val anchorBPos: Offset?,
  val selectionBounds: Rect?
)

fun extractSelectedPdfText(words: List<PdfGlyphWord>, selection: PdfWordSelection): String {
  if (words.isEmpty()) return ""
  val s = selection.startWord.coerceIn(0, words.lastIndex)
  val e = selection.endWord.coerceIn(s, words.lastIndex)
  val sb = StringBuilder()
  var prevWord: PdfGlyphWord? = null
  for (i in s..e) {
    val w = words[i]
    if (prevWord != null) {
      val lineGap = w.rect.centerY - prevWord.rect.centerY
      val lineHeight = maxOf(prevWord.rect.height, w.rect.height, 0.01f)
      if (lineGap > lineHeight * 1.6f) {
        sb.append('\n')
      } else {
        sb.append(' ')
      }
    }
    sb.append(w.text)
    prevWord = w
  }
  return sb.toString().trim()
}

private fun findNearestPdfWordIndex(
  words: List<PdfGlyphWord>,
  normX: Float,
  normY: Float
): Int? {
  if (words.isEmpty()) return null
  var bestIdx = 0
  var bestScore = Float.MAX_VALUE

  words.forEachIndexed { idx, w ->
    val r = w.rect
    val dx = when {
      normX < r.left -> r.left - normX
      normX > r.right -> normX - r.right
      else -> 0f
    }
    val dy = when {
      normY < r.top -> r.top - normY
      normY > r.bottom -> normY - r.bottom
      else -> 0f
    }
    // Weight vertical distance slightly higher so dragging stays on the intended text line
    val score = hypot(dx, dy * 2.4f)
    if (score < bestScore) {
      bestScore = score
      bestIdx = idx
    }
  }
  return bestIdx
}

private fun pdfNormToOverlayPoint(
  normX: Float,
  normY: Float,
  metrics: PdfPageRenderMetrics,
  overlayContainerPosInRoot: Offset
): Offset {
  val pageW = metrics.basePageSizePx.width
  val pageH = metrics.basePageSizePx.height
  val viewW = metrics.viewportSize.width.toFloat()
  val viewH = metrics.viewportSize.height.toFloat()

  val localFromCenterX = normX * pageW - pageW * 0.5f
  val localFromCenterY = normY * pageH - pageH * 0.5f
  val inViewportX = viewW * 0.5f + localFromCenterX * metrics.scale + metrics.panOffset.x
  val inViewportY = viewH * 0.5f + localFromCenterY * metrics.scale + metrics.panOffset.y

  return Offset(
    x = (metrics.viewportPosInRoot.x - overlayContainerPosInRoot.x) + inViewportX,
    y = (metrics.viewportPosInRoot.y - overlayContainerPosInRoot.y) + inViewportY
  )
}

private fun overlayPointToPdfNorm(
  overlayPoint: Offset,
  metrics: PdfPageRenderMetrics,
  overlayContainerPosInRoot: Offset
): Offset {
  val pageW = metrics.basePageSizePx.width.coerceAtLeast(1f)
  val pageH = metrics.basePageSizePx.height.coerceAtLeast(1f)
  val viewW = metrics.viewportSize.width.toFloat()
  val viewH = metrics.viewportSize.height.toFloat()
  val safeScale = metrics.scale.coerceAtLeast(0.1f)

  val inViewportX = overlayPoint.x - (metrics.viewportPosInRoot.x - overlayContainerPosInRoot.x)
  val inViewportY = overlayPoint.y - (metrics.viewportPosInRoot.y - overlayContainerPosInRoot.y)

  val localFromCenterX = (inViewportX - viewW * 0.5f - metrics.panOffset.x) / safeScale
  val localFromCenterY = (inViewportY - viewH * 0.5f - metrics.panOffset.y) / safeScale

  return Offset(
    x = ((localFromCenterX + pageW * 0.5f) / pageW).coerceIn(0f, 1f),
    y = ((localFromCenterY + pageH * 0.5f) / pageH).coerceIn(0f, 1f)
  )
}

private fun computePdfSelectionGeometry(
  selection: PdfWordSelection?,
  pageText: PdfPageTextData?,
  metrics: PdfPageRenderMetrics?,
  overlayContainerPosInRoot: Offset,
  handleHorizontalOffsetPx: Float
): PdfSelectionGeometry {
  if (selection == null || pageText == null || pageText.isScanned || metrics == null) {
    return PdfSelectionGeometry(null, null, null)
  }
  if (pageText.pageIndex != selection.pageIndex || metrics.pageIndex != selection.pageIndex) {
    return PdfSelectionGeometry(null, null, null)
  }
  val words = pageText.words
  if (words.isEmpty()) return PdfSelectionGeometry(null, null, null)

  val idxA = selection.anchorWordA.coerceIn(0, words.lastIndex)
  val idxB = selection.anchorWordB.coerceIn(0, words.lastIndex)
  val isAStart = idxA <= idxB

  val wordA = words[idxA]
  val wordB = words[idxB]

  val normAX = if (isAStart) wordA.rect.left else wordA.rect.right
  val normAY = (wordA.rect.top + wordA.rect.bottom) * 0.5f
  val normBX = if (isAStart) wordB.rect.right else wordB.rect.left
  val normBY = (wordB.rect.top + wordB.rect.bottom) * 0.5f

  val offsetAX = if (isAStart) -handleHorizontalOffsetPx else handleHorizontalOffsetPx
  val offsetBX = if (isAStart) handleHorizontalOffsetPx else -handleHorizontalOffsetPx

  val anchorAPos = pdfNormToOverlayPoint(normAX, normAY, metrics, overlayContainerPosInRoot) + Offset(offsetAX, 0f)
  val anchorBPos = pdfNormToOverlayPoint(normBX, normBY, metrics, overlayContainerPosInRoot) + Offset(offsetBX, 0f)

  val s = minOf(idxA, idxB)
  val e = maxOf(idxA, idxB)
  var minL = Float.MAX_VALUE
  var minT = Float.MAX_VALUE
  var maxR = -Float.MAX_VALUE
  var maxB = -Float.MAX_VALUE

  for (i in s..e) {
    val r = words[i].rect
    minL = minOf(minL, r.left)
    minT = minOf(minT, r.top)
    maxR = maxOf(maxR, r.right)
    maxB = maxOf(maxB, r.bottom)
  }

  val topLeft = pdfNormToOverlayPoint(minL, minT, metrics, overlayContainerPosInRoot)
  val bottomRight = pdfNormToOverlayPoint(maxR, maxB, metrics, overlayContainerPosInRoot)
  val bounds = Rect(
    left = minOf(topLeft.x, bottomRight.x),
    top = minOf(topLeft.y, bottomRight.y),
    right = maxOf(topLeft.x, bottomRight.x),
    bottom = maxOf(topLeft.y, bottomRight.y)
  )

  return PdfSelectionGeometry(anchorAPos, anchorBPos, bounds)
}

@Composable
fun PdfReaderContent(
  bookTitle: String,
  pageCount: Int,
  session: PdfBookSession,
  currentPageIndex: Int,
  preferences: ReaderPreferences,
  surfaceColors: ReaderSurfaceColors,
  highlights: List<HighlightEntity> = emptyList(),
  isAutoScrolling: Boolean = false,
  autoScrollSpeed: Float = 1.2f,
  topOverlayClearanceDp: androidx.compose.ui.unit.Dp = 0.dp,
  onToggleControls: () -> Unit,
  onOpenFragmentTools: (pageIndex: Int, startWordIndex: Int, endWordExclusive: Int, text: String) -> Unit = { _, _, _, _ -> },
  onCopyPlainText: (String) -> Unit = {},
  onCopyCitation: (text: String, locationLabel: String) -> Unit = { _, _ -> },
  onShareSelection: (text: String, locationLabel: String) -> Unit = { _, _ -> },
  onPageChanged: (pageIndex: Int, overallProgress: Float) -> Unit,
  modifier: Modifier = Modifier
) {
  val colorFilter = remember(preferences.readerTheme, preferences.pdfNightFilter) {
    when {
      (preferences.readerTheme == ReaderTheme.NIGHT || preferences.readerTheme == ReaderTheme.EBONY) && preferences.pdfNightFilter -> {
        ColorFilter.colorMatrix(
          ColorMatrix(
            floatArrayOf(
              -0.88f, 0f, 0f, 0f, 232f,
              0f, -0.88f, 0f, 0f, 228f,
              0f, 0f, -0.85f, 0f, 218f,
              0f, 0f, 0f, 1f, 0f
            )
          )
        )
      }
      preferences.readerTheme == ReaderTheme.SEPIA -> {
        ColorFilter.colorMatrix(
          ColorMatrix(
            floatArrayOf(
              0.96f, 0f, 0f, 0f, 0f,
              0f, 0.90f, 0f, 0f, 0f,
              0f, 0f, 0.78f, 0f, 0f,
              0f, 0f, 0f, 1f, 0f
            )
          )
        )
      }
      else -> null
    }
  }

  val notesByPage = remember(highlights) {
    highlights.groupBy { it.unitIndex }
  }

  if (preferences.pdfScrollMode) {
    PdfScrollReader(
      bookTitle = bookTitle,
      pageCount = pageCount,
      session = session,
      currentPageIndex = currentPageIndex,
      surfaceColors = surfaceColors,
      colorFilter = colorFilter,
      notesByPage = notesByPage,
      isAutoScrolling = isAutoScrolling,
      autoScrollSpeed = autoScrollSpeed,
      topOverlayClearanceDp = topOverlayClearanceDp,
      onToggleControls = onToggleControls,
      onOpenFragmentTools = onOpenFragmentTools,
      onCopyPlainText = onCopyPlainText,
      onCopyCitation = onCopyCitation,
      onShareSelection = onShareSelection,
      onPageChanged = onPageChanged,
      modifier = modifier
    )
  } else {
    PdfPaginatedReader(
      bookTitle = bookTitle,
      pageCount = pageCount,
      session = session,
      currentPageIndex = currentPageIndex,
      surfaceColors = surfaceColors,
      colorFilter = colorFilter,
      notesByPage = notesByPage,
      topOverlayClearanceDp = topOverlayClearanceDp,
      onToggleControls = onToggleControls,
      onOpenFragmentTools = onOpenFragmentTools,
      onCopyPlainText = onCopyPlainText,
      onCopyCitation = onCopyCitation,
      onShareSelection = onShareSelection,
      onPageChanged = onPageChanged,
      modifier = modifier
    )
  }
}

@Composable
private fun PdfPaginatedReader(
  bookTitle: String,
  pageCount: Int,
  session: PdfBookSession,
  currentPageIndex: Int,
  surfaceColors: ReaderSurfaceColors,
  colorFilter: ColorFilter?,
  notesByPage: Map<Int, List<HighlightEntity>>,
  topOverlayClearanceDp: androidx.compose.ui.unit.Dp,
  onToggleControls: () -> Unit,
  onOpenFragmentTools: (pageIndex: Int, startWordIndex: Int, endWordExclusive: Int, text: String) -> Unit,
  onCopyPlainText: (String) -> Unit,
  onCopyCitation: (text: String, locationLabel: String) -> Unit,
  onShareSelection: (text: String, locationLabel: String) -> Unit,
  onPageChanged: (pageIndex: Int, overallProgress: Float) -> Unit,
  modifier: Modifier = Modifier
) {
  val safeCount = pageCount.coerceAtLeast(1)
  val density = LocalDensity.current
  val pagerState = rememberPagerState(
    initialPage = currentPageIndex.coerceIn(0, safeCount - 1)
  ) { safeCount }

  var isZoomed by remember { mutableStateOf(false) }
  var activeSelection by remember { mutableStateOf<PdfWordSelection?>(null) }
  val pageTextCache = remember(session) { mutableStateMapOf<Int, PdfPageTextData>() }
  val pageMetricsMap = remember(session) { mutableStateMapOf<Int, PdfPageRenderMetrics>() }

  var overlayContainerSize by remember { mutableStateOf(IntSize.Zero) }
  var overlayContainerPosInRoot by remember { mutableStateOf(Offset.Zero) }

  val handleHorizontalOffsetPx = with(density) { 8.dp.toPx() }
  val topSafePx = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().let {
    with(density) { (it + topOverlayClearanceDp).toPx() }
  }
  val bottomSafePx = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().let {
    with(density) { it.toPx() }
  }

  LaunchedEffect(currentPageIndex) {
    val target = currentPageIndex.coerceIn(0, safeCount - 1)
    if (pagerState.currentPage != target) {
      pagerState.scrollToPage(target)
    }
  }

  LaunchedEffect(pagerState.currentPage) {
    activeSelection = activeSelection?.takeIf { it.pageIndex == pagerState.currentPage }
    val prog = (pagerState.currentPage + 1).toFloat() / safeCount.toFloat()
    onPageChanged(pagerState.currentPage, prog)
  }

  val latestSelection by rememberUpdatedState(activeSelection)

  Box(
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
              activeSelection = null
            }
          }
        )
      }
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.statusBars)
        .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
      BoxWithConstraints(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
      ) {
        val targetWidthPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(720)

        HorizontalPager(
          state = pagerState,
          userScrollEnabled = !isZoomed && activeSelection == null,
          modifier = Modifier
            .fillMaxSize()
            .testTag("pdf_horizontal_pager")
        ) { pageIdx ->
          PdfSinglePageView(
            pageIndex = pageIdx,
            targetWidthPx = targetWidthPx,
            session = session,
            surfaceColors = surfaceColors,
            colorFilter = colorFilter,
            pageNotes = notesByPage[pageIdx].orEmpty(),
            activeSelection = activeSelection?.takeIf { it.pageIndex == pageIdx },
            onPageTextLoaded = { data -> pageTextCache[pageIdx] = data },
            onMetricsUpdated = { metrics ->
              if (pageMetricsMap[pageIdx] != metrics) pageMetricsMap[pageIdx] = metrics
            },
            onRemoveMetrics = { pageMetricsMap.remove(pageIdx) },
            onTapPage = {
              if (activeSelection != null) {
                activeSelection = null
              } else {
                onToggleControls()
              }
            },
            onOpenPageNotes = {
              val noteExcerpt = notesByPage[pageIdx]?.firstOrNull()?.excerpt
                ?.takeIf { it.isNotBlank() }
                ?: "$bookTitle · Página ${pageIdx + 1}"
              onOpenFragmentTools(pageIdx, -1, -1, noteExcerpt)
            },
            onSelectWord = { wordIdx ->
              val data = pageTextCache[pageIdx]
              if (data != null && !data.isScanned && wordIdx in data.words.indices) {
                activeSelection = PdfWordSelection(pageIdx, wordIdx, wordIdx)
              }
            },
            onZoomStateChanged = { zoomed ->
              if (pagerState.currentPage == pageIdx) {
                isZoomed = zoomed
              }
            }
          )
        }
      }

      // Subtle footer
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = bookTitle,
          style = MaterialTheme.typography.labelSmall,
          color = surfaceColors.secondaryText,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        Text(
          text = "Pág. ${pagerState.currentPage + 1} de $safeCount",
          style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
          color = surfaceColors.secondaryText
        )
      }
    }

    val sel = activeSelection
    val activePageText = sel?.let { pageTextCache[it.pageIndex] }
    val activeMetrics = sel?.let { pageMetricsMap[it.pageIndex] }
    if (sel != null && activePageText != null && !activePageText.isScanned && activeMetrics != null) {
      val selectedText = remember(sel, activePageText) {
        extractSelectedPdfText(activePageText.words, sel)
      }
      val geometry = computePdfSelectionGeometry(
        selection = sel,
        pageText = activePageText,
        metrics = activeMetrics,
        overlayContainerPosInRoot = overlayContainerPosInRoot,
        handleHorizontalOffsetPx = handleHorizontalOffsetPx
      )
      val locationLabel = "pág. ${sel.pageIndex + 1}"
      val isAStart = sel.anchorWordA <= sel.anchorWordB

      if (selectedText.isNotEmpty()) {
        ReaderSelectionOverlay(
          containerSize = overlayContainerSize,
          topSafeInsetPx = topSafePx,
          bottomSafeInsetPx = bottomSafePx,
          surfaceColors = surfaceColors,
          handlesVisible = !activePageText.isScanned && (geometry.anchorAPos != null || geometry.anchorBPos != null),
          menuVisible = geometry.selectionBounds != null,
          anchorAPos = geometry.anchorAPos,
          anchorBPos = geometry.anchorBPos,
          selectionBounds = geometry.selectionBounds,
          copyEnabled = !activePageText.isScanned,
          highlightEnabled = !activePageText.isScanned,
          onDragAnchorA = { ptr ->
            val xAdj = if (isAStart) handleHorizontalOffsetPx else -handleHorizontalOffsetPx
            val norm = overlayPointToPdfNorm(ptr + Offset(xAdj, 0f), activeMetrics, overlayContainerPosInRoot)
            val hitWord = findNearestPdfWordIndex(activePageText.words, norm.x, norm.y)
            if (hitWord != null) {
              activeSelection = sel.copy(anchorWordA = hitWord)
            }
          },
          onDragAnchorB = { ptr ->
            val xAdj = if (isAStart) -handleHorizontalOffsetPx else handleHorizontalOffsetPx
            val norm = overlayPointToPdfNorm(ptr + Offset(xAdj, 0f), activeMetrics, overlayContainerPosInRoot)
            val hitWord = findNearestPdfWordIndex(activePageText.words, norm.x, norm.y)
            if (hitWord != null) {
              activeSelection = sel.copy(anchorWordB = hitWord)
            }
          },
          onCopyPlain = {
            if (!activePageText.isScanned) {
              onCopyPlainText(selectedText)
              activeSelection = null
            }
          },
          onCopyCitation = {
            if (!activePageText.isScanned) {
              onCopyCitation(selectedText, locationLabel)
              activeSelection = null
            }
          },
          onHighlight = {
            if (!activePageText.isScanned) {
              onOpenFragmentTools(sel.pageIndex, sel.startWord, sel.endWord + 1, selectedText)
              activeSelection = null
            }
          },
          onShare = {
            if (!activePageText.isScanned) {
              onShareSelection(selectedText, locationLabel)
              activeSelection = null
            }
          }
        )
      }
    }
  }
}

@Composable
private fun PdfSinglePageView(
  pageIndex: Int,
  targetWidthPx: Int,
  session: PdfBookSession,
  surfaceColors: ReaderSurfaceColors,
  colorFilter: ColorFilter?,
  pageNotes: List<HighlightEntity>,
  activeSelection: PdfWordSelection?,
  onPageTextLoaded: (PdfPageTextData) -> Unit,
  onMetricsUpdated: (PdfPageRenderMetrics) -> Unit,
  onRemoveMetrics: () -> Unit,
  onTapPage: () -> Unit,
  onOpenPageNotes: () -> Unit,
  onSelectWord: (wordIndex: Int) -> Unit,
  onZoomStateChanged: (Boolean) -> Unit
) {
  var bitmap by remember(pageIndex, targetWidthPx) { mutableStateOf<Bitmap?>(null) }
  var zoomTile by remember(pageIndex) { mutableStateOf<PdfZoomTile?>(null) }
  var pageText by remember(pageIndex) { mutableStateOf<PdfPageTextData?>(null) }
  var scale by remember(pageIndex) { mutableFloatStateOf(1f) }
  var offset by remember(pageIndex) { mutableStateOf(Offset.Zero) }
  var viewportSize by remember(pageIndex) { mutableStateOf(IntSize.Zero) }
  var viewportPosInRoot by remember(pageIndex) { mutableStateOf(Offset.Zero) }
  var basePageSizePx by remember(pageIndex) { mutableStateOf(Size.Zero) }

  val latestPageText by rememberUpdatedState(pageText)
  val latestScale by rememberUpdatedState(scale)
  val latestOffset by rememberUpdatedState(offset)
  val latestViewportSize by rememberUpdatedState(viewportSize)
  val latestBasePageSizePx by rememberUpdatedState(basePageSizePx)
  val latestSelection by rememberUpdatedState(activeSelection)
  val latestOnTapPage by rememberUpdatedState(onTapPage)
  val latestOnSelectWord by rememberUpdatedState(onSelectWord)

  DisposableEffect(pageIndex) {
    onDispose { onRemoveMetrics() }
  }

  LaunchedEffect(pageIndex, targetWidthPx) {
    bitmap = session.renderPage(pageIndex, targetWidthPx)
  }

  LaunchedEffect(pageIndex, session) {
    val data = session.extractPageText(pageIndex)
    pageText = data
    onPageTextLoaded(data)
  }

  LaunchedEffect(scale) {
    onZoomStateChanged(scale > 1.05f)
  }

  // Re-render crisp visible region dynamically whenever zoomed > 1.05x
  LaunchedEffect(pageIndex, scale, offset, basePageSizePx, viewportSize) {
    if (scale <= 1.05f || basePageSizePx.width <= 0f || basePageSizePx.height <= 0f || viewportSize.width <= 0) {
      zoomTile = null
      return@LaunchedEffect
    }
    val visibleRegion = computePdfZoomTileRegion(
      viewportWidthPx = viewportSize.width.toFloat(),
      viewportHeightPx = viewportSize.height.toFloat(),
      pageWidthPx = basePageSizePx.width,
      pageHeightPx = basePageSizePx.height,
      scale = scale,
      panOffsetX = offset.x,
      panOffsetY = offset.y,
      overscanFraction = 0f
    ) ?: return@LaunchedEffect

    val existing = zoomTile
    if (existing != null &&
      existing.pageIndex == pageIndex &&
      existing.region.contains(visibleRegion) &&
      abs(existing.renderedScale - scale) / scale <= 0.14f
    ) {
      return@LaunchedEffect
    }

    val paddedRegion = computePdfZoomTileRegion(
      viewportWidthPx = viewportSize.width.toFloat(),
      viewportHeightPx = viewportSize.height.toFloat(),
      pageWidthPx = basePageSizePx.width,
      pageHeightPx = basePageSizePx.height,
      scale = scale,
      panOffsetX = offset.x,
      panOffsetY = offset.y,
      overscanFraction = 0.18f
    ) ?: return@LaunchedEffect

    delay(55L)
    val rendered = session.renderPageRegion(
      pageIndex = pageIndex,
      region = paddedRegion,
      fullPageTargetWidthPx = basePageSizePx.width * scale,
      renderedScale = scale
    )
    if (rendered != null) {
      zoomTile = rendered
    }
  }

  SideEffect {
    if (basePageSizePx.width > 0f && basePageSizePx.height > 0f && viewportSize.width > 0) {
      onMetricsUpdated(
        PdfPageRenderMetrics(
          pageIndex = pageIndex,
          viewportPosInRoot = viewportPosInRoot,
          viewportSize = viewportSize,
          basePageSizePx = basePageSizePx,
          scale = scale,
          panOffset = offset
        )
      )
    }
  }

  BoxWithConstraints(
    modifier = Modifier
      .fillMaxSize()
      .onGloballyPositioned { coords ->
        viewportSize = coords.size
        viewportPosInRoot = coords.positionInRoot()
      }
      .pointerInput(pageIndex) {
        detectSingleFingerTapAndLongPress(
          shouldSkipDoubleTap = { latestSelection != null },
          onTap = { latestOnTapPage() },
          onLongPress = { tapOffset ->
            val data = latestPageText
            val curBase = latestBasePageSizePx
            val curView = latestViewportSize
            val curScale = latestScale
            val curOffset = latestOffset
            if (data != null && !data.isScanned && curBase.width > 0f && curBase.height > 0f) {
              val localCenterX = (tapOffset.x - curView.width * 0.5f - curOffset.x) / curScale
              val localCenterY = (tapOffset.y - curView.height * 0.5f - curOffset.y) / curScale
              val nx = ((localCenterX + curBase.width * 0.5f) / curBase.width).coerceIn(0f, 1f)
              val ny = ((localCenterY + curBase.height * 0.5f) / curBase.height).coerceIn(0f, 1f)
              val wordIdx = findNearestPdfWordIndex(data.words, nx, ny)
              if (wordIdx != null) {
                latestOnSelectWord(wordIdx)
              }
            }
          },
          onDoubleTap = {
            if (latestScale > 1.1f) {
              scale = 1f
              offset = Offset.Zero
            } else {
              scale = 2.2f
            }
          }
        )
      }
      .pointerInput(pageIndex) {
        // Supports multi-touch pinch zoom from scale=1f AND single-finger pan while zoomed,
        // without stealing single-finger horizontal page swipes when unzoomed.
        awaitEachGesture {
          awaitFirstDown(requireUnconsumed = false)
          var pastTouchSlop = false
          val touchSlop = viewConfiguration.touchSlop
          var panAccum = Offset.Zero
          var zoomAccum = 1f

          do {
            val event = awaitPointerEvent()
            val pointerCount = event.changes.count { it.pressed }
            val canceled = event.changes.any { it.isConsumed }
            if (canceled) break

            val zoomChange = event.calculateZoom()
            val panChange = event.calculatePan()

            if (!pastTouchSlop) {
              zoomAccum *= zoomChange
              panAccum += panChange
              val centroidSize = event.calculateCentroidSize(useCurrent = false)
              val zoomMotion = abs(1f - zoomAccum) * centroidSize
              val panMotion = panAccum.getDistance()
              if (pointerCount >= 2 && (zoomMotion > touchSlop || panMotion > touchSlop)) {
                pastTouchSlop = true
              } else if (pointerCount == 1 && scale > 1.05f && panMotion > touchSlop) {
                pastTouchSlop = true
              }
            }

            if (pastTouchSlop && (pointerCount >= 2 || scale > 1.05f)) {
              val newScale = (scale * zoomChange).coerceIn(1f, 5f)
              scale = newScale
              if (newScale <= 1.02f) {
                scale = 1f
                offset = Offset.Zero
              } else {
                val maxPanX = (size.width * (newScale - 1f)) / 2f
                val maxPanY = (size.height * (newScale - 1f)) / 2f
                offset = Offset(
                  x = (offset.x + panChange.x).coerceIn(-maxPanX, maxPanX),
                  y = (offset.y + panChange.y).coerceIn(-maxPanY, maxPanY)
                )
              }
              event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
          } while (event.changes.any { it.pressed })
        }
      },
    contentAlignment = Alignment.Center
  ) {
    val bmp = bitmap
    if (bmp == null) {
      CircularProgressIndicator(color = surfaceColors.accent)
    } else {
      val density = LocalDensity.current
      val availW = (maxWidth - 20.dp).coerceAtLeast(120.dp)
      val availH = (maxHeight - 12.dp).coerceAtLeast(160.dp)
      val bmpAspect = bmp.width.toFloat() / bmp.height.coerceAtLeast(1).toFloat()
      val containerAspect = availW.value / availH.value
      val (pageWDp, pageHDp) = if (bmpAspect > containerAspect) {
        availW to (availW / bmpAspect)
      } else {
        (availH * bmpAspect) to availH
      }

      val pageWPx = with(density) { pageWDp.toPx() }
      val pageHPx = with(density) { pageHDp.toPx() }
      basePageSizePx = Size(pageWPx, pageHPx)

      Surface(
        shadowElevation = 4.dp,
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier
          .size(pageWDp, pageHDp)
          .graphicsLayer(
            scaleX = scale,
            scaleY = scale,
            translationX = offset.x,
            translationY = offset.y
          )
      ) {
        Box(modifier = Modifier.fillMaxSize()) {
          Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "Página ${pageIndex + 1} del PDF",
            contentScale = ContentScale.FillBounds,
            colorFilter = colorFilter,
            modifier = Modifier.fillMaxSize()
          )

          val activeTile = zoomTile
          if (activeTile != null && scale > 1.05f) {
            val tileImage = remember(activeTile) { activeTile.bitmap.asImageBitmap() }
            Canvas(modifier = Modifier.fillMaxSize()) {
              val dstLeft = activeTile.region.left * size.width
              val dstTop = activeTile.region.top * size.height
              val dstW = (activeTile.region.width * size.width).coerceAtLeast(1f)
              val dstH = (activeTile.region.height * size.height).coerceAtLeast(1f)
              withTransform({
                translate(left = dstLeft, top = dstTop)
                scale(
                  scaleX = dstW / tileImage.width.coerceAtLeast(1).toFloat(),
                  scaleY = dstH / tileImage.height.coerceAtLeast(1).toFloat(),
                  pivot = Offset.Zero
                )
              }) {
                drawImage(
                  image = tileImage,
                  topLeft = Offset.Zero,
                  colorFilter = colorFilter
                )
              }
            }
          }

          val sel = activeSelection
          val data = pageText
          val savedHighlights = remember(pageNotes) {
            pageNotes.filter { !it.isStickyNoteOnly && it.startCharOffset >= 0 && it.endCharOffset > it.startCharOffset }
          }
          if (data != null && !data.isScanned && data.words.isNotEmpty() && (sel != null || savedHighlights.isNotEmpty())) {
            val highlightTint = surfaceColors.accent.copy(alpha = 0.30f)

            Canvas(modifier = Modifier.fillMaxSize()) {
              for (hl in savedHighlights) {
                val hlTint = if (surfaceColors.isDark) hl.color.washDark else hl.color.washLight
                val hs = hl.startCharOffset.coerceIn(0, data.words.lastIndex)
                val he = (hl.endCharOffset - 1).coerceIn(hs, data.words.lastIndex)
                for (idx in hs..he) {
                  val r = data.words[idx].rect
                  drawRoundRect(
                    color = hlTint,
                    topLeft = Offset(r.left * size.width, r.top * size.height),
                    size = Size(
                      width = (r.width * size.width).coerceAtLeast(2f),
                      height = (r.height * size.height).coerceAtLeast(2f)
                    ),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                  )
                }
              }
              if (sel != null) {
                val s = sel.startWord.coerceIn(0, data.words.lastIndex)
                val e = sel.endWord.coerceIn(s, data.words.lastIndex)
                for (idx in s..e) {
                  val r = data.words[idx].rect
                  drawRoundRect(
                    color = highlightTint,
                    topLeft = Offset(r.left * size.width, r.top * size.height),
                    size = Size(
                      width = (r.width * size.width).coerceAtLeast(2f),
                      height = (r.height * size.height).coerceAtLeast(2f)
                    ),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                  )
                }
              }
            }
          }
        }
      }
    }

    if (pageNotes.isNotEmpty()) {
      Surface(
        onClick = onOpenPageNotes,
        color = pageNotes.first().color.dotColor,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier
          .align(Alignment.TopEnd)
          .padding(16.dp)
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(
            imageVector = Icons.Default.StickyNote2,
            contentDescription = "Notas en esta página",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(14.dp)
          )
          Spacer(Modifier.width(4.dp))
          Text(
            text = "${pageNotes.size}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary
          )
        }
      }
    }
  }
}

@Composable
private fun PdfScrollReader(
  bookTitle: String,
  pageCount: Int,
  session: PdfBookSession,
  currentPageIndex: Int,
  surfaceColors: ReaderSurfaceColors,
  colorFilter: ColorFilter?,
  notesByPage: Map<Int, List<HighlightEntity>>,
  isAutoScrolling: Boolean,
  autoScrollSpeed: Float,
  topOverlayClearanceDp: androidx.compose.ui.unit.Dp,
  onToggleControls: () -> Unit,
  onOpenFragmentTools: (pageIndex: Int, startWordIndex: Int, endWordExclusive: Int, text: String) -> Unit,
  onCopyPlainText: (String) -> Unit,
  onCopyCitation: (text: String, locationLabel: String) -> Unit,
  onShareSelection: (text: String, locationLabel: String) -> Unit,
  onPageChanged: (pageIndex: Int, overallProgress: Float) -> Unit,
  modifier: Modifier = Modifier
) {
  val safeCount = pageCount.coerceAtLeast(1)
  val density = LocalDensity.current
  val listState = rememberLazyListState(
    initialFirstVisibleItemIndex = currentPageIndex.coerceIn(0, safeCount - 1)
  )

  var activeSelection by remember { mutableStateOf<PdfWordSelection?>(null) }
  val pageTextCache = remember(session) { mutableStateMapOf<Int, PdfPageTextData>() }
  val pageMetricsMap = remember(session) { mutableStateMapOf<Int, PdfPageRenderMetrics>() }

  var overlayContainerSize by remember { mutableStateOf(IntSize.Zero) }
  var overlayContainerPosInRoot by remember { mutableStateOf(Offset.Zero) }

  val handleHorizontalOffsetPx = with(density) { 8.dp.toPx() }
  val topSafePx = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().let {
    with(density) { (it + topOverlayClearanceDp).toPx() }
  }
  val bottomSafePx = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().let {
    with(density) { it.toPx() }
  }

  LaunchedEffect(currentPageIndex) {
    val target = currentPageIndex.coerceIn(0, safeCount - 1)
    if (abs(listState.firstVisibleItemIndex - target) > 1) {
      listState.scrollToItem(target)
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

  LaunchedEffect(listState) {
    snapshotFlow { listState.firstVisibleItemIndex }
      .distinctUntilChanged()
      .collect { idx ->
        val prog = (idx + 1).toFloat() / safeCount.toFloat()
        onPageChanged(idx, prog)
      }
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
    Column(
      modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.statusBars)
        .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
      BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
        val targetWidthPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(720)

        val latestActiveSelection by rememberUpdatedState(activeSelection)
        val latestOnToggleControls by rememberUpdatedState(onToggleControls)

        LazyColumn(
          state = listState,
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
          modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
              detectSingleFingerTapAndLongPress(
                onTap = {
                  if (latestActiveSelection != null) activeSelection = null else latestOnToggleControls()
                }
              )
            }
            .testTag("pdf_scroll_list")
        ) {
          items(safeCount, key = { it }) { pageIdx ->
            PdfScrollPageCard(
              pageIndex = pageIdx,
              targetWidthPx = targetWidthPx,
              session = session,
              colorFilter = colorFilter,
              surfaceColors = surfaceColors,
              pageNotes = notesByPage[pageIdx].orEmpty(),
              activeSelection = activeSelection?.takeIf { it.pageIndex == pageIdx },
              onPageTextLoaded = { data -> pageTextCache[pageIdx] = data },
              onMetricsUpdated = { metrics ->
                if (pageMetricsMap[pageIdx] != metrics) pageMetricsMap[pageIdx] = metrics
              },
              onRemoveMetrics = { pageMetricsMap.remove(pageIdx) },
              onTapPage = {
                if (activeSelection != null) activeSelection = null else onToggleControls()
              },
              onSelectWord = { wordIdx ->
                val data = pageTextCache[pageIdx]
                if (data != null && !data.isScanned && wordIdx in data.words.indices) {
                  activeSelection = PdfWordSelection(pageIdx, wordIdx, wordIdx)
                }
              }
            )
          }
        }
      }

      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Text(
          text = bookTitle,
          style = MaterialTheme.typography.labelSmall,
          color = surfaceColors.secondaryText,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f)
        )
        Text(
          text = "Pág. ${listState.firstVisibleItemIndex + 1} de $safeCount",
          style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
          color = surfaceColors.secondaryText
        )
      }
    }

    val sel = activeSelection
    val activePageText = sel?.let { pageTextCache[it.pageIndex] }
    val activeMetrics = sel?.let { pageMetricsMap[it.pageIndex] }
    if (sel != null && activePageText != null && !activePageText.isScanned && activeMetrics != null) {
      val selectedText = remember(sel, activePageText) {
        extractSelectedPdfText(activePageText.words, sel)
      }
      val geometry = computePdfSelectionGeometry(
        selection = sel,
        pageText = activePageText,
        metrics = activeMetrics,
        overlayContainerPosInRoot = overlayContainerPosInRoot,
        handleHorizontalOffsetPx = handleHorizontalOffsetPx
      )
      val locationLabel = "pág. ${sel.pageIndex + 1}"
      val isAStart = sel.anchorWordA <= sel.anchorWordB

      if (selectedText.isNotEmpty()) {
        ReaderSelectionOverlay(
          containerSize = overlayContainerSize,
          topSafeInsetPx = topSafePx,
          bottomSafeInsetPx = bottomSafePx,
          surfaceColors = surfaceColors,
          handlesVisible = !activePageText.isScanned && (geometry.anchorAPos != null || geometry.anchorBPos != null),
          menuVisible = geometry.selectionBounds != null,
          anchorAPos = geometry.anchorAPos,
          anchorBPos = geometry.anchorBPos,
          selectionBounds = geometry.selectionBounds,
          copyEnabled = !activePageText.isScanned,
          highlightEnabled = !activePageText.isScanned,
          onDragAnchorA = { ptr ->
            val xAdj = if (isAStart) handleHorizontalOffsetPx else -handleHorizontalOffsetPx
            val norm = overlayPointToPdfNorm(ptr + Offset(xAdj, 0f), activeMetrics, overlayContainerPosInRoot)
            val hitWord = findNearestPdfWordIndex(activePageText.words, norm.x, norm.y)
            if (hitWord != null) {
              activeSelection = sel.copy(anchorWordA = hitWord)
            }
          },
          onDragAnchorB = { ptr ->
            val xAdj = if (isAStart) -handleHorizontalOffsetPx else handleHorizontalOffsetPx
            val norm = overlayPointToPdfNorm(ptr + Offset(xAdj, 0f), activeMetrics, overlayContainerPosInRoot)
            val hitWord = findNearestPdfWordIndex(activePageText.words, norm.x, norm.y)
            if (hitWord != null) {
              activeSelection = sel.copy(anchorWordB = hitWord)
            }
          },
          onCopyPlain = {
            if (!activePageText.isScanned) {
              onCopyPlainText(selectedText)
              activeSelection = null
            }
          },
          onCopyCitation = {
            if (!activePageText.isScanned) {
              onCopyCitation(selectedText, locationLabel)
              activeSelection = null
            }
          },
          onHighlight = {
            if (!activePageText.isScanned) {
              onOpenFragmentTools(sel.pageIndex, sel.startWord, sel.endWord + 1, selectedText)
              activeSelection = null
            }
          },
          onShare = {
            if (!activePageText.isScanned) {
              onShareSelection(selectedText, locationLabel)
              activeSelection = null
            }
          }
        )
      }
    }
  }
}

@Composable
private fun PdfScrollPageCard(
  pageIndex: Int,
  targetWidthPx: Int,
  session: PdfBookSession,
  colorFilter: ColorFilter?,
  surfaceColors: ReaderSurfaceColors,
  pageNotes: List<HighlightEntity>,
  activeSelection: PdfWordSelection?,
  onPageTextLoaded: (PdfPageTextData) -> Unit,
  onMetricsUpdated: (PdfPageRenderMetrics) -> Unit,
  onRemoveMetrics: () -> Unit,
  onTapPage: () -> Unit,
  onSelectWord: (wordIndex: Int) -> Unit
) {
  var bitmap by remember(pageIndex, targetWidthPx) { mutableStateOf<Bitmap?>(null) }
  var zoomTile by remember(pageIndex) { mutableStateOf<PdfZoomTile?>(null) }
  var pageText by remember(pageIndex) { mutableStateOf<PdfPageTextData?>(null) }
  var scale by remember(pageIndex) { mutableFloatStateOf(1f) }
  var panOffset by remember(pageIndex) { mutableStateOf(Offset.Zero) }
  var cardPosInRoot by remember(pageIndex) { mutableStateOf(Offset.Zero) }
  var cardSize by remember(pageIndex) { mutableStateOf(IntSize.Zero) }

  val latestPageText by rememberUpdatedState(pageText)
  val latestScale by rememberUpdatedState(scale)
  val latestPanOffset by rememberUpdatedState(panOffset)
  val latestCardSize by rememberUpdatedState(cardSize)
  val latestSelection by rememberUpdatedState(activeSelection)
  val latestOnTapPage by rememberUpdatedState(onTapPage)
  val latestOnSelectWord by rememberUpdatedState(onSelectWord)

  DisposableEffect(pageIndex) {
    onDispose { onRemoveMetrics() }
  }

  LaunchedEffect(pageIndex, targetWidthPx) {
    bitmap = session.renderPage(pageIndex, targetWidthPx)
  }

  LaunchedEffect(pageIndex, session) {
    val data = session.extractPageText(pageIndex)
    pageText = data
    onPageTextLoaded(data)
  }

  LaunchedEffect(pageIndex, scale, panOffset, cardSize) {
    if (scale <= 1.05f || cardSize.width <= 0 || cardSize.height <= 0) {
      zoomTile = null
      return@LaunchedEffect
    }
    val w = cardSize.width.toFloat()
    val h = cardSize.height.toFloat()
    val visibleRegion = computePdfZoomTileRegion(
      viewportWidthPx = w,
      viewportHeightPx = h,
      pageWidthPx = w,
      pageHeightPx = h,
      scale = scale,
      panOffsetX = panOffset.x,
      panOffsetY = panOffset.y,
      overscanFraction = 0f
    ) ?: return@LaunchedEffect

    val existing = zoomTile
    if (existing != null &&
      existing.pageIndex == pageIndex &&
      existing.region.contains(visibleRegion) &&
      abs(existing.renderedScale - scale) / scale <= 0.14f
    ) {
      return@LaunchedEffect
    }

    val paddedRegion = computePdfZoomTileRegion(
      viewportWidthPx = w,
      viewportHeightPx = h,
      pageWidthPx = w,
      pageHeightPx = h,
      scale = scale,
      panOffsetX = panOffset.x,
      panOffsetY = panOffset.y,
      overscanFraction = 0.18f
    ) ?: return@LaunchedEffect

    delay(55L)
    val rendered = session.renderPageRegion(
      pageIndex = pageIndex,
      region = paddedRegion,
      fullPageTargetWidthPx = w * scale,
      renderedScale = scale
    )
    if (rendered != null) {
      zoomTile = rendered
    }
  }

  SideEffect {
    if (cardSize.width > 0 && cardSize.height > 0) {
      onMetricsUpdated(
        PdfPageRenderMetrics(
          pageIndex = pageIndex,
          viewportPosInRoot = cardPosInRoot,
          viewportSize = cardSize,
          basePageSizePx = Size(cardSize.width.toFloat(), cardSize.height.toFloat()),
          scale = scale,
          panOffset = panOffset
        )
      )
    }
  }

  val bmp = bitmap
  val aspect = if (bmp != null) {
    bmp.width.toFloat() / bmp.height.coerceAtLeast(1).toFloat()
  } else {
    0.707f
  }

  Surface(
    shadowElevation = 2.dp,
    shape = RoundedCornerShape(6.dp),
    color = surfaceColors.surface,
    modifier = Modifier
      .fillMaxWidth()
      .aspectRatio(aspect)
      .clip(RoundedCornerShape(6.dp))
      .onGloballyPositioned { coords ->
        cardPosInRoot = coords.positionInRoot()
        cardSize = coords.size
      }
      .pointerInput(pageIndex) {
        detectSingleFingerTapAndLongPress(
          shouldSkipDoubleTap = { latestSelection != null },
          onTap = { latestOnTapPage() },
          onDoubleTap = {
            if (latestScale > 1.1f) {
              scale = 1f
              panOffset = Offset.Zero
            } else {
              scale = 2.2f
            }
          },
          onLongPress = { tapOffset ->
            val data = latestPageText
            val curCard = latestCardSize
            val curScale = latestScale
            val curPan = latestPanOffset
            if (data != null && !data.isScanned && curCard.width > 0 && curCard.height > 0) {
              val w = curCard.width.toFloat()
              val h = curCard.height.toFloat()
              val localCenterX = (tapOffset.x - w * 0.5f - curPan.x) / curScale
              val localCenterY = (tapOffset.y - h * 0.5f - curPan.y) / curScale
              val nx = ((localCenterX + w * 0.5f) / w).coerceIn(0f, 1f)
              val ny = ((localCenterY + h * 0.5f) / h).coerceIn(0f, 1f)
              val wordIdx = findNearestPdfWordIndex(data.words, nx, ny)
              if (wordIdx != null) {
                latestOnSelectWord(wordIdx)
              }
            }
          }
        )
      }
      .pointerInput(pageIndex) {
        awaitEachGesture {
          awaitFirstDown(requireUnconsumed = false)
          var pastTouchSlop = false
          val touchSlop = viewConfiguration.touchSlop
          var zoomAccum = 1f
          var panAccum = Offset.Zero

          do {
            val event = awaitPointerEvent()
            val pointerCount = event.changes.count { it.pressed }
            if (event.changes.any { it.isConsumed }) break

            val zoomChange = event.calculateZoom()
            val panChange = event.calculatePan()

            if (!pastTouchSlop) {
              zoomAccum *= zoomChange
              panAccum += panChange
              val centroidSize = event.calculateCentroidSize(useCurrent = false)
              if (pointerCount >= 2 && (abs(1f - zoomAccum) * centroidSize > touchSlop || panAccum.getDistance() > touchSlop)) {
                pastTouchSlop = true
              } else if (pointerCount == 1 && scale > 1.05f && panAccum.getDistance() > touchSlop) {
                pastTouchSlop = true
              }
            }

            if (pastTouchSlop && (pointerCount >= 2 || scale > 1.05f)) {
              val newScale = (scale * zoomChange).coerceIn(1f, 5f)
              scale = newScale
              if (newScale <= 1.02f) {
                scale = 1f
                panOffset = Offset.Zero
              } else {
                val maxPanX = (size.width * (newScale - 1f)) / 2f
                val maxPanY = (size.height * (newScale - 1f)) / 2f
                panOffset = Offset(
                  x = (panOffset.x + panChange.x).coerceIn(-maxPanX, maxPanX),
                  y = (panOffset.y + panChange.y).coerceIn(-maxPanY, maxPanY)
                )
              }
              event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
          } while (event.changes.any { it.pressed })
        }
      }
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .graphicsLayer(
          scaleX = scale,
          scaleY = scale,
          translationX = panOffset.x,
          translationY = panOffset.y
        )
    ) {
      if (bmp == null) {
        Box(
          modifier = Modifier.fillMaxSize(),
          contentAlignment = Alignment.Center
        ) {
          CircularProgressIndicator(color = surfaceColors.accent)
        }
      } else {
        Image(
          bitmap = bmp.asImageBitmap(),
          contentDescription = "Página ${pageIndex + 1}",
          contentScale = ContentScale.FillBounds,
          colorFilter = colorFilter,
          modifier = Modifier.fillMaxSize()
        )

        val activeTile = zoomTile
        if (activeTile != null && scale > 1.05f) {
          val tileImage = remember(activeTile) { activeTile.bitmap.asImageBitmap() }
          Canvas(modifier = Modifier.fillMaxSize()) {
            val dstLeft = activeTile.region.left * size.width
            val dstTop = activeTile.region.top * size.height
            val dstW = (activeTile.region.width * size.width).coerceAtLeast(1f)
            val dstH = (activeTile.region.height * size.height).coerceAtLeast(1f)
            withTransform({
              translate(left = dstLeft, top = dstTop)
              scale(
                scaleX = dstW / tileImage.width.coerceAtLeast(1).toFloat(),
                scaleY = dstH / tileImage.height.coerceAtLeast(1).toFloat(),
                pivot = Offset.Zero
              )
            }) {
              drawImage(
                image = tileImage,
                topLeft = Offset.Zero,
                colorFilter = colorFilter
              )
            }
          }
        }

        val sel = activeSelection
        val data = pageText
        val savedHighlights = remember(pageNotes) {
          pageNotes.filter { !it.isStickyNoteOnly && it.startCharOffset >= 0 && it.endCharOffset > it.startCharOffset }
        }
        if (data != null && !data.isScanned && data.words.isNotEmpty() && (sel != null || savedHighlights.isNotEmpty())) {
          val highlightTint = surfaceColors.accent.copy(alpha = 0.30f)

          Canvas(modifier = Modifier.fillMaxSize()) {
            for (hl in savedHighlights) {
              val hlTint = if (surfaceColors.isDark) hl.color.washDark else hl.color.washLight
              val hs = hl.startCharOffset.coerceIn(0, data.words.lastIndex)
              val he = (hl.endCharOffset - 1).coerceIn(hs, data.words.lastIndex)
              for (idx in hs..he) {
                val r = data.words[idx].rect
                drawRoundRect(
                  color = hlTint,
                  topLeft = Offset(r.left * size.width, r.top * size.height),
                  size = Size(
                    width = (r.width * size.width).coerceAtLeast(2f),
                    height = (r.height * size.height).coerceAtLeast(2f)
                  ),
                  cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                )
              }
            }
            if (sel != null) {
              val s = sel.startWord.coerceIn(0, data.words.lastIndex)
              val e = sel.endWord.coerceIn(s, data.words.lastIndex)
              for (idx in s..e) {
                val r = data.words[idx].rect
                drawRoundRect(
                  color = highlightTint,
                  topLeft = Offset(r.left * size.width, r.top * size.height),
                  size = Size(
                    width = (r.width * size.width).coerceAtLeast(2f),
                    height = (r.height * size.height).coerceAtLeast(2f)
                  ),
                  cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                )
              }
            }
          }
        }
      }

      if (pageNotes.isNotEmpty()) {
        Box(
          modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(10.dp)
            .size(12.dp)
            .clip(CircleShape)
            .background(pageNotes.first().color.dotColor)
        )
      }
    }
  }
}
