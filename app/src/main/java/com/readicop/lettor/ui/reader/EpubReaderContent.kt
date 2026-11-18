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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    val fullBlockText: String
  ) : PageSliceItem

  data class QuoteSlice(
    override val blockIndex: Int,
    val text: AnnotatedString,
    val fullBlockText: String
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
  onRequestImage: (String) -> Unit,
  onToggleControls: () -> Unit,
  onLongPressBlock: (blockIndex: Int, text: String) -> Unit,
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
      onRequestImage = onRequestImage,
      onToggleControls = onToggleControls,
      onLongPressBlock = onLongPressBlock,
      onSelectChapter = { nextIdx ->
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
      onRequestImage = onRequestImage,
      onToggleControls = onToggleControls,
      onLongPressBlock = onLongPressBlock,
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
  onRequestImage: (String) -> Unit,
  onToggleControls: () -> Unit,
  onLongPressBlock: (blockIndex: Int, text: String) -> Unit,
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

  BoxWithConstraints(
    modifier = modifier
      .fillMaxSize()
      .background(surfaceColors.background)
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
      preferences.readerFont
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

    LaunchedEffect(pagerState.currentPage, chapter.index, safePageCount) {
      val chapterBase = chapter.index.toFloat() / totalChapters.toFloat()
      val inChapterFraction = (pagerState.currentPage + 1).toFloat() / safePageCount.toFloat()
      val overall = (chapterBase + inChapterFraction / totalChapters.toFloat()).coerceIn(0f, 1f)
      onPositionChanged(chapter.index, pagerState.currentPage, overall)
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
          modifier = Modifier.fillMaxSize()
        ) { pageIdx ->
          val page = pages[pageIdx]
          var pageWidthPx by remember { mutableFloatStateOf(availableWidthPx.toFloat()) }

          val handlePageTap: (Float) -> Unit = { xInPage ->
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

          Box(
            modifier = Modifier
              .fillMaxSize()
              .onGloballyPositioned { coords ->
                pageWidthPx = coords.size.width.toFloat()
              }
              .pointerInput(pageIdx, chapter.index, safePageCount) {
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
                    BlockInteractiveWrapper(
                      backgroundColor = blockWash,
                      isTtsActive = isTtsActive,
                      accentColor = surfaceColors.accent,
                      highlight = highlight,
                      onTapXInPage = handlePageTap,
                      onLongPress = { onLongPressBlock(item.blockIndex, item.text) }
                    ) {
                      Text(
                        text = item.text,
                        fontFamily = PlayfairDisplayFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = headingSize,
                        lineHeight = (headingSize.value * 1.25f).sp,
                        color = surfaceColors.text
                      )
                    }
                  }

                  is PageSliceItem.TextSlice -> {
                    BlockInteractiveWrapper(
                      backgroundColor = blockWash,
                      isTtsActive = isTtsActive,
                      accentColor = surfaceColors.accent,
                      highlight = highlight,
                      onTapXInPage = handlePageTap,
                      onLongPress = {
                        val excerpt = item.fullBlockText.ifBlank { item.text.text }
                        onLongPressBlock(item.blockIndex, excerpt)
                      }
                    ) {
                      Text(
                        text = item.text,
                        style = bodyStyle
                      )
                    }
                  }

                  is PageSliceItem.QuoteSlice -> {
                    BlockInteractiveWrapper(
                      backgroundColor = blockWash,
                      isTtsActive = isTtsActive,
                      accentColor = surfaceColors.accent,
                      highlight = highlight,
                      onTapXInPage = handlePageTap,
                      onLongPress = {
                        val excerpt = item.fullBlockText.ifBlank { item.text.text }
                        onLongPressBlock(item.blockIndex, excerpt)
                      }
                    ) {
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
                          modifier = Modifier.weight(1f)
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
  }
}

@Composable
private fun BlockInteractiveWrapper(
  backgroundColor: Color,
  isTtsActive: Boolean,
  accentColor: Color,
  highlight: HighlightEntity?,
  onTapXInPage: (Float) -> Unit,
  onLongPress: () -> Unit,
  content: @Composable () -> Unit
) {
  var blockOffsetX by remember { mutableFloatStateOf(0f) }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .then(
        if (backgroundColor != Color.Transparent) {
          Modifier.background(backgroundColor)
        } else Modifier
      )
      .then(
        if (isTtsActive) {
          Modifier.border(1.dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
        } else Modifier
      )
      .onGloballyPositioned { coords ->
        blockOffsetX = coords.positionInParent().x
      }
      .pointerInput(Unit) {
        detectTapGestures(
          onLongPress = { onLongPress() },
          onTap = { local -> onTapXInPage(blockOffsetX + local.x) }
        )
      }
      .padding(
        horizontal = if (backgroundColor != Color.Transparent) 6.dp else 0.dp,
        vertical = if (backgroundColor != Color.Transparent) 4.dp else 0.dp
      )
  ) {
    content()
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
  onRequestImage: (String) -> Unit,
  onToggleControls: () -> Unit,
  onLongPressBlock: (blockIndex: Int, text: String) -> Unit,
  onSelectChapter: (Int) -> Unit,
  modifier: Modifier = Modifier
) {
  val listState = rememberLazyListState()
  val totalChapters = chapterStubs.size.coerceAtLeast(1)

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
      .windowInsetsPadding(WindowInsets.statusBars)
      .windowInsetsPadding(WindowInsets.navigationBars)
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onToggleControls
      ),
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
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(blockWash)
                .pointerInput(blockIndex) {
                  detectTapGestures(
                    onTap = { onToggleControls() },
                    onLongPress = { onLongPressBlock(blockIndex, block.text) }
                  )
                }
                .padding(top = 8.dp)
            ) {
              Text(
                text = block.text,
                fontFamily = PlayfairDisplayFamily,
                fontWeight = FontWeight.Bold,
                fontSize = headingSize,
                lineHeight = (headingSize.value * 1.25f).sp,
                color = surfaceColors.text
              )
            }
          }

          is EpubBlock.Paragraph -> {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(blockWash)
                .then(
                  if (isTtsActive) Modifier.border(1.dp, surfaceColors.accent.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                  else Modifier
                )
                .pointerInput(blockIndex) {
                  detectTapGestures(
                    onTap = { onToggleControls() },
                    onLongPress = { onLongPressBlock(blockIndex, block.plainText) }
                  )
                }
                .padding(if (blockWash != Color.Transparent) 6.dp else 0.dp)
            ) {
              Text(text = block.content, style = bodyStyle)
              if (highlight != null && highlight.stickyNote.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                  text = "Nota: ${highlight.stickyNote}",
                  style = MaterialTheme.typography.labelSmall,
                  color = highlight.color.dotColor
                )
              }
            }
          }

          is EpubBlock.Quote -> {
            Surface(
              color = if (blockWash != Color.Transparent) blockWash else surfaceColors.surface,
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier
                .fillMaxWidth()
                .pointerInput(blockIndex) {
                  detectTapGestures(
                    onTap = { onToggleControls() },
                    onLongPress = { onLongPressBlock(blockIndex, block.plainText) }
                  )
                }
            ) {
              Row(modifier = Modifier.padding(14.dp)) {
                Box(
                  modifier = Modifier
                    .width(3.dp)
                    .height(40.dp)
                    .background(surfaceColors.accent, RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.width(12.dp))
                Text(
                  text = block.content,
                  style = bodyStyle.copy(
                    fontStyle = FontStyle.Italic,
                    color = surfaceColors.secondaryText
                  ),
                  modifier = Modifier.weight(1f)
                )
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
          if (isQuote) PageSliceItem.QuoteSlice(blockIndex, remaining, plainText)
          else PageSliceItem.TextSlice(blockIndex, remaining, plainText)
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
        if (isQuote) PageSliceItem.QuoteSlice(blockIndex, head, plainText)
        else PageSliceItem.TextSlice(blockIndex, head, plainText)
      )
      flushPage()

      remaining = if (trimmedTailStart >= 0) {
        tailText.subSequence(trimmedTailStart, tailText.length)
      } else {
        AnnotatedString("")
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
