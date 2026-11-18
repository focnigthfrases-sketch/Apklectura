package com.readicop.lettor.ui.bookmarks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.readicop.lettor.data.local.BookEntity
import com.readicop.lettor.data.local.BookmarkWithBook
import com.readicop.lettor.data.local.HighlightWithBook
import com.readicop.lettor.data.local.VocabularyEntity
import com.readicop.lettor.data.preferences.ReaderPreferences
import com.readicop.lettor.data.preferences.ReaderPreferencesRepository
import com.readicop.lettor.data.repository.LibraryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ReadingStats(
  val totalBooks: Int = 0,
  val finishedBooks: Int = 0,
  val totalReadingMinutes: Long = 0L,
  val todayMinutes: Int = 0,
  val dailyGoalMinutes: Int = 20,
  val streakDays: Int = 0,
  val estimatedWpm: Int = 210,
  val totalAnnotations: Int = 0,
  val vocabularyCount: Int = 0,
  val topGenres: List<Pair<String, Int>> = emptyList(),
  val topAuthors: List<Pair<String, Int>> = emptyList()
)

data class BookmarksUiState(
  val bookmarks: List<BookmarkWithBook> = emptyList(),
  val highlights: List<HighlightWithBook> = emptyList(),
  val vocabulary: List<VocabularyEntity> = emptyList(),
  val books: List<BookEntity> = emptyList(),
  val stats: ReadingStats = ReadingStats(),
  val preferences: ReaderPreferences = ReaderPreferences()
)

class BookmarksViewModel(
  private val repository: LibraryRepository,
  private val preferencesRepository: ReaderPreferencesRepository
) : ViewModel() {

  val uiState: StateFlow<BookmarksUiState> = combine(
    repository.allBookmarksWithBooks,
    repository.allHighlightsWithBooks,
    repository.allVocabulary,
    repository.allBooks,
    preferencesRepository.preferencesFlow
  ) { bookmarks, highlights, vocab, books, prefs ->
    val genreCounts = books.groupingBy { it.genre.ifBlank { "General" } }
      .eachCount()
      .entries
      .sortedByDescending { it.value }
      .take(5)
      .map { it.key to it.value }

    val authorCounts = books.groupingBy { it.author.ifBlank { "Autor desconocido" } }
      .eachCount()
      .entries
      .sortedByDescending { it.value }
      .take(5)
      .map { it.key to it.value }

    val stats = ReadingStats(
      totalBooks = books.size,
      finishedBooks = books.count { it.isFinished },
      totalReadingMinutes = books.sumOf { it.readingTimeSeconds } / 60L,
      todayMinutes = (prefs.todayReadingSeconds / 60L).toInt(),
      dailyGoalMinutes = prefs.dailyGoalMinutes,
      streakDays = prefs.readingStreakDays,
      estimatedWpm = prefs.estimatedWpm,
      totalAnnotations = bookmarks.size + highlights.size,
      vocabularyCount = vocab.size,
      topGenres = genreCounts,
      topAuthors = authorCounts
    )

    BookmarksUiState(
      bookmarks = bookmarks,
      highlights = highlights,
      vocabulary = vocab,
      books = books,
      stats = stats,
      preferences = prefs
    )
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5000),
    initialValue = BookmarksUiState()
  )

  fun deleteBookmark(bookmarkId: Long) {
    viewModelScope.launch { repository.removeBookmark(bookmarkId) }
  }

  fun deleteHighlight(highlightId: Long) {
    viewModelScope.launch { repository.removeHighlight(highlightId) }
  }

  fun toggleVocabularyMastered(word: VocabularyEntity) {
    viewModelScope.launch { repository.toggleVocabularyMastered(word.id, !word.isMastered) }
  }

  fun deleteVocabularyWord(id: Long) {
    viewModelScope.launch { repository.deleteVocabularyWord(id) }
  }

  fun addManualVocabularyWord(word: String) {
    viewModelScope.launch {
      repository.saveWordToVocabulary(
        rawWord = word,
        bookId = null,
        bookTitle = "Vocabulario personal",
        contextSnippet = ""
      )
    }
  }

  class Factory(
    private val repository: LibraryRepository,
    private val preferencesRepository: ReaderPreferencesRepository
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return BookmarksViewModel(repository, preferencesRepository) as T
    }
  }
}
