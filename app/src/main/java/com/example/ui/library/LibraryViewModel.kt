package com.example.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.BookEntity
import com.example.data.local.BookFormat
import com.example.data.preferences.ReaderPreferences
import com.example.data.preferences.ReaderPreferencesRepository
import com.example.data.repository.LibraryRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class LibraryFilter(val label: String) {
  ALL("Todos"),
  EPUB("EPUB"),
  PDF("PDF"),
  READING("Leyendo"),
  FAVORITES("Favoritos"),
  FINISHED("Terminados")
}

enum class LibrarySort(val label: String) {
  RECENT("Recientes"),
  TITLE("Título"),
  AUTHOR("Autor"),
  GENRE("Género"),
  PROGRESS("Progreso")
}

data class LibraryUiState(
  val allBooks: List<BookEntity> = emptyList(),
  val filteredBooks: List<BookEntity> = emptyList(),
  val continueReadingBook: BookEntity? = null,
  val crashRecoveryBook: BookEntity? = null,
  val availableAuthors: List<String> = emptyList(),
  val availableGenres: List<String> = emptyList(),
  val selectedAuthor: String? = null,
  val selectedGenre: String? = null,
  val filter: LibraryFilter = LibraryFilter.ALL,
  val sort: LibrarySort = LibrarySort.RECENT,
  val searchQuery: String = "",
  val isGridView: Boolean = true,
  val isImporting: Boolean = false,
  val isLoading: Boolean = true,
  val preferences: ReaderPreferences = ReaderPreferences()
)

sealed interface LibraryEvent {
  data class ShowMessage(val message: String) : LibraryEvent
  data class ImportCompletedSuccessfully(val summaryMessage: String) : LibraryEvent
}

class LibraryViewModel(
  private val repository: LibraryRepository,
  private val preferencesRepository: ReaderPreferencesRepository
) : ViewModel() {

  private val filterFlow = MutableStateFlow(LibraryFilter.ALL)
  private val sortFlow = MutableStateFlow(LibrarySort.RECENT)
  private val searchFlow = MutableStateFlow("")
  private val authorFilterFlow = MutableStateFlow<String?>(null)
  private val genreFilterFlow = MutableStateFlow<String?>(null)
  private val gridViewFlow = MutableStateFlow(true)
  private val importingFlow = MutableStateFlow(false)
  private val seededFlow = MutableStateFlow(false)

  private val _events = MutableSharedFlow<LibraryEvent>()
  val events = _events.asSharedFlow()

  val uiState: StateFlow<LibraryUiState> = combine(
    repository.allBooks,
    preferencesRepository.preferencesFlow,
    combine(filterFlow, sortFlow, searchFlow) { f, s, q -> Triple(f, s, q) },
    combine(authorFilterFlow, genreFilterFlow) { a, g -> a to g },
    combine(gridViewFlow, importingFlow, seededFlow) { grid, imp, seeded -> Triple(grid, imp, seeded) }
  ) { books, prefs, fsq, ag, flags ->
    val (filter, sort, query) = fsq
    val (selectedAuthor, selectedGenre) = ag
    val (isGrid, isImporting, isSeeded) = flags
    val trimmedQuery = query.trim()

    val authors = books.map { it.author.trim() }.filter { it.isNotBlank() }.distinct().sorted()
    val genres = books.map { it.genre.trim() }.filter { it.isNotBlank() }.distinct().sorted()

    val filtered = books
      .filter { book ->
        val matchesFilter = when (filter) {
          LibraryFilter.ALL -> true
          LibraryFilter.EPUB -> book.format == BookFormat.EPUB
          LibraryFilter.PDF -> book.format == BookFormat.PDF
          LibraryFilter.READING -> book.progressFraction > 0f && !book.isFinished
          LibraryFilter.FAVORITES -> book.isFavorite
          LibraryFilter.FINISHED -> book.isFinished
        }
        val matchesAuthor = selectedAuthor == null || book.author.equals(selectedAuthor, ignoreCase = true)
        val matchesGenre = selectedGenre == null || book.genre.equals(selectedGenre, ignoreCase = true)
        val matchesQuery = trimmedQuery.isEmpty() ||
          book.title.contains(trimmedQuery, ignoreCase = true) ||
          book.author.contains(trimmedQuery, ignoreCase = true) ||
          book.genre.contains(trimmedQuery, ignoreCase = true)
        matchesFilter && matchesAuthor && matchesGenre && matchesQuery
      }
      .let { list ->
        when (sort) {
          LibrarySort.RECENT -> list.sortedByDescending { maxOf(it.lastOpenedAt, it.addedAt) }
          LibrarySort.TITLE -> list.sortedBy { it.title.lowercase() }
          LibrarySort.AUTHOR -> list.sortedBy { it.author.lowercase() }
          LibrarySort.GENRE -> list.sortedBy { it.genre.lowercase() }
          LibrarySort.PROGRESS -> list.sortedByDescending { it.progressFraction }
        }
      }

    val continueBook = books
      .filter { !it.isFinished }
      .maxByOrNull { maxOf(it.lastOpenedAt, it.addedAt) }
      ?: books.firstOrNull()

    val recoveryBook = if (prefs.lastActiveReadingBookId > 0L) {
      books.firstOrNull { it.id == prefs.lastActiveReadingBookId }
    } else {
      null
    }

    LibraryUiState(
      allBooks = books,
      filteredBooks = filtered,
      continueReadingBook = continueBook,
      crashRecoveryBook = recoveryBook,
      availableAuthors = authors,
      availableGenres = genres,
      selectedAuthor = selectedAuthor,
      selectedGenre = selectedGenre,
      filter = filter,
      sort = sort,
      searchQuery = query,
      isGridView = isGrid,
      isImporting = isImporting,
      isLoading = !isSeeded,
      preferences = prefs
    )
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5000),
    initialValue = LibraryUiState()
  )

  init {
    viewModelScope.launch {
      repository.ensureSampleBooksSeeded()
      seededFlow.value = true
    }
  }

  fun setFilter(filter: LibraryFilter) {
    filterFlow.value = filter
  }

  fun setSort(sort: LibrarySort) {
    sortFlow.value = sort
  }

  fun setSearchQuery(query: String) {
    searchFlow.value = query
  }

  fun selectAuthor(author: String?) {
    authorFilterFlow.value = if (authorFilterFlow.value == author) null else author
  }

  fun selectGenre(genre: String?) {
    genreFilterFlow.value = if (genreFilterFlow.value == genre) null else genre
  }

  fun clearAllFilters() {
    filterFlow.value = LibraryFilter.ALL
    authorFilterFlow.value = null
    genreFilterFlow.value = null
    searchFlow.value = ""
  }

  fun toggleGridView() {
    gridViewFlow.value = !gridViewFlow.value
  }

  fun setDailyGoalMinutes(minutes: Int) {
    viewModelScope.launch {
      preferencesRepository.setDailyGoalMinutes(minutes)
    }
  }

  fun dismissCrashRecoveryBanner() {
    viewModelScope.launch {
      preferencesRepository.setActiveReadingBookId(-1L)
    }
  }

  fun importMultipleBooks(uris: List<Uri>) {
    if (uris.isEmpty()) return // User cancelled: never show ad
    viewModelScope.launch {
      importingFlow.value = true
      val summary = repository.importMultipleBooks(uris)
      importingFlow.value = false

      val parts = mutableListOf<String>()
      if (summary.imported.isNotEmpty()) {
        val label = if (summary.imported.size == 1) {
          "«${summary.imported.first().title}» añadido"
        } else {
          "${summary.imported.size} libros importados"
        }
        parts.add(label)
      }
      if (summary.duplicates.isNotEmpty()) {
        parts.add("${summary.duplicates.size} duplicado(s) omitido(s)")
      }
      if (summary.errors.isNotEmpty()) {
        parts.add(summary.errors.first())
      }
      val msg = parts.joinToString(" · ").ifBlank { "No se importaron archivos." }

      if (summary.imported.isNotEmpty()) {
        // Only trigger post-import AdMob check AFTER import succeeds
        _events.emit(LibraryEvent.ImportCompletedSuccessfully(msg))
      } else {
        _events.emit(LibraryEvent.ShowMessage(msg))
      }
    }
  }

  fun updateBookMetadata(
    book: BookEntity,
    title: String,
    author: String,
    genre: String,
    description: String,
    newCoverUri: Uri?
  ) {
    viewModelScope.launch {
      repository.updateBookMetadata(
        bookId = book.id,
        title = title,
        author = author,
        genre = genre,
        description = description,
        newCoverUri = newCoverUri,
        existingCoverPath = book.coverPath
      )
      _events.emit(LibraryEvent.ShowMessage("Metadatos actualizados"))
    }
  }

  fun toggleFavorite(book: BookEntity) {
    viewModelScope.launch {
      repository.toggleFavorite(book.id, !book.isFavorite)
    }
  }

  fun toggleFinished(book: BookEntity) {
    viewModelScope.launch {
      repository.setFinished(book.id, !book.isFinished)
    }
  }

  fun deleteBook(book: BookEntity) {
    viewModelScope.launch {
      repository.deleteBook(book)
      _events.emit(LibraryEvent.ShowMessage("«${book.title}» eliminado de tu biblioteca"))
    }
  }

  fun markNotificationRationaleShown() {
    viewModelScope.launch {
      preferencesRepository.markNotificationRationaleShown()
    }
  }

  class Factory(
    private val repository: LibraryRepository,
    private val preferencesRepository: ReaderPreferencesRepository
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return LibraryViewModel(repository, preferencesRepository) as T
    }
  }
}
