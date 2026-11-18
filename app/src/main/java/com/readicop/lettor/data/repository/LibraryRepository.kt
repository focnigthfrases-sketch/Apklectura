package com.readicop.lettor.data.repository

import android.content.Context
import android.net.Uri
import com.readicop.lettor.data.local.BookDao
import com.readicop.lettor.data.local.BookEntity
import com.readicop.lettor.data.local.BookmarkDao
import com.readicop.lettor.data.local.BookmarkEntity
import com.readicop.lettor.data.local.BookmarkWithBook
import com.readicop.lettor.data.local.HighlightColor
import com.readicop.lettor.data.local.HighlightDao
import com.readicop.lettor.data.local.HighlightEntity
import com.readicop.lettor.data.local.HighlightWithBook
import com.readicop.lettor.data.local.VocabularyDao
import com.readicop.lettor.data.local.VocabularyEntity
import com.readicop.lettor.data.preferences.ReaderPreferencesRepository
import com.readicop.lettor.domain.engine.BackupManager
import com.readicop.lettor.domain.engine.BackupRestoreSummary
import com.readicop.lettor.domain.engine.BatchImportSummary
import com.readicop.lettor.domain.engine.BookImporter
import com.readicop.lettor.domain.engine.ImportResult
import com.readicop.lettor.domain.engine.LocalDictionaryAndTranslator
import com.readicop.lettor.notifications.ResumeReadingScheduler
import com.readicop.lettor.widget.LettorContinueWidgetProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LibraryRepository(
  private val appContext: Context,
  private val bookDao: BookDao,
  private val bookmarkDao: BookmarkDao,
  private val highlightDao: HighlightDao,
  private val vocabularyDao: VocabularyDao,
  private val importer: BookImporter,
  private val backupManager: BackupManager,
  private val preferencesRepository: ReaderPreferencesRepository
) {
  private val seedMutex = Mutex()

  val allBooks: Flow<List<BookEntity>> = bookDao.observeAllBooks()

  val allBookmarksWithBooks: Flow<List<BookmarkWithBook>> = combine(
    bookmarkDao.observeAllBookmarks(),
    bookDao.observeAllBooks()
  ) { bookmarks, books ->
    val bookMap = books.associateBy { it.id }
    bookmarks.mapNotNull { bm ->
      val book = bookMap[bm.bookId] ?: return@mapNotNull null
      BookmarkWithBook(
        bookmark = bm,
        bookTitle = book.title,
        bookAuthor = book.author,
        bookFormat = book.format,
        bookCoverPath = book.coverPath
      )
    }
  }

  val allHighlightsWithBooks: Flow<List<HighlightWithBook>> = combine(
    highlightDao.observeAllHighlights(),
    bookDao.observeAllBooks()
  ) { highlights, books ->
    val bookMap = books.associateBy { it.id }
    highlights.mapNotNull { hl ->
      val book = bookMap[hl.bookId] ?: return@mapNotNull null
      HighlightWithBook(
        highlight = hl,
        bookTitle = book.title,
        bookAuthor = book.author,
        bookFormat = book.format
      )
    }
  }

  val allVocabulary: Flow<List<VocabularyEntity>> = vocabularyDao.observeAllVocabulary()

  fun observeBook(bookId: Long): Flow<BookEntity?> = bookDao.observeBookById(bookId)

  suspend fun getBook(bookId: Long): BookEntity? = bookDao.getBookById(bookId)

  fun observeBookmarksForBook(bookId: Long): Flow<List<BookmarkEntity>> =
    bookmarkDao.observeBookmarksForBook(bookId)

  fun observeHighlightsForBook(bookId: Long): Flow<List<HighlightEntity>> =
    highlightDao.observeHighlightsForBook(bookId)

  suspend fun ensureSampleBooksSeeded() {
    seedMutex.withLock {
      val prefs = preferencesRepository.preferencesFlow.first()
      if (!prefs.samplesSeeded) {
        if (bookDao.getBookCount() == 0) {
          val samples = importer.createBundledSamples()
          samples.forEachIndexed { idx, sample ->
            val insertedId = bookDao.insertBook(sample)
            if (idx == 0) {
              bookmarkDao.insertBookmark(
                BookmarkEntity(
                  bookId = insertedId,
                  unitIndex = 2,
                  subOffset = 0,
                  label = "Capítulo VIII · De los molinos de viento",
                  excerpt = "—Mire vuestra merced —respondió Sancho— que aquellos que allí se parecen no son gigantes, sino molinos de viento.",
                  note = "Pasaje clásico de los molinos de viento"
                )
              )
              highlightDao.insertHighlight(
                HighlightEntity(
                  bookId = insertedId,
                  unitIndex = 0,
                  blockIndex = 2,
                  excerpt = "El sosiego, el lugar apacible, la amenidad de los campos, la serenidad de los cielos, el murmurar de las fuentes, la quietud del espíritu son grande parte para que las musas más estériles se muestren fecundas.",
                  stickyNote = "Elogio del sosiego para crear e imaginar.",
                  color = HighlightColor.AMBER,
                  isStickyNoteOnly = false
                )
              )
              vocabularyDao.insertWord(
                VocabularyEntity(
                  word = "Hidalgo",
                  normalizedWord = "hidalgo",
                  partOfSpeech = "sustantivo",
                  definition = "Persona que por linaje o nobleza pertenece a una clase distinguida.",
                  translation = "nobleman / gentleman",
                  bookId = insertedId,
                  bookTitle = sample.title,
                  contextSnippet = "…vivía un hidalgo de los de lanza en astillero, adarga antigua…"
                )
              )
            }
          }
          LettorContinueWidgetProvider.requestRefresh(appContext)
        }
        preferencesRepository.markSamplesSeeded()
      }
    }
  }

  suspend fun importMultipleBooks(uris: List<Uri>): BatchImportSummary {
    val imported = mutableListOf<BookEntity>()
    val duplicates = mutableListOf<BookEntity>()
    val errors = mutableListOf<String>()

    for (uri in uris) {
      when (val res = importer.importFromUri(uri) { hash -> bookDao.findByContentHash(hash) }) {
        is ImportResult.Success -> {
          val id = bookDao.insertBook(res.book)
          imported.add(res.book.copy(id = id))
        }
        is ImportResult.Duplicate -> duplicates.add(res.existingBook)
        is ImportResult.Error -> errors.add(res.message)
      }
    }

    if (imported.isNotEmpty()) {
      LettorContinueWidgetProvider.requestRefresh(appContext)
    }
    return BatchImportSummary(imported = imported, duplicates = duplicates, errors = errors)
  }

  suspend fun updateBookMetadata(
    bookId: Long,
    title: String,
    author: String,
    genre: String,
    description: String,
    newCoverUri: Uri?,
    existingCoverPath: String?
  ) {
    val coverPath = if (newCoverUri != null) {
      importer.saveCustomCoverFromUri(newCoverUri) ?: existingCoverPath
    } else {
      existingCoverPath
    }
    bookDao.updateMetadata(
      bookId = bookId,
      title = title.trim().ifBlank { "Sin título" },
      author = author.trim().ifBlank { "Autor desconocido" },
      genre = genre.trim().ifBlank { "General" },
      description = description.trim(),
      coverPath = coverPath
    )
    LettorContinueWidgetProvider.requestRefresh(appContext)
  }

  suspend fun updateProgress(
    bookId: Long,
    unit: Int,
    subOffset: Int,
    progress: Float,
    isFinished: Boolean
  ) {
    bookDao.updateReadingProgress(
      bookId = bookId,
      unit = unit,
      subOffset = subOffset,
      progress = progress.coerceIn(0f, 1f),
      isFinished = isFinished,
      timestamp = System.currentTimeMillis()
    )
    if (isFinished) {
      ResumeReadingScheduler.cancelForBook(appContext, bookId)
    }
    LettorContinueWidgetProvider.requestRefresh(appContext)
  }

  suspend fun toggleFavorite(bookId: Long, isFavorite: Boolean) {
    bookDao.updateFavorite(bookId, isFavorite)
  }

  suspend fun setFinished(bookId: Long, isFinished: Boolean) {
    bookDao.updateFinished(bookId, isFinished)
    if (isFinished) {
      ResumeReadingScheduler.cancelForBook(appContext, bookId)
    }
    LettorContinueWidgetProvider.requestRefresh(appContext)
  }

  suspend fun addReadingTime(bookId: Long, deltaSeconds: Long) {
    if (deltaSeconds > 0) {
      bookDao.addReadingTime(bookId, deltaSeconds)
      preferencesRepository.addTodayReadingSeconds(deltaSeconds)
    }
  }

  suspend fun deleteBook(book: BookEntity) {
    ResumeReadingScheduler.cancelForBook(appContext, book.id)
    importer.deleteFilesForBook(book)
    bookDao.deleteBookById(book.id)
    LettorContinueWidgetProvider.requestRefresh(appContext)
  }

  suspend fun addBookmark(bookmark: BookmarkEntity): Long = bookmarkDao.insertBookmark(bookmark)

  suspend fun removeBookmark(bookmarkId: Long) = bookmarkDao.deleteBookmarkById(bookmarkId)

  suspend fun addHighlight(highlight: HighlightEntity): Long = highlightDao.insertHighlight(highlight)

  suspend fun updateHighlight(id: Long, note: String, color: HighlightColor) =
    highlightDao.updateHighlight(id, note, color)

  suspend fun removeHighlight(id: Long) = highlightDao.deleteHighlightById(id)

  suspend fun saveWordToVocabulary(
    rawWord: String,
    bookId: Long?,
    bookTitle: String,
    contextSnippet: String
  ): Boolean {
    val norm = LocalDictionaryAndTranslator.normalize(rawWord)
    if (norm.length < 2) return false
    if (vocabularyDao.findByNormalizedWord(norm) != null) return false
    val entry = LocalDictionaryAndTranslator.lookupWord(rawWord)
    vocabularyDao.insertWord(
      VocabularyEntity(
        word = entry.word.replaceFirstChar { it.titlecase() },
        normalizedWord = norm,
        partOfSpeech = entry.partOfSpeech,
        definition = entry.definition,
        translation = entry.englishTranslation,
        bookId = bookId,
        bookTitle = bookTitle,
        contextSnippet = contextSnippet.take(180)
      )
    )
    return true
  }

  suspend fun toggleVocabularyMastered(id: Long, mastered: Boolean) =
    vocabularyDao.setMastered(id, mastered)

  suspend fun deleteVocabularyWord(id: Long) = vocabularyDao.deleteWordById(id)

  suspend fun exportBackup(destinationUri: Uri): Result<Int> = backupManager.exportBackupToUri(destinationUri)

  suspend fun importBackup(sourceUri: Uri): Result<BackupRestoreSummary> {
    val result = backupManager.importBackupFromUri(sourceUri)
    if (result.isSuccess) {
      LettorContinueWidgetProvider.requestRefresh(appContext)
    }
    return result
  }
}
