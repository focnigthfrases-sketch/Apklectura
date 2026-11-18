package com.example.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.HighlightEntity
import com.example.data.preferences.ReaderPreferences
import com.example.data.preferences.ReaderTheme
import com.example.domain.engine.PdfBookSession
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.ReaderSurfaceColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive

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
  onToggleControls: () -> Unit,
  onLongPressPage: (pageIndex: Int) -> Unit = {},
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
      onToggleControls = onToggleControls,
      onLongPressPage = onLongPressPage,
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
      onToggleControls = onToggleControls,
      onLongPressPage = onLongPressPage,
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
  onToggleControls: () -> Unit,
  onLongPressPage: (Int) -> Unit,
  onPageChanged: (pageIndex: Int, overallProgress: Float) -> Unit,
  modifier: Modifier = Modifier
) {
  val safeCount = pageCount.coerceAtLeast(1)
  val pagerState = rememberPagerState(
    initialPage = currentPageIndex.coerceIn(0, safeCount - 1)
  ) { safeCount }

  LaunchedEffect(currentPageIndex) {
    val target = currentPageIndex.coerceIn(0, safeCount - 1)
    if (pagerState.currentPage != target) {
      pagerState.scrollToPage(target)
    }
  }

  LaunchedEffect(pagerState.currentPage) {
    val prog = (pagerState.currentPage + 1).toFloat() / safeCount.toFloat()
    onPageChanged(pagerState.currentPage, prog)
  }

  var isZoomed by remember { mutableStateOf(false) }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(surfaceColors.background)
      .windowInsetsPadding(WindowInsets.statusBars)
      .windowInsetsPadding(WindowInsets.navigationBars)
  ) {
    BoxWithConstraints(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
    ) {
      val targetWidthPx = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceAtLeast(720)

      HorizontalPager(
        state = pagerState,
        userScrollEnabled = !isZoomed,
        modifier = Modifier
          .fillMaxSize()
          .testTag("pdf_horizontal_pager")
      ) { pageIdx ->
        PdfSinglePageView(
          pageIndex = pageIdx,
          targetWidthPx = targetWidthPx,
          session = session,
          colorFilter = colorFilter,
          pageNotes = notesByPage[pageIdx].orEmpty(),
          onToggleControls = onToggleControls,
          onLongPressPage = { onLongPressPage(pageIdx) },
          onZoomStateChanged = { zoomed -> isZoomed = zoomed }
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
}

@Composable
private fun PdfSinglePageView(
  pageIndex: Int,
  targetWidthPx: Int,
  session: PdfBookSession,
  colorFilter: ColorFilter?,
  pageNotes: List<HighlightEntity>,
  onToggleControls: () -> Unit,
  onLongPressPage: () -> Unit,
  onZoomStateChanged: (Boolean) -> Unit
) {
  var bitmap by remember(pageIndex, targetWidthPx) { mutableStateOf<Bitmap?>(null) }
  var scale by remember(pageIndex) { mutableFloatStateOf(1f) }
  var offset by remember(pageIndex) { mutableStateOf(Offset.Zero) }

  LaunchedEffect(pageIndex, targetWidthPx) {
    bitmap = session.renderPage(pageIndex, targetWidthPx)
  }

  LaunchedEffect(scale) {
    onZoomStateChanged(scale > 1.05f)
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .pointerInput(pageIndex) {
        detectTapGestures(
          onTap = { onToggleControls() },
          onLongPress = { onLongPressPage() },
          onDoubleTap = {
            if (scale > 1.1f) {
              scale = 1f
              offset = Offset.Zero
            } else {
              scale = 2.2f
            }
          }
        )
      }
      .then(
        if (scale > 1.05f) {
          Modifier.pointerInput(pageIndex, scale) {
            detectTransformGestures { _, pan, zoom, _ ->
              val newScale = (scale * zoom).coerceIn(1f, 4f)
              scale = newScale
              if (newScale <= 1.02f) {
                scale = 1f
                offset = Offset.Zero
              } else {
                val maxPanX = (size.width * (newScale - 1f)) / 2f
                val maxPanY = (size.height * (newScale - 1f)) / 2f
                offset = Offset(
                  x = (offset.x + pan.x).coerceIn(-maxPanX, maxPanX),
                  y = (offset.y + pan.y).coerceIn(-maxPanY, maxPanY)
                )
              }
            }
          }
        } else {
          Modifier
        }
      ),
    contentAlignment = Alignment.Center
  ) {
    val bmp = bitmap
    if (bmp == null) {
      CircularProgressIndicator()
    } else {
      Surface(
        shadowElevation = 4.dp,
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier
          .padding(horizontal = 10.dp, vertical = 6.dp)
          .graphicsLayer(
            scaleX = scale,
            scaleY = scale,
            translationX = offset.x,
            translationY = offset.y
          )
      ) {
        Image(
          bitmap = bmp.asImageBitmap(),
          contentDescription = "Página ${pageIndex + 1} del PDF",
          contentScale = ContentScale.Fit,
          colorFilter = colorFilter,
          modifier = Modifier.fillMaxSize()
        )
      }
    }

    if (pageNotes.isNotEmpty()) {
      Surface(
        onClick = onLongPressPage,
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
  onToggleControls: () -> Unit,
  onLongPressPage: (Int) -> Unit,
  onPageChanged: (pageIndex: Int, overallProgress: Float) -> Unit,
  modifier: Modifier = Modifier
) {
  val safeCount = pageCount.coerceAtLeast(1)
  val listState = rememberLazyListState(
    initialFirstVisibleItemIndex = currentPageIndex.coerceIn(0, safeCount - 1)
  )

  LaunchedEffect(currentPageIndex) {
    val target = currentPageIndex.coerceIn(0, safeCount - 1)
    if (kotlin.math.abs(listState.firstVisibleItemIndex - target) > 1) {
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

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(surfaceColors.background)
      .windowInsetsPadding(WindowInsets.statusBars)
      .windowInsetsPadding(WindowInsets.navigationBars)
  ) {
    BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
      val targetWidthPx = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceAtLeast(720)

      LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
          .fillMaxSize()
          .pointerInput(Unit) {
            detectTapGestures(onTap = { onToggleControls() })
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
            onToggleControls = onToggleControls,
            onLongPressPage = { onLongPressPage(pageIdx) }
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
}

@Composable
private fun PdfScrollPageCard(
  pageIndex: Int,
  targetWidthPx: Int,
  session: PdfBookSession,
  colorFilter: ColorFilter?,
  surfaceColors: ReaderSurfaceColors,
  pageNotes: List<HighlightEntity>,
  onToggleControls: () -> Unit,
  onLongPressPage: () -> Unit
) {
  var bitmap by remember(pageIndex, targetWidthPx) { mutableStateOf<Bitmap?>(null) }

  LaunchedEffect(pageIndex, targetWidthPx) {
    bitmap = session.renderPage(pageIndex, targetWidthPx)
  }

  val bmp = bitmap
  Surface(
    shadowElevation = 2.dp,
    shape = RoundedCornerShape(6.dp),
    color = surfaceColors.surface,
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(6.dp))
      .pointerInput(pageIndex) {
        detectTapGestures(
          onTap = { onToggleControls() },
          onLongPress = { onLongPressPage() }
        )
      }
  ) {
    Box(modifier = Modifier.fillMaxWidth()) {
      if (bmp == null) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.707f),
          contentAlignment = Alignment.Center
        ) {
          CircularProgressIndicator()
        }
      } else {
        Image(
          bitmap = bmp.asImageBitmap(),
          contentDescription = "Página ${pageIndex + 1}",
          contentScale = ContentScale.FillWidth,
          colorFilter = colorFilter,
          modifier = Modifier.fillMaxWidth()
        )
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
