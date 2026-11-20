package com.readicop.lettor.ui.reader

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.readicop.lettor.data.local.BookEntity
import com.readicop.lettor.data.local.BookFormat
import com.readicop.lettor.data.local.BookmarkEntity
import com.readicop.lettor.data.local.HighlightColor
import com.readicop.lettor.data.local.HighlightEntity
import com.readicop.lettor.data.preferences.MAX_READER_FONT_SIZE_SP
import com.readicop.lettor.data.preferences.MIN_READER_FONT_SIZE_SP
import com.readicop.lettor.data.preferences.OrientationLockMode
import com.readicop.lettor.data.preferences.ReaderFont
import com.readicop.lettor.data.preferences.ReaderPreferences
import com.readicop.lettor.data.preferences.ReaderPreferencesRepository
import com.readicop.lettor.data.preferences.ReaderTheme
import com.readicop.lettor.data.preferences.normalizeReaderBrightness
import com.readicop.lettor.data.repository.LibraryRepository
import com.readicop.lettor.domain.engine.BookQuestionAnswer
import com.readicop.lettor.domain.engine.ChapterSummaryResult
import com.readicop.lettor.domain.engine.DictionaryEntry
import com.readicop.lettor.domain.engine.EpubChapter
import com.readicop.lettor.domain.engine.EpubChapterStub
import com.readicop.lettor.domain.engine.EpubMetadata
import com.readicop.lettor.domain.engine.EpubParser
import com.readicop.lettor.domain.engine.EpubSearchHit
import com.readicop.lettor.domain.engine.LazyEpubSession
import com.readicop.lettor.domain.engine.LocalBookIntelligence
import com.readicop.lettor.domain.engine.LocalDictionaryAndTranslator
import com.readicop.lettor.domain.engine.PdfBookSession
import com.readicop.lettor.domain.engine.PdfChapterOutlineItem
import com.readicop.lettor.domain.engine.TranslationResult
import com.readicop.lettor.domain.engine.TtsReaderController
import com.readicop.lettor.domain.engine.TtsState
import com.readicop.lettor.notifications.ResumeReadingScheduler
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ReaderContentState {
  data object Loading : ReaderContentState
  data class EpubReady(
    val metadata: EpubMetadata,
    val chapterStubs: List<EpubChapterStub>,
    val activeChapter: EpubChapter
  ) : ReaderContentState
  data class PdfReady(
    val pageCount: Int,
    val session: PdfBookSession,
    val chapters: List<PdfChapterOutlineItem> = emptyList()
  ) : ReaderContentState
  data class Error(val message: String) : ReaderContentState
}

data class ReaderUiState(
  val book: BookEntity? = null,
  val contentState: ReaderContentState = ReaderContentState.Loading,
  val currentUnit: Int = 0,
  val currentSubOffset: Int = 0,
  val horizontalChapterPositions: Map<String, EpubHorizontalPosition> = emptyMap(),
  val verticalChapterPositions: Map<String, EpubVerticalScrollPosition> = emptyMap(),
  val bookmarks: List<BookmarkEntity> = emptyList(),
  val highlights: List<HighlightEntity> = emptyList(),
  val preferences: ReaderPreferences = ReaderPreferences(),
  val searchQuery: String = "",
  val searchResults: List<EpubSearchHit> = emptyList(),
  val askQuestion: String = "",
  val bookAnswer: BookQuestionAnswer? = null,
  val chapterSummary: ChapterSummaryResult? = null,
  val ttsState: TtsState = TtsState(),
  val isAutoScrolling: Boolean = false,
  val sleepTimerRemainingSeconds: Int = 0,
  val sleepTimerExpiredDialog: Boolean = false
)

class ReaderViewModel(
  private val appContext: Context,
  private val bookId: Long,
  private val initialUnit: Int,
  private val repository: LibraryRepository,
  private val preferencesRepository: ReaderPreferencesRepository
) : ViewModel() {

  private val contentStateFlow = MutableStateFlow<ReaderContentState>(ReaderContentState.Loading)
  private val currentUnitFlow = MutableStateFlow(0)
  private val currentSubOffsetFlow = MutableStateFlow(0)
  private val searchQueryFlow = MutableStateFlow("")
  private val searchResultsFlow = MutableStateFlow<List<EpubSearchHit>>(emptyList())
  private val askQuestionFlow = MutableStateFlow("")
  private val bookAnswerFlow = MutableStateFlow<BookQuestionAnswer?>(null)
  private val chapterSummaryFlow = MutableStateFlow<ChapterSummaryResult?>(null)
  private val autoScrollingFlow = MutableStateFlow(false)
  private val sleepRemainingFlow = MutableStateFlow(0)
  private val sleepExpiredFlow = MutableStateFlow(false)
  private val liveBrightnessOverrideFlow = MutableStateFlow<Float?>(null)
  private val liveFontSizeOverrideFlow = MutableStateFlow<Float?>(null)
  private val pendingBrightnessPersistFlow = MutableSharedFlow<Float>(
    replay = 0,
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
  )
  private val pendingFontSizePersistFlow = MutableSharedFlow<Float>(
    replay = 0,
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
  )

  private var epubSession: LazyEpubSession? = null
  private var pdfSession: PdfBookSession? = null
  private var readingTimerJob: Job? = null
  private var sleepTimerJob: Job? = null
  private var chapterSwitchJob: Job? = null
  private val chapterSubOffsets = mutableMapOf<Int, Int>()
  private val horizontalChapterPositions = mutableMapOf<String, EpubHorizontalPosition>()
  private val horizontalPositionsByChapterFlow = MutableStateFlow<Map<String, EpubHorizontalPosition>>(emptyMap())
  private val verticalChapterPositions = mutableMapOf<String, EpubVerticalScrollPosition>()
  private val verticalPositionsByChapterFlow = MutableStateFlow<Map<String, EpubVerticalScrollPosition>>(emptyMap())
  private val chapterAnchors = mutableMapOf<String, EpubChapterAnchor>()

  private val ttsController: TtsReaderController = TtsReaderController(appContext) {
    // Advance to next chapter automatically when TTS finishes a chapter
    viewModelScope.launch(Dispatchers.Main) {
      val content = contentStateFlow.value
      if (content is ReaderContentState.EpubReady) {
        val nextIdx = currentUnitFlow.value + 1
        if (nextIdx < content.chapterStubs.size) {
          jumpToUnitInternal(nextIdx, resetSubOffset = true)
          delay(250)
          toggleTtsPlayPause(0)
        }
      }
    }
  }

  private val extraStateFlow = combine(
    combine(searchQueryFlow, searchResultsFlow) { q, hits -> q to hits },
    combine(askQuestionFlow, bookAnswerFlow, chapterSummaryFlow) { q, ans, sum -> Triple(q, ans, sum) },
    combine(ttsController.state, autoScrollingFlow) { tts, auto -> tts to auto },
    combine(sleepRemainingFlow, sleepExpiredFlow) { rem, exp -> rem to exp }
  ) { search, intel, playback, sleep ->
    ExtraReaderState(search.first, search.second, intel.first, intel.second, intel.third, playback.first, playback.second, sleep.first, sleep.second)
  }

  private data class ExtraReaderState(
    val searchQuery: String,
    val searchResults: List<EpubSearchHit>,
    val askQuestion: String,
    val bookAnswer: BookQuestionAnswer?,
    val chapterSummary: ChapterSummaryResult?,
    val ttsState: TtsState,
    val isAutoScrolling: Boolean,
    val sleepRemainingSeconds: Int,
    val sleepExpiredDialog: Boolean
  )

  private val effectivePreferencesFlow = combine(
    preferencesRepository.preferencesFlow,
    liveBrightnessOverrideFlow,
    liveFontSizeOverrideFlow
  ) { prefs, brightnessOverride, fontSizeOverride ->
    prefs.copy(
      readerBrightness = brightnessOverride ?: prefs.readerBrightness,
      fontSizeSp = fontSizeOverride ?: prefs.fontSizeSp
    )
  }

  private data class PositionStateTuple(
    val unit: Int,
    val subOffset: Int,
    val horizontalPositions: Map<String, EpubHorizontalPosition>,
    val verticalPositions: Map<String, EpubVerticalScrollPosition>
  )

  private val positionStateFlow = combine(
    currentUnitFlow,
    currentSubOffsetFlow,
    horizontalPositionsByChapterFlow,
    verticalPositionsByChapterFlow
  ) { u, s, h, v -> PositionStateTuple(u, s, h, v) }

  val uiState: StateFlow<ReaderUiState> = combine(
    repository.observeBook(bookId),
    contentStateFlow,
    positionStateFlow,
    combine(repository.observeBookmarksForBook(bookId), repository.observeHighlightsForBook(bookId)) { bm, hl -> bm to hl },
    combine(effectivePreferencesFlow, extraStateFlow) { prefs, extra -> prefs to extra }
  ) { book, content, position, annotations, prefExtra ->
    val (prefs, extra) = prefExtra
    ReaderUiState(
      book = book,
      contentState = content,
      currentUnit = position.unit,
      currentSubOffset = position.subOffset,
      horizontalChapterPositions = position.horizontalPositions,
      verticalChapterPositions = position.verticalPositions,
      bookmarks = annotations.first,
      highlights = annotations.second,
      preferences = prefs,
      searchQuery = extra.searchQuery,
      searchResults = extra.searchResults,
      askQuestion = extra.askQuestion,
      bookAnswer = extra.bookAnswer,
      chapterSummary = extra.chapterSummary,
      ttsState = extra.ttsState,
      isAutoScrolling = extra.isAutoScrolling,
      sleepTimerRemainingSeconds = extra.sleepRemainingSeconds,
      sleepTimerExpiredDialog = extra.sleepExpiredDialog
    )
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5000),
    initialValue = ReaderUiState()
  )

  private val _epubImageCache = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
  val epubImageCache: StateFlow<Map<String, Bitmap>> = _epubImageCache.asStateFlow()

  private val backgroundNotificationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  init {
    ResumeReadingScheduler.cancelForBook(appContext, bookId)
    loadBook()
    startReadingTimer()
    viewModelScope.launch {
      pendingBrightnessPersistFlow.collect { brightness ->
        withContext(NonCancellable) {
          preferencesRepository.setReaderBrightness(brightness)
        }
      }
    }
    viewModelScope.launch {
      pendingFontSizePersistFlow.collect { sizeSp ->
        withContext(NonCancellable) {
          preferencesRepository.setFontSizeSp(sizeSp)
        }
      }
    }
    viewModelScope.launch {
      preferencesRepository.setActiveReadingBookId(bookId)
      preferencesRepository.markHasOpenedAnyBook()
    }
  }

  private fun loadBook() {
    viewModelScope.launch {
      contentStateFlow.value = ReaderContentState.Loading
      val book = repository.getBook(bookId)
      if (book == null) {
        contentStateFlow.value = ReaderContentState.Error("No se encontró el libro en la biblioteca.")
        return@launch
      }

      val file = File(book.filePath)
      if (!file.exists()) {
        contentStateFlow.value = ReaderContentState.Error("El archivo del libro ya no está disponible.")
        return@launch
      }

      val startUnit = if (initialUnit >= 0) initialUnit else book.currentUnit
      val startSub = if (initialUnit >= 0) 0 else book.currentSubOffset
      currentUnitFlow.value = startUnit
      currentSubOffsetFlow.value = startSub

      runCatching {
        when (book.format) {
          BookFormat.EPUB -> {
            val session = withContext(Dispatchers.IO) { LazyEpubSession(file).open() }
            epubSession = session
            val safeUnit = startUnit.coerceIn(0, (session.chapterStubs.size - 1).coerceAtLeast(0))
            val safeSub = startSub.coerceAtLeast(0)
            chapterSubOffsets.clear()
            horizontalChapterPositions.clear()
            verticalChapterPositions.clear()
            chapterAnchors.clear()
            val activeChapter = withContext(Dispatchers.IO) { session.getChapter(safeUnit) }
            val chId = epubChapterUniqueId(activeChapter, session.chapterStubs)
            val prefs = preferencesRepository.preferencesFlow.first()
            if (prefs.epubScrollMode && safeSub > 0) {
              val clampedBlock = safeSub.coerceIn(0, (activeChapter.blocks.size - 1).coerceAtLeast(0))
              verticalChapterPositions[chId] = EpubVerticalScrollPosition(
                chapterId = chId,
                chapterIndex = safeUnit,
                blockIndex = clampedBlock
              )
              chapterAnchors[chId] = EpubChapterAnchor(
                chapterId = chId,
                chapterIndex = safeUnit,
                blockIndex = clampedBlock
              )
            } else if (!prefs.epubScrollMode && safeSub > 0) {
              chapterSubOffsets[safeUnit] = safeSub
              horizontalChapterPositions[chId] = EpubHorizontalPosition(
                chapterId = chId,
                chapterIndex = safeUnit,
                subPage = safeSub,
                hasSemanticAnchor = false
              )
            }
            horizontalPositionsByChapterFlow.value = horizontalChapterPositions.toMap()
            verticalPositionsByChapterFlow.value = verticalChapterPositions.toMap()
            currentUnitFlow.value = safeUnit
            currentSubOffsetFlow.value = safeSub
            contentStateFlow.value = ReaderContentState.EpubReady(
              metadata = session.metadata,
              chapterStubs = session.chapterStubs,
              activeChapter = activeChapter
            )
            loadChapterIntoTts(activeChapter)
            saveProgressInternal(book, safeUnit, safeSub, session.chapterStubs.size)
          }
          BookFormat.PDF -> {
            val session = PdfBookSession(file, appContext)
            pdfSession = session
            val pageCount = session.open()
            if (pageCount <= 0) {
              session.close()
              contentStateFlow.value = ReaderContentState.Error("El PDF no contiene páginas legibles.")
            } else {
              val safeUnit = startUnit.coerceIn(0, pageCount - 1)
              val chapters = session.extractOutline()
              currentUnitFlow.value = safeUnit
              contentStateFlow.value = ReaderContentState.PdfReady(pageCount, session, chapters)
              saveProgressInternal(book, safeUnit, 0, pageCount)
            }
          }
        }
      }.onFailure { err ->
        contentStateFlow.value = ReaderContentState.Error(
          err.localizedMessage ?: "El archivo parece estar dañado o no es compatible."
        )
      }
    }
  }

  internal fun rememberedSubOffsetForChapter(chapterIndex: Int): Int {
    val content = contentStateFlow.value as? ReaderContentState.EpubReady
    if (content != null && chapterIndex in content.chapterStubs.indices) {
      val stub = content.chapterStubs[chapterIndex]
      val chId = epubChapterUniqueId(stub.index, stub.zipPath, content.chapterStubs)
      horizontalChapterPositions[chId]?.let { return it.subPage.coerceAtLeast(0) }
    }
    return chapterSubOffsets[chapterIndex]?.coerceAtLeast(0) ?: 0
  }

  internal fun savedHorizontalPositionForChapter(chapterId: String): EpubHorizontalPosition? =
    horizontalChapterPositions[chapterId]

  internal fun savedVerticalPositionForChapter(chapterId: String): EpubVerticalScrollPosition? =
    verticalChapterPositions[chapterId]

  fun onHorizontalPositionChanged(
    chapter: EpubChapter,
    position: EpubHorizontalPosition,
    overallProgressFraction: Float
  ) {
    val content = contentStateFlow.value as? ReaderContentState.EpubReady ?: return
    val chId = epubChapterUniqueId(chapter, content.chapterStubs)
    val safeSub = position.subPage.coerceAtLeast(0)
    val normalized = position.copy(
      chapterId = chId,
      chapterIndex = chapter.index,
      subPage = safeSub
    )
    chapterSubOffsets[chapter.index] = safeSub
    horizontalChapterPositions[chId] = normalized
    horizontalPositionsByChapterFlow.value = horizontalChapterPositions.toMap()
    if (normalized.hasSemanticAnchor) {
      chapterAnchors[chId] = EpubChapterAnchor(
        chapterId = chId,
        chapterIndex = chapter.index,
        blockIndex = normalized.anchorBlockIndex.coerceAtLeast(0),
        charOffsetInBlock = normalized.anchorCharOffsetInBlock.coerceAtLeast(0),
        chapterProgressFraction = normalized.chapterProgressFraction.coerceIn(0f, 1f)
      )
    }

    if (content.activeChapter.index != chapter.index) {
      return
    }
    val totalUnits = content.chapterStubs.size.coerceAtLeast(1)
    currentUnitFlow.value = chapter.index
    currentSubOffsetFlow.value = safeSub
    val book = uiState.value.book ?: return
    viewModelScope.launch {
      repository.updateProgress(
        bookId = book.id,
        unit = chapter.index,
        subOffset = safeSub,
        progress = overallProgressFraction.coerceIn(0f, 1f),
        isFinished = chapter.index >= totalUnits - 1 && overallProgressFraction >= 0.96f
      )
    }
  }

  fun onVerticalPositionChanged(
    chapter: EpubChapter,
    position: EpubVerticalScrollPosition,
    overallProgressFraction: Float
  ) {
    val content = contentStateFlow.value as? ReaderContentState.EpubReady ?: return
    val chId = epubChapterUniqueId(chapter, content.chapterStubs)
    val normalized = position.copy(
      chapterId = chId,
      chapterIndex = chapter.index
    )
    verticalChapterPositions[chId] = normalized
    verticalPositionsByChapterFlow.value = verticalChapterPositions.toMap()
    val totalBlocks = chapter.blocks.size.coerceAtLeast(1)
    chapterAnchors[chId] = EpubChapterAnchor(
      chapterId = chId,
      chapterIndex = chapter.index,
      blockIndex = normalized.blockIndex.coerceAtLeast(0),
      charOffsetInBlock = normalized.characterOffsetInBlock.coerceAtLeast(0),
      blockScrollFraction = normalized.blockScrollFraction.coerceIn(0f, 1f),
      chapterProgressFraction = ((normalized.blockIndex + 1).toFloat() / totalBlocks.toFloat()).coerceIn(0f, 1f)
    )

    if (content.activeChapter.index != chapter.index) {
      return
    }
    val totalUnits = content.chapterStubs.size.coerceAtLeast(1)
    currentUnitFlow.value = chapter.index
    currentSubOffsetFlow.value = normalized.blockIndex.coerceAtLeast(0)
    val book = uiState.value.book ?: return
    viewModelScope.launch {
      repository.updateProgress(
        bookId = book.id,
        unit = chapter.index,
        subOffset = normalized.blockIndex.coerceAtLeast(0),
        progress = overallProgressFraction.coerceIn(0f, 1f),
        isFinished = chapter.index >= totalUnits - 1 && overallProgressFraction >= 0.96f
      )
    }
  }

  fun jumpToUnit(targetUnit: Int) = jumpToUnitInternal(targetUnit, resetSubOffset = false)

  private fun jumpToUnitInternal(targetUnit: Int, resetSubOffset: Boolean) {
    val content = contentStateFlow.value
    when (content) {
      is ReaderContentState.EpubReady -> {
        val safeIdx = targetUnit.coerceIn(0, (content.chapterStubs.size - 1).coerceAtLeast(0))
        chapterSwitchJob?.cancel()
        chapterSwitchJob = viewModelScope.launch {
          val chapter = withContext(Dispatchers.IO) {
            epubSession?.getChapter(safeIdx)
          } ?: return@launch
          val latestContent = contentStateFlow.value as? ReaderContentState.EpubReady ?: content
          val chId = epubChapterUniqueId(chapter, latestContent.chapterStubs)
          val isScrollMode = uiState.value.preferences.epubScrollMode

          if (resetSubOffset) {
            chapterSubOffsets.remove(safeIdx)
            horizontalChapterPositions.remove(chId)
            verticalChapterPositions.remove(chId)
            chapterAnchors.remove(chId)
          } else {
            // Bridge semantic text anchor if the user visited this chapter in the other reading mode
            if (!isScrollMode && !horizontalChapterPositions.containsKey(chId)) {
              chapterAnchors[chId]?.let { anchor ->
                horizontalChapterPositions[chId] = EpubHorizontalPosition(
                  chapterId = chId,
                  chapterIndex = safeIdx,
                  subPage = 0,
                  anchorBlockIndex = anchor.blockIndex,
                  anchorCharOffsetInBlock = anchor.charOffsetInBlock,
                  chapterProgressFraction = anchor.chapterProgressFraction,
                  hasSemanticAnchor = true,
                  layoutKey = null
                )
              }
            } else if (isScrollMode && !verticalChapterPositions.containsKey(chId)) {
              chapterAnchors[chId]?.let { anchor ->
                verticalChapterPositions[chId] = EpubVerticalScrollPosition(
                  chapterId = chId,
                  chapterIndex = safeIdx,
                  blockIndex = anchor.blockIndex,
                  scrollOffsetPx = 0,
                  blockScrollFraction = anchor.blockScrollFraction,
                  characterOffsetInBlock = anchor.charOffsetInBlock,
                  layoutSignature = null
                )
              }
            }
          }

          val restoredSub = if (resetSubOffset) {
            0
          } else if (!isScrollMode) {
            (horizontalChapterPositions[chId]?.subPage ?: chapterSubOffsets[safeIdx] ?: 0).coerceAtLeast(0)
          } else {
            verticalChapterPositions[chId]?.blockIndex?.coerceAtLeast(0) ?: 0
          }

          if (!isScrollMode && !resetSubOffset) {
            chapterSubOffsets[safeIdx] = restoredSub
          }
          horizontalPositionsByChapterFlow.value = horizontalChapterPositions.toMap()
          verticalPositionsByChapterFlow.value = verticalChapterPositions.toMap()
          currentSubOffsetFlow.value = restoredSub
          currentUnitFlow.value = safeIdx
          contentStateFlow.value = latestContent.copy(activeChapter = chapter)
          loadChapterIntoTts(chapter)
          val totalUnits = latestContent.chapterStubs.size.coerceAtLeast(1)
          val prog = ((safeIdx + 1).toFloat() / totalUnits.toFloat()).coerceIn(0f, 1f)
          val book = uiState.value.book ?: return@launch
          repository.updateProgress(
            bookId = book.id,
            unit = safeIdx,
            subOffset = restoredSub,
            progress = prog,
            isFinished = safeIdx >= totalUnits - 1 && prog >= 0.96f
          )
        }
      }
      is ReaderContentState.PdfReady -> {
        val safeIdx = targetUnit.coerceIn(0, (content.pageCount - 1).coerceAtLeast(0))
        val prog = (safeIdx + 1).toFloat() / content.pageCount.coerceAtLeast(1)
        onPositionChanged(safeIdx, 0, prog)
      }
      else -> Unit
    }
  }

  fun onPositionChanged(unitIndex: Int, subOffset: Int = 0, unitProgressFraction: Float? = null) {
    val content = contentStateFlow.value
    val totalUnits = when (content) {
      is ReaderContentState.EpubReady -> content.chapterStubs.size
      is ReaderContentState.PdfReady -> content.pageCount
      else -> return
    }.coerceAtLeast(1)

    val safeUnit = unitIndex.coerceIn(0, totalUnits - 1)
    if (content is ReaderContentState.EpubReady && content.activeChapter.index != safeUnit) {
      jumpToUnit(safeUnit)
      return
    }

    val safeSub = subOffset.coerceAtLeast(0)
    chapterSubOffsets[safeUnit] = safeSub
    if (content is ReaderContentState.EpubReady) {
      val chId = epubChapterUniqueId(content.activeChapter, content.chapterStubs)
      val existing = horizontalChapterPositions[chId]
      if (existing == null || existing.subPage != safeSub) {
        horizontalChapterPositions[chId] = EpubHorizontalPosition(
          chapterId = chId,
          chapterIndex = safeUnit,
          subPage = safeSub,
          hasSemanticAnchor = false
        )
        horizontalPositionsByChapterFlow.value = horizontalChapterPositions.toMap()
      }
    }
    currentUnitFlow.value = safeUnit
    currentSubOffsetFlow.value = safeSub

    val overallProgress = unitProgressFraction ?: ((safeUnit + 1).toFloat() / totalUnits.toFloat())
    val book = uiState.value.book ?: return
    viewModelScope.launch {
      repository.updateProgress(
        bookId = book.id,
        unit = safeUnit,
        subOffset = safeSub,
        progress = overallProgress.coerceIn(0f, 1f),
        isFinished = safeUnit >= totalUnits - 1 && overallProgress >= 0.96f
      )
    }
  }

  private fun loadChapterIntoTts(chapter: EpubChapter) {
    val spokenBlocks = chapter.blocks.mapNotNull { block ->
      when (block) {
        is com.readicop.lettor.domain.engine.EpubBlock.Heading -> block.text
        is com.readicop.lettor.domain.engine.EpubBlock.Paragraph -> block.plainText
        is com.readicop.lettor.domain.engine.EpubBlock.Quote -> block.plainText
        is com.readicop.lettor.domain.engine.EpubBlock.Image -> null
      }
    }
    ttsController.loadParagraphs(spokenBlocks, 0)
  }

  // TTS Controls
  fun toggleTtsPlayPause(startAtBlock: Int? = null) {
    if (ttsController.state.value.isPlaying && startAtBlock == null) {
      ttsController.pause()
    } else {
      ttsController.play(startAtBlock)
    }
  }

  fun pauseTts() = ttsController.pause()
  fun nextTtsParagraph() = ttsController.nextParagraph()
  fun prevTtsParagraph() = ttsController.previousParagraph()
  fun setTtsRate(rate: Float) {
    ttsController.setSpeechRate(rate)
    viewModelScope.launch { preferencesRepository.setTtsSpeechRate(rate) }
  }

  // Auto-scroll Controls
  fun toggleAutoScroll() {
    val next = !autoScrollingFlow.value
    autoScrollingFlow.value = next
    if (next) {
      val format = uiState.value.book?.format
      viewModelScope.launch {
        if (format == BookFormat.EPUB) preferencesRepository.setEpubScrollMode(true)
        else if (format == BookFormat.PDF) preferencesRepository.setPdfScrollMode(true)
      }
    }
  }

  fun setAutoScrollSpeed(speed: Float) {
    viewModelScope.launch { preferencesRepository.setAutoScrollSpeed(speed) }
  }

  // Sleep Timer
  fun startSleepTimer(minutes: Int) {
    sleepTimerJob?.cancel()
    sleepExpiredFlow.value = false
    if (minutes <= 0) {
      sleepRemainingFlow.value = 0
      return
    }
    sleepRemainingFlow.value = minutes * 60
    sleepTimerJob = viewModelScope.launch {
      while (sleepRemainingFlow.value > 0) {
        delay(1000L)
        sleepRemainingFlow.value = (sleepRemainingFlow.value - 1).coerceAtLeast(0)
      }
      ttsController.pause()
      autoScrollingFlow.value = false
      sleepExpiredFlow.value = true
    }
  }

  fun dismissSleepTimerExpired() {
    sleepExpiredFlow.value = false
  }

  // Highlights & Sticky Notes
  fun saveHighlightOrStickyNote(
    unitIndex: Int,
    blockIndex: Int,
    endBlockIndex: Int = blockIndex,
    startCharOffset: Int = -1,
    endCharOffset: Int = -1,
    excerpt: String,
    stickyNote: String,
    color: HighlightColor,
    isStickyNoteOnly: Boolean
  ) {
    val book = uiState.value.book ?: return
    viewModelScope.launch {
      repository.addHighlight(
        HighlightEntity(
          bookId = book.id,
          unitIndex = unitIndex,
          blockIndex = blockIndex,
          endBlockIndex = endBlockIndex,
          startCharOffset = startCharOffset,
          endCharOffset = endCharOffset,
          excerpt = excerpt.trim(),
          stickyNote = stickyNote.trim(),
          color = color,
          isStickyNoteOnly = isStickyNoteOnly
        )
      )
    }
  }

  fun deleteHighlight(id: Long) {
    viewModelScope.launch { repository.removeHighlight(id) }
  }

  // Dictionary, Translation, & Vocabulary Builder
  fun lookupWord(word: String): DictionaryEntry = LocalDictionaryAndTranslator.lookupWord(word)

  fun translateFragment(text: String): TranslationResult = LocalDictionaryAndTranslator.translateLocally(text)

  fun saveWordToVocabulary(word: String, contextSnippet: String, onResult: (Boolean) -> Unit) {
    val book = uiState.value.book
    viewModelScope.launch {
      val added = repository.saveWordToVocabulary(
        rawWord = word,
        bookId = book?.id,
        bookTitle = book?.title.orEmpty(),
        contextSnippet = contextSnippet
      )
      onResult(added)
    }
  }

  // Local AI / NLP Intelligence: Chapter Summary & Ask the Book
  fun generateCurrentChapterSummary() {
    val content = contentStateFlow.value
    if (content !is ReaderContentState.EpubReady) return
    val wpm = uiState.value.preferences.estimatedWpm
    viewModelScope.launch(Dispatchers.Default) {
      val summary = LocalBookIntelligence.summarizeChapter(content.activeChapter, wpm)
      chapterSummaryFlow.value = summary
    }
  }

  fun clearChapterSummary() {
    chapterSummaryFlow.value = null
  }

  fun askBook(question: String) {
    askQuestionFlow.value = question
    if (question.isBlank()) {
      bookAnswerFlow.value = null
      return
    }
    val session = epubSession ?: return
    viewModelScope.launch(Dispatchers.Default) {
      val doc = session.toEpubDocumentForQuery()
      bookAnswerFlow.value = LocalBookIntelligence.askBook(doc, question)
    }
  }

  private suspend fun saveProgressInternal(book: BookEntity, unit: Int, subOffset: Int, totalUnits: Int) {
    val progress = if (totalUnits <= 1) 1f else ((unit + 1).toFloat() / totalUnits.toFloat()).coerceIn(0f, 1f)
    repository.updateProgress(
      bookId = book.id,
      unit = unit,
      subOffset = subOffset,
      progress = if (book.progressFraction > 0f && unit == book.currentUnit) book.progressFraction else progress,
      isFinished = book.isFinished
    )
  }

  fun requestEpubImage(zipEntryPath: String) {
    if (_epubImageCache.value.containsKey(zipEntryPath)) return
    val book = uiState.value.book ?: return
    viewModelScope.launch(Dispatchers.IO) {
      val bmp = EpubParser.loadChapterImage(File(book.filePath), zipEntryPath)
      if (bmp != null) {
        _epubImageCache.value = _epubImageCache.value + (zipEntryPath to bmp)
      }
    }
  }

  fun toggleCurrentBookmark() {
    val state = uiState.value
    val book = state.book ?: return
    val unit = state.currentUnit
    val existing = state.bookmarks.firstOrNull { it.unitIndex == unit }
    viewModelScope.launch {
      if (existing != null) {
        repository.removeBookmark(existing.id)
      } else {
        val (label, excerpt) = buildDefaultBookmarkInfo(state, unit)
        repository.addBookmark(
          BookmarkEntity(
            bookId = book.id,
            unitIndex = unit,
            subOffset = state.currentSubOffset,
            label = label,
            excerpt = excerpt,
            note = ""
          )
        )
      }
    }
  }

  fun saveBookmarkWithNote(note: String, customExcerpt: String? = null) {
    val state = uiState.value
    val book = state.book ?: return
    val unit = state.currentUnit
    val (label, defaultExcerpt) = buildDefaultBookmarkInfo(state, unit)
    viewModelScope.launch {
      repository.addBookmark(
        BookmarkEntity(
          bookId = book.id,
          unitIndex = unit,
          subOffset = state.currentSubOffset,
          label = label,
          excerpt = customExcerpt?.ifBlank { null } ?: defaultExcerpt,
          note = note.trim()
        )
      )
    }
  }

  fun deleteBookmark(bookmarkId: Long) {
    viewModelScope.launch { repository.removeBookmark(bookmarkId) }
  }

  private fun buildDefaultBookmarkInfo(state: ReaderUiState, unit: Int): Pair<String, String> {
    return when (val content = state.contentState) {
      is ReaderContentState.EpubReady -> {
        val label = content.chapterStubs.getOrNull(unit)?.title ?: "Capítulo ${unit + 1}"
        val excerpt = content.activeChapter.plainText.take(140).replace('\n', ' ').trim()
        label to excerpt
      }
      is ReaderContentState.PdfReady -> {
        val label = "Página ${unit + 1} de ${content.pageCount}"
        label to state.book?.title.orEmpty()
      }
      else -> "Posición ${unit + 1}" to ""
    }
  }

  fun updateSearchQuery(query: String) {
    searchQueryFlow.value = query
    val session = epubSession
    if (query.isBlank() || session == null) {
      searchResultsFlow.value = emptyList()
      return
    }
    viewModelScope.launch(Dispatchers.IO) {
      searchResultsFlow.value = session.searchAllChapters(query)
    }
  }

  fun setReaderTheme(theme: ReaderTheme) = viewModelScope.launch { preferencesRepository.setReaderTheme(theme) }
  fun setReaderFont(font: ReaderFont) = viewModelScope.launch { preferencesRepository.setReaderFont(font) }
  fun setFontSizeSp(size: Float) {
    val normalized = size.coerceIn(MIN_READER_FONT_SIZE_SP, MAX_READER_FONT_SIZE_SP)
    liveFontSizeOverrideFlow.value = normalized
    pendingFontSizePersistFlow.tryEmit(normalized)
  }
  fun setLineHeightMultiplier(mult: Float) = viewModelScope.launch { preferencesRepository.setLineHeightMultiplier(mult) }
  fun setHorizontalPaddingDp(paddingDp: Int) = viewModelScope.launch { preferencesRepository.setHorizontalPaddingDp(paddingDp) }
  fun setEpubScrollMode(scroll: Boolean) = viewModelScope.launch { preferencesRepository.setEpubScrollMode(scroll) }
  fun setPdfScrollMode(scroll: Boolean) = viewModelScope.launch { preferencesRepository.setPdfScrollMode(scroll) }
  fun setPdfNightFilter(enabled: Boolean) = viewModelScope.launch { preferencesRepository.setPdfNightFilter(enabled) }
  fun setReaderBrightness(brightness: Float) {
    val normalized = normalizeReaderBrightness(brightness)
    liveBrightnessOverrideFlow.value = normalized
    pendingBrightnessPersistFlow.tryEmit(normalized)
  }
  fun setOrientationLock(mode: OrientationLockMode) = viewModelScope.launch { preferencesRepository.setOrientationLock(mode) }

  fun onReaderResumed() {
    ResumeReadingScheduler.cancelForBook(appContext, bookId)
  }

  fun onReaderStopped() {
    backgroundNotificationScope.launch {
      ResumeReadingScheduler.scheduleForBook(appContext, bookId)
    }
  }

  fun onExitReaderNormally() {
    ttsController.pause()
    autoScrollingFlow.value = false
    sleepTimerJob?.cancel()
    backgroundNotificationScope.launch {
      preferencesRepository.setActiveReadingBookId(-1L)
      ResumeReadingScheduler.scheduleForBook(appContext, bookId)
    }
  }

  private fun startReadingTimer() {
    readingTimerJob?.cancel()
    readingTimerJob = viewModelScope.launch {
      while (true) {
        delay(15_000L)
        repository.addReadingTime(bookId, 15L)
      }
    }
  }

  override fun onCleared() {
    readingTimerJob?.cancel()
    sleepTimerJob?.cancel()
    ttsController.shutdown()
    epubSession?.close()
    pdfSession?.close()
    super.onCleared()
  }

  class Factory(
    private val appContext: Context,
    private val bookId: Long,
    private val initialUnit: Int,
    private val repository: LibraryRepository,
    private val preferencesRepository: ReaderPreferencesRepository
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return ReaderViewModel(appContext, bookId, initialUnit, repository, preferencesRepository) as T
    }
  }
}
