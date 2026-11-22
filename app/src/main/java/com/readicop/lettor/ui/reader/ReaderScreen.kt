package com.readicop.lettor.ui.reader

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TextFormat
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readicop.lettor.data.local.BookFormat
import com.readicop.lettor.data.preferences.MAX_READER_BRIGHTNESS
import com.readicop.lettor.data.preferences.MIN_READER_BRIGHTNESS
import com.readicop.lettor.data.preferences.OrientationLockMode
import com.readicop.lettor.domain.engine.EpubBlock
import com.readicop.lettor.ui.components.LettorThinSlider
import com.readicop.lettor.ui.theme.JetBrainsMonoFamily
import com.readicop.lettor.ui.theme.colors
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private data class ActiveFragmentSelection(
  val blockIndex: Int,
  val endBlockIndex: Int = blockIndex,
  val startCharOffset: Int = -1,
  val endCharOffset: Int = -1,
  val text: String,
  val spokenIndex: Int?,
  val hasSelection: Boolean
)

internal fun resolveWindowBrightness(readerBrightness: Float): Float =
  if (readerBrightness < 0f) {
    WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
  } else {
    readerBrightness.coerceIn(MIN_READER_BRIGHTNESS, MAX_READER_BRIGHTNESS)
  }

@Composable
fun ReaderScreen(
  viewModel: ReaderViewModel,
  onNavigateBack: () -> Unit,
  modifier: Modifier = Modifier
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val imageCache by viewModel.epubImageCache.collectAsStateWithLifecycle()

  var controlsVisible by remember { mutableStateOf(false) }
  var showAppearanceSheet by remember { mutableStateOf(false) }
  var showTocSheet by remember { mutableStateOf(false) }
  var tocInitialTab by remember { mutableIntStateOf(0) }
  var showSearchSheet by remember { mutableStateOf(false) }
  var searchInitialTab by remember { mutableIntStateOf(0) }
  var showAddNoteDialog by remember { mutableStateOf(false) }
  var activeFragment by remember { mutableStateOf<ActiveFragmentSelection?>(null) }
  var currentVisibleBlockIndex by remember(uiState.book?.id, uiState.currentUnit) { mutableIntStateOf(0) }
  var liveSelectionFragment by remember(uiState.book?.id, uiState.currentUnit) {
    mutableStateOf<ActiveFragmentSelection?>(null)
  }
  var epubCurrentGlobalPage by remember(uiState.book?.id) { mutableIntStateOf(0) }
  var epubTotalBookPages by remember(uiState.book?.id) { mutableIntStateOf(1) }
  var epubGlobalPageJumpCounter by remember(uiState.book?.id) { mutableLongStateOf(0L) }
  var epubGlobalPageJumpRequest by remember(uiState.book?.id) { mutableStateOf<Pair<Long, Int>?>(null) }

  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()

  val context = LocalContext.current
  val activity = context as? Activity
  val view = LocalView.current

  val initialCutoutMode = remember(activity) {
    val window = activity?.window
    if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      window.attributes.layoutInDisplayCutoutMode
    } else 0
  }
  val initialLightStatusBars = remember(activity, view) {
    activity?.window?.let { w -> WindowCompat.getInsetsController(w, view).isAppearanceLightStatusBars } ?: true
  }
  val initialLightNavBars = remember(activity, view) {
    activity?.window?.let { w -> WindowCompat.getInsetsController(w, view).isAppearanceLightNavigationBars } ?: true
  }

  val anyModalOpen = showAppearanceSheet ||
    showTocSheet ||
    showSearchSheet ||
    activeFragment != null ||
    showAddNoteDialog ||
    uiState.sleepTimerExpiredDialog

  val surfaceColors = uiState.preferences.readerTheme.colors()

  val latestControlsVisible by rememberUpdatedState(controlsVisible)
  val latestAnyModalOpen by rememberUpdatedState(anyModalOpen)
  val latestIsDarkSurface by rememberUpdatedState(surfaceColors.isDark)

  val syncSystemBars = remember(activity, view) {
    { showBars: Boolean, isDarkSurface: Boolean ->
      val window = activity?.window
      if (window != null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
          val lp = window.attributes
          lp.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
          } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
          }
          window.attributes = lp
        }
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior =
          WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.isAppearanceLightStatusBars = !isDarkSurface
        controller.isAppearanceLightNavigationBars = !isDarkSurface
        if (showBars) {
          controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
          controller.hide(WindowInsetsCompat.Type.systemBars())
        }
      }
    }
  }

  val restoreNormalSystemBars = remember(activity, view, initialCutoutMode, initialLightStatusBars, initialLightNavBars) {
    {
      val window = activity?.window
      if (window != null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
          val lp = window.attributes
          lp.layoutInDisplayCutoutMode = initialCutoutMode
          window.attributes = lp
        }
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        controller.isAppearanceLightStatusBars = initialLightStatusBars
        controller.isAppearanceLightNavigationBars = initialLightNavBars
        controller.show(WindowInsetsCompat.Type.systemBars())
      }
    }
  }

  val handleExit: () -> Unit = {
    restoreNormalSystemBars()
    viewModel.onExitReaderNormally()
    onNavigateBack()
  }

  BackHandler(onBack = handleExit)

  // Enter immersive reading mode on launch and restore normal system bars upon exiting the reader.
  DisposableEffect(activity, view, syncSystemBars, restoreNormalSystemBars) {
    syncSystemBars(false, latestIsDarkSurface)
    onDispose {
      restoreNormalSystemBars()
    }
  }

  // Synchronize Android status & navigation bars with the reader's top and bottom controls.
  LaunchedEffect(controlsVisible, anyModalOpen, surfaceColors.isDark) {
    if (controlsVisible) {
      syncSystemBars(true, surfaceColors.isDark)
    } else if (!anyModalOpen) {
      syncSystemBars(false, surfaceColors.isDark)
    }
  }

  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner, viewModel, syncSystemBars) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_RESUME -> {
          syncSystemBars(latestControlsVisible || latestAnyModalOpen, latestIsDarkSurface)
          viewModel.onReaderResumed()
        }
        Lifecycle.Event.ON_STOP -> viewModel.onReaderStopped()
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
    }
  }

  // Keep screen awake while reading
  val keepScreenOn = uiState.preferences.keepScreenOn
  DisposableEffect(view, keepScreenOn) {
    view.keepScreenOn = keepScreenOn
    onDispose { view.keepScreenOn = false }
  }

  // Per-reader brightness override (update without resetting to system brightness between ticks)
  val readerBrightness = uiState.preferences.readerBrightness
  LaunchedEffect(activity, readerBrightness) {
    val window = activity?.window ?: return@LaunchedEffect
    val lp = window.attributes
    val targetBrightness = resolveWindowBrightness(readerBrightness)
    if (lp.screenBrightness != targetBrightness) {
      lp.screenBrightness = targetBrightness
      window.attributes = lp
    }
  }
  DisposableEffect(activity) {
    onDispose {
      val window = activity?.window
      if (window != null) {
        val lp = window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
      }
    }
  }

  // Orientation lock control
  val orientationLock = uiState.preferences.orientationLock
  DisposableEffect(activity, orientationLock) {
    activity?.requestedOrientation = when (orientationLock) {
      OrientationLockMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
      OrientationLockMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
      OrientationLockMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
    onDispose {
      activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
  }

  val controlsTopInsets = rememberReaderControlsTopInsets()
  val controlsBottomInsets = rememberReaderControlsBottomInsets()
  val isBookmarked = uiState.bookmarks.any { it.unitIndex == uiState.currentUnit }

  val copyPlainSelection: (String) -> Unit = { selectedText ->
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Texto de ${uiState.book?.title.orEmpty()}", selectedText))
    scope.launch { snackbarHostState.showSnackbar("Texto copiado") }
  }
  val shareBookSelection: (String, String) -> Unit = { selectedText, locationLabel ->
    val citation = formatBookCitation(
      selectedText = selectedText,
      bookTitle = uiState.book?.title.orEmpty(),
      bookAuthor = uiState.book?.author.orEmpty(),
      locationLabel = locationLabel
    )
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_TEXT, citation)
    }
    context.startActivity(Intent.createChooser(shareIntent, "Compartir cita"))
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(surfaceColors.background)
  ) {
    when (val content = uiState.contentState) {
      ReaderContentState.Loading -> {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = surfaceColors.accent)
            Spacer(Modifier.height(12.dp))
            Text(
              text = "Abriendo libro…",
              style = MaterialTheme.typography.bodyMedium,
              color = surfaceColors.secondaryText
            )
          }
        }
      }

      is ReaderContentState.Error -> {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
          contentAlignment = Alignment.Center
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
              text = "No se pudo abrir el libro",
              style = MaterialTheme.typography.titleLarge,
              color = surfaceColors.text
            )
            Spacer(Modifier.height(8.dp))
            Text(
              text = content.message,
              style = MaterialTheme.typography.bodyMedium,
              color = surfaceColors.secondaryText
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = handleExit) {
              Text("Volver a la biblioteca")
            }
          }
        }
      }

      is ReaderContentState.EpubReady -> {
        val spokenMap = remember(content.activeChapter.index, content.activeChapter.blocks) {
          buildMap<Int, Int> {
            var idx = 0
            content.activeChapter.blocks.forEachIndexed { blockIdx, block ->
              val t = when (block) {
                is EpubBlock.Heading -> block.text
                is EpubBlock.Paragraph -> block.plainText
                is EpubBlock.Quote -> block.plainText
                is EpubBlock.Image -> ""
              }
              if (t.isNotBlank()) put(blockIdx, idx++)
            }
          }
        }

        val activeChapterId = epubChapterUniqueId(content.activeChapter, content.chapterStubs)
        EpubReaderContent(
          metadata = content.metadata,
          chapterStubs = content.chapterStubs,
          activeChapter = content.activeChapter,
          loadedChapters = content.loadedChapters,
          scrollJumpRequestVersion = content.scrollJumpRequestVersion,
          openAtEnd = content.openAtEnd,
          initialSubPage = uiState.currentSubOffset,
          initialHorizontalPosition = uiState.horizontalChapterPositions[activeChapterId],
          horizontalChapterPositions = uiState.horizontalChapterPositions,
          initialVerticalPosition = uiState.verticalChapterPositions[activeChapterId],
          verticalChapterPositions = uiState.verticalChapterPositions,
          preferences = uiState.preferences,
          surfaceColors = surfaceColors,
          imageCache = imageCache,
          highlights = uiState.highlights,
          ttsActiveBlockIndex = if (uiState.ttsState.isPlaying) uiState.ttsState.currentBlockIndex else null,
          isAutoScrolling = uiState.isAutoScrolling,
          autoScrollSpeed = uiState.preferences.autoScrollSpeed,
          topOverlayClearanceDp = if (controlsVisible) 76.dp else 4.dp,
          controlsVisible = controlsVisible,
          onDismissControls = { controlsVisible = false },
          onRequestImage = viewModel::requestEpubImage,
          onRequestChapter = viewModel::ensureScrollChapterLoaded,
          onLoadChapterForPagination = viewModel::loadChapterForPagination,
          globalPageJumpRequest = epubGlobalPageJumpRequest,
          onGlobalPageStateChanged = { cur, total ->
            epubCurrentGlobalPage = cur
            epubTotalBookPages = total
          },
          onJumpToChapterPage = viewModel::jumpToChapterPage,
          onToggleControls = { controlsVisible = !controlsVisible },
          onOpenFragmentTools = { startBlk, endBlk, startChar, endChar, excerpt ->
            activeFragment = ActiveFragmentSelection(
              blockIndex = startBlk,
              endBlockIndex = endBlk,
              startCharOffset = startChar,
              endCharOffset = endChar,
              text = excerpt,
              spokenIndex = spokenMap[startBlk],
              hasSelection = true
            )
          },
          onCopyPlainText = copyPlainSelection,
          onShareSelection = shareBookSelection,
          onPositionChanged = { chIdx, subPage, overall ->
            viewModel.onPositionChanged(chIdx, subPage, overall)
          },
          onHorizontalPositionChanged = viewModel::onHorizontalPositionChanged,
          onVerticalPositionChanged = viewModel::onVerticalPositionChanged,
          onSelectChapter = viewModel::jumpToUnit,
          onSelectChapterBoundary = viewModel::jumpToChapterAtBoundary,
          onVisibleBlockChanged = { blockIdx ->
            currentVisibleBlockIndex = blockIdx
          },
          onActiveSelectionInfoChanged = { startBlk, endBlk, startChar, endChar, excerpt ->
            liveSelectionFragment = if (!excerpt.isNullOrBlank()) {
              ActiveFragmentSelection(
                blockIndex = startBlk,
                endBlockIndex = endBlk,
                startCharOffset = startChar,
                endCharOffset = endChar,
                text = excerpt,
                spokenIndex = spokenMap[startBlk],
                hasSelection = true
              )
            } else null
          }
        )
      }

      is ReaderContentState.PdfReady -> {
        PdfReaderContent(
          bookTitle = uiState.book?.title.orEmpty(),
          pageCount = content.pageCount,
          session = content.session,
          currentPageIndex = uiState.currentUnit,
          preferences = uiState.preferences,
          surfaceColors = surfaceColors,
          highlights = uiState.highlights,
          isAutoScrolling = uiState.isAutoScrolling,
          autoScrollSpeed = uiState.preferences.autoScrollSpeed,
          topOverlayClearanceDp = if (controlsVisible) 76.dp else 4.dp,
          onToggleControls = { controlsVisible = !controlsVisible },
          onOpenFragmentTools = { _, startWord, endWordExcl, excerpt ->
            activeFragment = ActiveFragmentSelection(
              blockIndex = -1,
              endBlockIndex = -1,
              startCharOffset = startWord,
              endCharOffset = endWordExcl,
              text = excerpt,
              spokenIndex = null,
              hasSelection = startWord >= 0 && endWordExcl > startWord && excerpt.isNotBlank()
            )
          },
          onOpenPageNotesList = {
            tocInitialTab = 2
            showTocSheet = true
          },
          onCopyPlainText = copyPlainSelection,
          onShareSelection = shareBookSelection,
          onPageChanged = { pageIdx, overall ->
            viewModel.onPositionChanged(pageIdx, 0, overall)
          },
          onActiveSelectionInfoChanged = { _, startWord, endWordExcl, excerpt ->
            liveSelectionFragment = if (!excerpt.isNullOrBlank() && startWord >= 0 && endWordExcl > startWord) {
              ActiveFragmentSelection(
                blockIndex = -1,
                endBlockIndex = -1,
                startCharOffset = startWord,
                endCharOffset = endWordExcl,
                text = excerpt,
                spokenIndex = null,
                hasSelection = true
              )
            } else null
          }
        )
      }
    }

    // Active Sleep Timer Badge (when controls are hidden)
    if (!controlsVisible && uiState.sleepTimerRemainingSeconds > 0) {
      val m = uiState.sleepTimerRemainingSeconds / 60
      val s = uiState.sleepTimerRemainingSeconds % 60
      Surface(
        onClick = { showAppearanceSheet = true },
        color = surfaceColors.surface.copy(alpha = 0.9f),
        shape = RoundedCornerShape(999.dp),
        shadowElevation = 2.dp,
        modifier = Modifier
          .align(Alignment.TopEnd)
          .windowInsetsPadding(controlsTopInsets)
          .padding(top = 8.dp, end = 12.dp)
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(
            Icons.Default.Timer,
            contentDescription = "Temporizador activo",
            tint = surfaceColors.accent,
            modifier = Modifier.size(14.dp)
          )
          Spacer(Modifier.width(4.dp))
          Text(
            text = String.format(Locale.US, "%02d:%02d", m, s),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
            color = surfaceColors.text
          )
        }
      }
    }

    // Floating TTS Mini-Player (even when top/bottom controls are hidden)
    AnimatedVisibility(
      visible = uiState.ttsState.isPlaying && !controlsVisible,
      enter = slideInVertically { it } + fadeIn(),
      exit = slideOutVertically { it } + fadeOut(),
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .windowInsetsPadding(controlsBottomInsets)
        .padding(bottom = 42.dp, start = 16.dp, end = 16.dp)
    ) {
      TtsFloatingMiniBar(
        speechRate = uiState.ttsState.speechRate,
        surfaceColors = surfaceColors,
        onPrev = viewModel::prevTtsParagraph,
        onPause = viewModel::pauseTts,
        onNext = viewModel::nextTtsParagraph,
        onCycleRate = {
          val rates = listOf(0.8f, 1.0f, 1.25f, 1.5f)
          val currentIdx = rates.indexOfFirst { kotlin.math.abs(it - uiState.ttsState.speechRate) < 0.1f }
          val nextRate = rates[(currentIdx + 1) % rates.size]
          viewModel.setTtsRate(nextRate)
        }
      )
    }

    // Floating Auto-Scroll Pill (when active and controls hidden)
    AnimatedVisibility(
      visible = uiState.isAutoScrolling && !controlsVisible && !uiState.ttsState.isPlaying,
      enter = fadeIn(),
      exit = fadeOut(),
      modifier = Modifier
        .align(Alignment.BottomEnd)
        .windowInsetsPadding(controlsBottomInsets)
        .padding(end = 16.dp, bottom = 44.dp)
    ) {
      Surface(
        color = surfaceColors.surface.copy(alpha = 0.94f),
        shape = RoundedCornerShape(999.dp),
        shadowElevation = 4.dp
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          TextButton(
            onClick = {
              val speeds = listOf(0.8f, 1.2f, 1.8f, 2.5f)
              val idx = speeds.indexOfFirst { kotlin.math.abs(it - uiState.preferences.autoScrollSpeed) < 0.2f }
              viewModel.setAutoScrollSpeed(speeds[(idx + 1) % speeds.size])
            }
          ) {
            Text(
              text = "${uiState.preferences.autoScrollSpeed}x",
              style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
              color = surfaceColors.accent
            )
          }
          IconButton(
            onClick = viewModel::toggleAutoScroll,
            modifier = Modifier.size(36.dp)
          ) {
            Icon(
              imageVector = Icons.Default.Pause,
              contentDescription = "Pausar autodesplazamiento",
              tint = surfaceColors.text
            )
          }
        }
      }
    }

    // Top Editorial Control Bar
    AnimatedVisibility(
      visible = controlsVisible,
      enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
      exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
      modifier = Modifier.align(Alignment.TopCenter)
    ) {
      Surface(
        color = surfaceColors.surface.copy(alpha = 0.96f),
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(modifier = Modifier.windowInsetsPadding(controlsTopInsets)) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            IconButton(
              onClick = handleExit,
              modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("reader_back_button")
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Volver a la biblioteca",
                tint = surfaceColors.text
              )
            }

            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = uiState.book?.title.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                color = surfaceColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
              val subTitle = when (val c = uiState.contentState) {
                is ReaderContentState.EpubReady ->
                  c.chapterStubs.getOrNull(uiState.currentUnit)?.title ?: ""
                is ReaderContentState.PdfReady ->
                  "Página ${uiState.currentUnit + 1} de ${c.pageCount}"
                else -> ""
              }
              if (subTitle.isNotBlank()) {
                Text(
                  text = subTitle,
                  style = MaterialTheme.typography.bodySmall,
                  color = surfaceColors.secondaryText,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              }
            }

            if (uiState.book?.format == BookFormat.EPUB) {
              IconButton(
                onClick = {
                  searchInitialTab = 0
                  showSearchSheet = true
                },
                modifier = Modifier
                  .minimumInteractiveComponentSize()
                  .testTag("reader_search_button")
              ) {
                Icon(
                  imageVector = Icons.Default.Search,
                  contentDescription = "Buscar o preguntar",
                  tint = surfaceColors.text
                )
              }
            }

            IconButton(
              onClick = viewModel::toggleCurrentBookmark,
              modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("reader_bookmark_toggle")
            ) {
              Icon(
                imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                contentDescription = if (isBookmarked) "Quitar marcador" else "Guardar marcador",
                tint = if (isBookmarked) surfaceColors.accent else surfaceColors.text
              )
            }

            IconButton(
              onClick = {
                tocInitialTab = 0
                showTocSheet = true
              },
              modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("reader_toc_button")
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.FormatListBulleted,
                contentDescription = "Índice, marcadores y notas",
                tint = surfaceColors.text
              )
            }

            IconButton(
              onClick = { showAppearanceSheet = true },
              modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("reader_appearance_button")
            ) {
              Icon(
                imageVector = Icons.Default.TextFormat,
                contentDescription = "Apariencia, brillo y temporizador",
                tint = surfaceColors.text
              )
            }
          }
          HorizontalDivider(color = surfaceColors.divider)
        }
      }
    }

    // Bottom Navigation, Quick Tools & Scrubber Deck
    AnimatedVisibility(
      visible = controlsVisible && (uiState.contentState is ReaderContentState.EpubReady || uiState.contentState is ReaderContentState.PdfReady),
      enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
      exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
      modifier = Modifier.align(Alignment.BottomCenter)
    ) {
      val isEpub = uiState.contentState is ReaderContentState.EpubReady
      val arrowTotalUnits = when (val c = uiState.contentState) {
        is ReaderContentState.EpubReady -> c.chapterStubs.size
        is ReaderContentState.PdfReady -> c.pageCount
        else -> 1
      }.coerceAtLeast(1)

      val totalUnits = when (val c = uiState.contentState) {
        is ReaderContentState.EpubReady -> epubTotalBookPages
        is ReaderContentState.PdfReady -> c.pageCount
        else -> 1
      }.coerceAtLeast(1)

      val activeSliderUnit = if (isEpub) {
        epubCurrentGlobalPage.coerceIn(0, totalUnits - 1)
      } else {
        uiState.currentUnit.coerceIn(0, totalUnits - 1)
      }

      val unitNoun = "Página"
      var isDraggingSlider by remember { mutableStateOf(false) }
      var pendingSliderJumpTarget by remember(uiState.book?.id) { mutableStateOf<Int?>(null) }
      var sliderValue by remember { mutableFloatStateOf(activeSliderUnit.toFloat()) }
      LaunchedEffect(pendingSliderJumpTarget) {
        if (pendingSliderJumpTarget != null) {
          kotlinx.coroutines.delay(450L)
          pendingSliderJumpTarget = null
        }
      }
      LaunchedEffect(activeSliderUnit, totalUnits, isDraggingSlider, pendingSliderJumpTarget) {
        if (isDraggingSlider) return@LaunchedEffect
        val pending = pendingSliderJumpTarget
        if (pending != null) {
          if (activeSliderUnit == pending) {
            pendingSliderJumpTarget = null
          } else {
            return@LaunchedEffect
          }
        }
        sliderValue = activeSliderUnit.toFloat().coerceIn(0f, (totalUnits - 1).coerceAtLeast(0).toFloat())
      }

      Surface(
        color = surfaceColors.surface.copy(alpha = 0.96f),
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(
          modifier = Modifier
            .windowInsetsPadding(controlsBottomInsets)
            .padding(top = 8.dp, bottom = 10.dp)
        ) {
          // Quick Advanced Reader Tools Strip
          LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            if (uiState.book?.format == BookFormat.EPUB) {
              item {
                FilterChip(
                  selected = uiState.ttsState.isPlaying,
                  onClick = { viewModel.toggleTtsPlayPause() },
                  leadingIcon = {
                    Icon(
                      imageVector = if (uiState.ttsState.isPlaying) Icons.Default.Pause else Icons.Default.RecordVoiceOver,
                      contentDescription = null,
                      modifier = Modifier.size(16.dp)
                    )
                  },
                  label = { Text(if (uiState.ttsState.isPlaying) "Pausar voz" else "Escuchar") },
                  modifier = Modifier.testTag("quick_tool_tts")
                )
              }
              item {
                FilterChip(
                  selected = false,
                  onClick = viewModel::generateCurrentChapterSummary,
                  leadingIcon = {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                  },
                  label = { Text("Resumen") },
                  modifier = Modifier.testTag("quick_tool_summary")
                )
              }
              item {
                FilterChip(
                  selected = false,
                  onClick = {
                    searchInitialTab = 1
                    showSearchSheet = true
                  },
                  leadingIcon = {
                    Icon(Icons.Default.QuestionAnswer, contentDescription = null, modifier = Modifier.size(16.dp))
                  },
                  label = { Text("Preguntar") },
                  modifier = Modifier.testTag("quick_tool_ask")
                )
              }
            }

            item {
              FilterChip(
                selected = uiState.isAutoScrolling,
                onClick = viewModel::toggleAutoScroll,
                leadingIcon = {
                  Icon(Icons.Default.SwapVert, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                label = { Text("Auto-scroll") },
                modifier = Modifier.testTag("quick_tool_autoscroll")
              )
            }

            item {
              val spokenMapForPosition = remember(uiState.contentState) {
                val epub = uiState.contentState as? ReaderContentState.EpubReady
                if (epub == null) emptyMap()
                else buildMap<Int, Int> {
                  var idx = 0
                  epub.activeChapter.blocks.forEachIndexed { blockIdx, block ->
                    val t = when (block) {
                      is EpubBlock.Heading -> block.text
                      is EpubBlock.Paragraph -> block.plainText
                      is EpubBlock.Quote -> block.plainText
                      is EpubBlock.Image -> ""
                    }
                    if (t.isNotBlank()) put(blockIdx, idx++)
                  }
                }
              }
              FilterChip(
                selected = false,
                onClick = {
                  val selected = liveSelectionFragment
                  activeFragment = selected ?: ActiveFragmentSelection(
                    blockIndex = -1,
                    text = "",
                    spokenIndex = spokenMapForPosition[currentVisibleBlockIndex]
                      ?: if (uiState.ttsState.isPlaying) uiState.ttsState.currentBlockIndex else 0,
                    hasSelection = false
                  )
                },
                leadingIcon = {
                  Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                label = { Text("Herramientas") },
                modifier = Modifier.testTag("quick_tool_tools")
              )
            }

            item {
              FilterChip(
                selected = uiState.sleepTimerRemainingSeconds > 0,
                onClick = { showAppearanceSheet = true },
                leadingIcon = {
                  Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                label = {
                  if (uiState.sleepTimerRemainingSeconds > 0) {
                    val m = uiState.sleepTimerRemainingSeconds / 60
                    val s = uiState.sleepTimerRemainingSeconds % 60
                    Text(String.format(Locale.US, "%02d:%02d", m, s))
                  } else {
                    Text("Temporizador")
                  }
                },
                modifier = Modifier.testTag("quick_tool_timer")
              )
            }
          }

          Spacer(Modifier.height(6.dp))

          Column(modifier = Modifier.padding(horizontal = 18.dp)) {
            val displayIndex = sliderValue.roundToInt().coerceIn(0, totalUnits - 1)
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = if (isEpub) {
                  "${displayIndex + 1} > $totalUnits"
                } else {
                  "$unitNoun ${displayIndex + 1} de $totalUnits"
                },
                style = MaterialTheme.typography.labelMedium.copy(
                  fontFamily = JetBrainsMonoFamily,
                  fontWeight = FontWeight.Medium
                ),
                color = surfaceColors.text
              )
              val pct = (((displayIndex + 1).toFloat() / totalUnits.toFloat()) * 100f)
                .roundToInt().coerceIn(0, 100)
              Text(
                text = "$pct%",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                color = surfaceColors.accent
              )
            }

            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.fillMaxWidth()
            ) {
              IconButton(
                onClick = {
                  if (uiState.currentUnit > 0) {
                    pendingSliderJumpTarget = null
                    viewModel.jumpToUnit(uiState.currentUnit - 1)
                  }
                },
                enabled = uiState.currentUnit > 0,
                modifier = Modifier.minimumInteractiveComponentSize()
              ) {
                Icon(
                  imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                  contentDescription = if (isEpub) "Capítulo anterior" else "Anterior",
                  tint = surfaceColors.text
                )
              }

              if (totalUnits > 1) {
                LettorThinSlider(
                  value = sliderValue.coerceIn(0f, (totalUnits - 1).toFloat()),
                  onValueChange = {
                    isDraggingSlider = true
                    pendingSliderJumpTarget = null
                    sliderValue = it
                  },
                  onValueChangeFinished = {
                    val target = sliderValue.roundToInt().coerceIn(0, totalUnits - 1)
                    sliderValue = target.toFloat()
                    pendingSliderJumpTarget = target
                    isDraggingSlider = false
                    if (isEpub) {
                      epubGlobalPageJumpCounter += 1L
                      epubGlobalPageJumpRequest = epubGlobalPageJumpCounter to target
                    } else {
                      viewModel.jumpToUnit(target)
                    }
                  },
                  valueRange = 0f..(totalUnits - 1).toFloat(),
                  accentColor = surfaceColors.accent,
                  trackColor = surfaceColors.divider,
                  modifier = Modifier
                    .weight(1f)
                    .testTag("reader_progress_slider")
                )
              } else {
                Spacer(Modifier.weight(1f))
              }

              IconButton(
                onClick = {
                  if (uiState.currentUnit < arrowTotalUnits - 1) {
                    pendingSliderJumpTarget = null
                    viewModel.jumpToUnit(uiState.currentUnit + 1)
                  }
                },
                enabled = uiState.currentUnit < arrowTotalUnits - 1,
                modifier = Modifier.minimumInteractiveComponentSize()
              ) {
                Icon(
                  imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                  contentDescription = if (isEpub) "Capítulo siguiente" else "Siguiente",
                  tint = surfaceColors.text
                )
              }
            }
          }
        }
      }
    }

    SnackbarHost(
      hostState = snackbarHostState,
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .windowInsetsPadding(controlsBottomInsets)
        .padding(bottom = 16.dp)
    )
  }

  // Appearance + Brightness + Orientation + Timer Sheet
  if (showAppearanceSheet && uiState.book != null) {
    val format = uiState.book!!.format
    ReaderAppearanceSheet(
      format = format,
      preferences = uiState.preferences,
      sleepTimerRemainingSeconds = uiState.sleepTimerRemainingSeconds,
      onSelectTheme = viewModel::setReaderTheme,
      onSelectFont = viewModel::setReaderFont,
      onFontSizeChange = viewModel::setFontSizeSp,
      onLineHeightChange = viewModel::setLineHeightMultiplier,
      onPaddingChange = viewModel::setHorizontalPaddingDp,
      onToggleScrollMode = { enabled ->
        if (format == BookFormat.EPUB) viewModel.setEpubScrollMode(enabled)
        else viewModel.setPdfScrollMode(enabled)
      },
      onTogglePdfNightFilter = viewModel::setPdfNightFilter,
      onBrightnessChange = viewModel::setReaderBrightness,
      onOrientationLockChange = viewModel::setOrientationLock,
      onSelectSleepTimerMinutes = viewModel::startSleepTimer,
      onDismiss = { showAppearanceSheet = false }
    )
  }

  // TOC, Bookmarks & Highlights/Notes Sheet
  if (showTocSheet && uiState.book != null) {
    val stubs = (uiState.contentState as? ReaderContentState.EpubReady)?.chapterStubs.orEmpty()
    val pdfReady = uiState.contentState as? ReaderContentState.PdfReady
    val pdfCount = pdfReady?.pageCount ?: 0
    val pdfChapters = pdfReady?.chapters.orEmpty()
    ReaderTocAndBookmarksSheet(
      format = uiState.book!!.format,
      chapterStubs = stubs,
      pdfChapters = pdfChapters,
      pdfPageCount = pdfCount,
      currentUnit = uiState.currentUnit,
      bookmarks = uiState.bookmarks,
      highlights = uiState.highlights,
      onJumpToUnit = viewModel::jumpToUnit,
      onDeleteBookmark = viewModel::deleteBookmark,
      onDeleteHighlight = viewModel::deleteHighlight,
      initialTab = tocInitialTab,
      onDismiss = { showTocSheet = false }
    )
  }

  // Search & Preguntar al libro Sheet
  if (showSearchSheet && uiState.book?.format == BookFormat.EPUB) {
    ReaderSearchSheet(
      initialTab = searchInitialTab,
      query = uiState.searchQuery,
      results = uiState.searchResults,
      askQuestion = uiState.askQuestion,
      bookAnswer = uiState.bookAnswer,
      onQueryChange = viewModel::updateSearchQuery,
      onAskQuestion = viewModel::askBook,
      onSelectChapter = viewModel::jumpToUnit,
      onDismiss = { showSearchSheet = false }
    )
  }

  // Fragment Tools Sheet (Highlight + Sticky Note + Dictionary + Translation + Vocab + Copy/Share + TTS)
  activeFragment?.let { frag ->
    val book = uiState.book
    FragmentToolsSheet(
      bookTitle = book?.title.orEmpty(),
      bookAuthor = book?.author.orEmpty(),
      fragmentText = frag.text,
      blockIndex = frag.blockIndex,
      hasHighlightableRange = frag.hasSelection && (frag.blockIndex >= 0 || (frag.startCharOffset >= 0 && frag.endCharOffset > frag.startCharOffset)),
      hasSelection = frag.hasSelection,
      onSaveHighlight = { note, color, isStickyOnly ->
        viewModel.saveHighlightOrStickyNote(
          unitIndex = uiState.currentUnit,
          blockIndex = frag.blockIndex,
          endBlockIndex = frag.endBlockIndex,
          startCharOffset = frag.startCharOffset,
          endCharOffset = frag.endCharOffset,
          excerpt = frag.text,
          stickyNote = note,
          color = color,
          isStickyNoteOnly = isStickyOnly
        )
        scope.launch {
          snackbarHostState.showSnackbar("Guardado en tus resaltados y notas")
        }
      },
      onReadAloudFromHere = if (uiState.book?.format == BookFormat.EPUB) {
        { viewModel.toggleTtsPlayPause(startAtBlock = frag.spokenIndex ?: 0) }
      } else null,
      onLookupWord = viewModel::lookupWord,
      onSaveWordToVocabulary = { word, onDone ->
        viewModel.saveWordToVocabulary(word, frag.text.take(180), onDone)
      },
      onDismiss = { activeFragment = null }
    )
  }

  // Chapter Summary Sheet
  uiState.chapterSummary?.let { summary ->
    ChapterSummarySheet(
      summary = summary,
      onDismiss = viewModel::clearChapterSummary
    )
  }

  if (showAddNoteDialog) {
    val label = when (val c = uiState.contentState) {
      is ReaderContentState.EpubReady ->
        c.chapterStubs.getOrNull(uiState.currentUnit)?.title ?: "Capítulo ${uiState.currentUnit + 1}"
      is ReaderContentState.PdfReady ->
        "Página ${uiState.currentUnit + 1} de ${c.pageCount}"
      else -> "Posición ${uiState.currentUnit + 1}"
    }
    AddBookmarkNoteDialog(
      defaultLabel = label,
      onSave = { note -> viewModel.saveBookmarkWithNote(note) },
      onDismiss = { showAddNoteDialog = false }
    )
  }

  if (uiState.sleepTimerExpiredDialog) {
    AlertDialog(
      onDismissRequest = viewModel::dismissSleepTimerExpired,
      title = { Text("Temporizador completado") },
      text = {
        Text("El temporizador de lectura ha finalizado y se detuvo la reproducción automática. Es buen momento para descansar la vista.")
      },
      confirmButton = {
        TextButton(onClick = viewModel::dismissSleepTimerExpired) {
          Text("Aceptar")
        }
      }
    )
  }
}

@Composable
private fun TtsFloatingMiniBar(
  speechRate: Float,
  surfaceColors: com.readicop.lettor.ui.theme.ReaderSurfaceColors,
  onPrev: () -> Unit,
  onPause: () -> Unit,
  onNext: () -> Unit,
  onCycleRate: () -> Unit
) {
  Surface(
    color = surfaceColors.surface.copy(alpha = 0.96f),
    shape = RoundedCornerShape(999.dp),
    shadowElevation = 6.dp
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
      IconButton(onClick = onPrev) {
        Icon(Icons.Default.SkipPrevious, contentDescription = "Párrafo anterior", tint = surfaceColors.text)
      }
      IconButton(onClick = onPause) {
        Icon(Icons.Default.Pause, contentDescription = "Pausar lectura en voz alta", tint = surfaceColors.accent)
      }
      IconButton(onClick = onNext) {
        Icon(Icons.Default.SkipNext, contentDescription = "Siguiente párrafo", tint = surfaceColors.text)
      }
      TextButton(onClick = onCycleRate) {
        Text(
          text = "${speechRate}x",
          style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
          color = surfaceColors.accent
        )
      }
    }
  }
}
