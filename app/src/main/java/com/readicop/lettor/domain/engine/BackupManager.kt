package com.readicop.lettor.domain.engine

import android.content.Context
import android.net.Uri
import com.readicop.lettor.data.local.BookDao
import com.readicop.lettor.data.local.BookEntity
import com.readicop.lettor.data.local.BookFormat
import com.readicop.lettor.data.local.BookmarkDao
import com.readicop.lettor.data.local.BookmarkEntity
import com.readicop.lettor.data.local.HighlightColor
import com.readicop.lettor.data.local.HighlightDao
import com.readicop.lettor.data.local.HighlightEntity
import com.readicop.lettor.data.local.VocabularyDao
import com.readicop.lettor.data.local.VocabularyEntity
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class BackupBookRecord(
  val originalId: Long,
  val title: String,
  val author: String,
  val genre: String,
  val description: String,
  val format: String,
  val zipBookEntry: String,
  val zipCoverEntry: String?,
  val contentHash: String,
  val totalUnits: Int,
  val totalWordCount: Int,
  val currentUnit: Int,
  val currentSubOffset: Int,
  val progressFraction: Float,
  val isFavorite: Boolean,
  val isFinished: Boolean,
  val fileSizeBytes: Long,
  val readingTimeSeconds: Long
)

@Serializable
data class BackupBookmarkRecord(
  val originalBookId: Long,
  val unitIndex: Int,
  val subOffset: Int,
  val label: String,
  val excerpt: String,
  val note: String,
  val createdAt: Long
)

@Serializable
data class BackupHighlightRecord(
  val originalBookId: Long,
  val unitIndex: Int,
  val blockIndex: Int,
  val excerpt: String,
  val stickyNote: String,
  val color: String,
  val isStickyNoteOnly: Boolean,
  val createdAt: Long
)

@Serializable
data class BackupVocabularyRecord(
  val word: String,
  val normalizedWord: String,
  val partOfSpeech: String,
  val definition: String,
  val translation: String,
  val bookTitle: String,
  val contextSnippet: String,
  val isMastered: Boolean,
  val createdAt: Long
)

@Serializable
data class LettorBackupManifest(
  val version: Int = 1,
  val exportedAt: Long = System.currentTimeMillis(),
  val books: List<BackupBookRecord>,
  val bookmarks: List<BackupBookmarkRecord>,
  val highlights: List<BackupHighlightRecord>,
  val vocabulary: List<BackupVocabularyRecord>
)

data class BackupRestoreSummary(
  val booksRestored: Int,
  val booksSkippedDuplicates: Int,
  val annotationsRestored: Int,
  val wordsRestored: Int
)

class BackupManager(
  private val context: Context,
  private val bookDao: BookDao,
  private val bookmarkDao: BookmarkDao,
  private val highlightDao: HighlightDao,
  private val vocabularyDao: VocabularyDao
) {
  private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

  suspend fun exportBackupToUri(destinationUri: Uri): Result<Int> = withContext(Dispatchers.IO) {
    runCatching {
      val books = bookDao.getAllBooksOnce()
      val bookmarks = bookmarkDao.getAllBookmarksOnce()
      val highlights = highlightDao.getAllHighlightsOnce()
      val vocabulary = vocabularyDao.getAllVocabularyOnce()

      val backupBooks = mutableListOf<BackupBookRecord>()
      val filesToPack = mutableListOf<Pair<String, File>>()

      books.forEachIndexed { idx, book ->
        val bookFile = File(book.filePath)
        if (bookFile.exists()) {
          val ext = if (book.format == BookFormat.EPUB) "epub" else "pdf"
          val bookEntry = "books/book_${idx}_${book.id}.$ext"
          filesToPack.add(bookEntry to bookFile)

          var coverEntry: String? = null
          book.coverPath?.let { path ->
            val coverFile = File(path)
            if (coverFile.exists()) {
              coverEntry = "covers/cover_${idx}_${book.id}.jpg"
              filesToPack.add(coverEntry!! to coverFile)
            }
          }

          backupBooks.add(
            BackupBookRecord(
              originalId = book.id,
              title = book.title,
              author = book.author,
              genre = book.genre,
              description = book.description,
              format = book.format.name,
              zipBookEntry = bookEntry,
              zipCoverEntry = coverEntry,
              contentHash = book.contentHash,
              totalUnits = book.totalUnits,
              totalWordCount = book.totalWordCount,
              currentUnit = book.currentUnit,
              currentSubOffset = book.currentSubOffset,
              progressFraction = book.progressFraction,
              isFavorite = book.isFavorite,
              isFinished = book.isFinished,
              fileSizeBytes = book.fileSizeBytes,
              readingTimeSeconds = book.readingTimeSeconds
            )
          )
        }
      }

      val manifest = LettorBackupManifest(
        books = backupBooks,
        bookmarks = bookmarks.map {
          BackupBookmarkRecord(it.bookId, it.unitIndex, it.subOffset, it.label, it.excerpt, it.note, it.createdAt)
        },
        highlights = highlights.map {
          BackupHighlightRecord(it.bookId, it.unitIndex, it.blockIndex, it.excerpt, it.stickyNote, it.color.name, it.isStickyNoteOnly, it.createdAt)
        },
        vocabulary = vocabulary.map {
          BackupVocabularyRecord(it.word, it.normalizedWord, it.partOfSpeech, it.definition, it.translation, it.bookTitle, it.contextSnippet, it.isMastered, it.createdAt)
        }
      )

      context.contentResolver.openOutputStream(destinationUri)?.use { out ->
        ZipOutputStream(out).use { zos ->
          zos.putNextEntry(ZipEntry("manifest.json"))
          zos.write(json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
          zos.closeEntry()

          for ((entryPath, file) in filesToPack) {
            zos.putNextEntry(ZipEntry(entryPath))
            file.inputStream().use { input -> input.copyTo(zos) }
            zos.closeEntry()
          }
        }
      } ?: error("No se pudo escribir el archivo de copia de seguridad.")

      backupBooks.size
    }
  }

  suspend fun importBackupFromUri(sourceUri: Uri): Result<BackupRestoreSummary> = withContext(Dispatchers.IO) {
    runCatching {
      val stagingDir = File(context.cacheDir, "backup_restore_${UUID.randomUUID()}").apply { mkdirs() }
      try {
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
          ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
              if (!entry.isDirectory && !entry.name.contains("..")) {
                val dest = File(stagingDir, entry.name)
                dest.parentFile?.mkdirs()
                dest.outputStream().use { out -> zis.copyTo(out) }
              }
              zis.closeEntry()
              entry = zis.nextEntry
            }
          }
        } ?: error("No se pudo abrir el archivo de respaldo.")

        val manifestFile = File(stagingDir, "manifest.json")
        if (!manifestFile.exists()) error("El archivo seleccionado no es una copia de seguridad válida de Lettor.")

        val manifest = json.decodeFromString<LettorBackupManifest>(manifestFile.readText(Charsets.UTF_8))
        val booksDir = File(context.filesDir, "books").apply { mkdirs() }
        val coversDir = File(context.filesDir, "covers").apply { mkdirs() }

        val idMap = mutableMapOf<Long, Long>()
        var booksRestored = 0
        var skippedDuplicates = 0

        for (rec in manifest.books) {
          val existing = if (rec.contentHash.isNotBlank()) bookDao.findByContentHash(rec.contentHash) else null
          if (existing != null) {
            idMap[rec.originalId] = existing.id
            skippedDuplicates++
            continue
          }

          val stagedBook = File(stagingDir, rec.zipBookEntry)
          if (!stagedBook.exists()) continue

          val format = runCatching { BookFormat.valueOf(rec.format) }.getOrDefault(BookFormat.EPUB)
          val ext = if (format == BookFormat.EPUB) "epub" else "pdf"
          val targetBook = File(booksDir, "${UUID.randomUUID()}.$ext")
          stagedBook.copyTo(targetBook, overwrite = true)

          var targetCoverPath: String? = null
          rec.zipCoverEntry?.let { coverEntry ->
            val stagedCover = File(stagingDir, coverEntry)
            if (stagedCover.exists()) {
              val targetCover = File(coversDir, "${targetBook.nameWithoutExtension}.jpg")
              stagedCover.copyTo(targetCover, overwrite = true)
              targetCoverPath = targetCover.absolutePath
            }
          }

          val newId = bookDao.insertBook(
            BookEntity(
              title = rec.title,
              author = rec.author,
              genre = rec.genre,
              description = rec.description,
              format = format,
              filePath = targetBook.absolutePath,
              coverPath = targetCoverPath,
              contentHash = rec.contentHash,
              totalUnits = rec.totalUnits,
              totalWordCount = rec.totalWordCount,
              currentUnit = rec.currentUnit,
              currentSubOffset = rec.currentSubOffset,
              progressFraction = rec.progressFraction,
              isFavorite = rec.isFavorite,
              isFinished = rec.isFinished,
              fileSizeBytes = targetBook.length(),
              readingTimeSeconds = rec.readingTimeSeconds
            )
          )
          idMap[rec.originalId] = newId
          booksRestored++
        }

        var annotationsRestored = 0
        for (bm in manifest.bookmarks) {
          val mappedBookId = idMap[bm.originalBookId] ?: continue
          bookmarkDao.insertBookmark(
            BookmarkEntity(
              bookId = mappedBookId,
              unitIndex = bm.unitIndex,
              subOffset = bm.subOffset,
              label = bm.label,
              excerpt = bm.excerpt,
              note = bm.note,
              createdAt = bm.createdAt
            )
          )
          annotationsRestored++
        }

        for (hl in manifest.highlights) {
          val mappedBookId = idMap[hl.originalBookId] ?: continue
          highlightDao.insertHighlight(
            HighlightEntity(
              bookId = mappedBookId,
              unitIndex = hl.unitIndex,
              blockIndex = hl.blockIndex,
              excerpt = hl.excerpt,
              stickyNote = hl.stickyNote,
              color = runCatching { HighlightColor.valueOf(hl.color) }.getOrDefault(HighlightColor.AMBER),
              isStickyNoteOnly = hl.isStickyNoteOnly,
              createdAt = hl.createdAt
            )
          )
          annotationsRestored++
        }

        var wordsRestored = 0
        for (v in manifest.vocabulary) {
          if (vocabularyDao.findByNormalizedWord(v.normalizedWord) == null) {
            vocabularyDao.insertWord(
              VocabularyEntity(
                word = v.word,
                normalizedWord = v.normalizedWord,
                partOfSpeech = v.partOfSpeech,
                definition = v.definition,
                translation = v.translation,
                bookTitle = v.bookTitle,
                contextSnippet = v.contextSnippet,
                isMastered = v.isMastered,
                createdAt = v.createdAt
              )
            )
            wordsRestored++
          }
        }

        BackupRestoreSummary(
          booksRestored = booksRestored,
          booksSkippedDuplicates = skippedDuplicates,
          annotationsRestored = annotationsRestored,
          wordsRestored = wordsRestored
        )
      } finally {
        stagingDir.deleteRecursively()
      }
    }
  }
}
