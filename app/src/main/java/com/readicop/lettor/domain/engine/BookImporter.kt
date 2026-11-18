package com.readicop.lettor.domain.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.readicop.lettor.R
import com.readicop.lettor.data.local.BookEntity
import com.readicop.lettor.data.local.BookFormat
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface ImportResult {
  data class Success(val book: BookEntity) : ImportResult
  data class Duplicate(val existingBook: BookEntity) : ImportResult
  data class Error(val message: String) : ImportResult
}

data class BatchImportSummary(
  val imported: List<BookEntity>,
  val duplicates: List<BookEntity>,
  val errors: List<String>
)

class BookImporter(private val context: Context) {

  private val booksDir: File
    get() = File(context.filesDir, "books").apply { mkdirs() }

  private val coversDir: File
    get() = File(context.filesDir, "covers").apply { mkdirs() }

  suspend fun importFromUri(
    uri: Uri,
    duplicateLookup: suspend (String) -> BookEntity?
  ): ImportResult = withContext(Dispatchers.IO) {
    runCatching {
      val displayName = resolveDisplayName(uri) ?: "libro_${System.currentTimeMillis()}"
      val tempFile = File(booksDir, "tmp_${UUID.randomUUID()}")

      context.contentResolver.openInputStream(uri)?.use { input ->
        tempFile.outputStream().use { output -> input.copyTo(output) }
      } ?: return@withContext ImportResult.Error("No se pudo abrir «$displayName».")

      if (tempFile.length() < 64L) {
        tempFile.delete()
        return@withContext ImportResult.Error("El archivo «$displayName» está vacío o dañado.")
      }

      val hash = computeSha256(tempFile)
      val existing = duplicateLookup(hash)
      if (existing != null) {
        tempFile.delete()
        return@withContext ImportResult.Duplicate(existing)
      }

      val detectedFormat = detectFormat(tempFile, displayName)
      if (detectedFormat == null) {
        tempFile.delete()
        return@withContext ImportResult.Error("«$displayName» no es un archivo EPUB o PDF válido.")
      }

      // Deep integrity validation against corrupted files
      val integrityError = validateIntegrity(tempFile, detectedFormat)
      if (integrityError != null) {
        tempFile.delete()
        return@withContext ImportResult.Error("«$displayName» parece estar dañado: $integrityError")
      }

      val ext = if (detectedFormat == BookFormat.EPUB) "epub" else "pdf"
      val finalFile = File(booksDir, "${UUID.randomUUID()}.$ext")
      if (!tempFile.renameTo(finalFile)) {
        tempFile.copyTo(finalFile, overwrite = true)
        tempFile.delete()
      }

      val coverFile = File(coversDir, "${finalFile.nameWithoutExtension}.jpg")
      val bookEntity = buildBookEntity(
        file = finalFile,
        format = detectedFormat,
        contentHash = hash,
        fallbackTitle = displayName.substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim(),
        coverCandidate = coverFile
      )
      ImportResult.Success(bookEntity)
    }.getOrElse { err ->
      ImportResult.Error(err.localizedMessage ?: "Error inesperado al importar el libro.")
    }
  }

  suspend fun saveCustomCoverFromUri(uri: Uri): String? = withContext(Dispatchers.IO) {
    runCatching {
      val coverFile = File(coversDir, "custom_${UUID.randomUUID()}.jpg")
      context.contentResolver.openInputStream(uri)?.use { input ->
        coverFile.outputStream().use { out -> input.copyTo(out) }
      } ?: return@withContext null
      if (coverFile.exists() && coverFile.length() > 0L) coverFile.absolutePath else null
    }.getOrNull()
  }

  suspend fun createBundledSamples(): List<BookEntity> = withContext(Dispatchers.IO) {
    val samples = mutableListOf<BookEntity>()

    // 1. Sample EPUB: Don Quijote de la Mancha (Selección)
    runCatching {
      val epubFile = File(booksDir, "sample_quijote.epub")
      context.assets.open("samples/don_quijote_seleccion.epub").use { input ->
        epubFile.outputStream().use { out -> input.copyTo(out) }
      }
      val hash = computeSha256(epubFile)
      val coverFile = File(coversDir, "sample_quijote.jpg")
      val meta = EpubParser.inspectMetadataAndExtractCover(epubFile, coverFile)
      samples.add(
        BookEntity(
          title = meta.title,
          author = meta.author,
          genre = "Clásicos",
          description = meta.description,
          format = BookFormat.EPUB,
          filePath = epubFile.absolutePath,
          coverPath = if (coverFile.exists() && coverFile.length() > 0) coverFile.absolutePath else null,
          contentHash = hash,
          totalUnits = meta.chapterCount,
          totalWordCount = 1350,
          currentUnit = 0,
          currentSubOffset = 0,
          progressFraction = 0.08f,
          isFavorite = true,
          fileSizeBytes = epubFile.length(),
          addedAt = System.currentTimeMillis() - 3600_000L,
          lastOpenedAt = System.currentTimeMillis() - 1800_000L
        )
      )
    }

    // 2. Sample PDF: Rimas y Leyendas — Gustavo Adolfo Bécquer
    runCatching {
      val pdfFile = File(booksDir, "sample_becquer.pdf")
      context.assets.open("samples/rimas_y_leyendas_becquer.pdf").use { input ->
        pdfFile.outputStream().use { out -> input.copyTo(out) }
      }
      val hash = computeSha256(pdfFile)
      val pageCount = PdfBookSession.inspectAndExtractCover(pdfFile, null)
      val coverFile = File(coversDir, "sample_becquer.jpg")
      context.resources.openRawResource(R.drawable.img_cover_becquer).use { input ->
        coverFile.outputStream().use { out -> input.copyTo(out) }
      }
      samples.add(
        BookEntity(
          title = "Rimas y Leyendas",
          author = "Gustavo Adolfo Bécquer",
          genre = "Poesía",
          description = "Selección poética y narrativa romántica en edición digital Lettor.",
          format = BookFormat.PDF,
          filePath = pdfFile.absolutePath,
          coverPath = if (coverFile.exists() && coverFile.length() > 0) coverFile.absolutePath else null,
          contentHash = hash,
          totalUnits = pageCount,
          totalWordCount = pageCount * 220,
          currentUnit = 0,
          currentSubOffset = 0,
          progressFraction = 0f,
          isFavorite = false,
          fileSizeBytes = pdfFile.length(),
          addedAt = System.currentTimeMillis() - 7200_000L,
          lastOpenedAt = 0L
        )
      )
    }

    samples
  }

  fun deleteFilesForBook(book: BookEntity) {
    runCatching { File(book.filePath).delete() }
    book.coverPath?.let { path -> runCatching { File(path).delete() } }
  }

  fun computeSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8192)
    file.inputStream().use { input ->
      var read = input.read(buffer)
      while (read > 0) {
        digest.update(buffer, 0, read)
        read = input.read(buffer)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  private fun validateIntegrity(file: File, format: BookFormat): String? {
    return when (format) {
      BookFormat.EPUB -> runCatching {
        java.util.zip.ZipFile(file).use { zip ->
          val hasOpf = zip.entries().asSequence().any { it.name.endsWith(".opf", ignoreCase = true) }
          if (!hasOpf) "Falta el índice OPF interno." else null
        }
      }.getOrElse { "Archivo ZIP/EPUB corrupto." }
      BookFormat.PDF -> runCatching {
        val count = PdfBookSession.inspectAndExtractCover(file, null)
        if (count <= 0) "El PDF no tiene páginas válidas." else null
      }.getOrElse { "Estructura PDF ilegible o protegida." }
    }
  }

  private fun buildBookEntity(
    file: File,
    format: BookFormat,
    contentHash: String,
    fallbackTitle: String,
    coverCandidate: File
  ): BookEntity {
    return when (format) {
      BookFormat.EPUB -> {
        val meta = EpubParser.inspectMetadataAndExtractCover(file, coverCandidate)
        val hasCover = coverCandidate.exists() && coverCandidate.length() > 0L
        if (!hasCover) coverCandidate.delete()
        BookEntity(
          title = meta.title.ifBlank { fallbackTitle.ifBlank { "Libro EPUB" } },
          author = meta.author.ifBlank { "Autor desconocido" },
          genre = meta.genre.ifBlank { "General" },
          description = meta.description,
          format = BookFormat.EPUB,
          filePath = file.absolutePath,
          coverPath = if (hasCover) coverCandidate.absolutePath else null,
          contentHash = contentHash,
          totalUnits = meta.chapterCount,
          totalWordCount = (meta.chapterCount * 1400).coerceAtLeast(300),
          fileSizeBytes = file.length(),
          addedAt = System.currentTimeMillis(),
          lastOpenedAt = System.currentTimeMillis()
        )
      }
      BookFormat.PDF -> {
        val pageCount = PdfBookSession.inspectAndExtractCover(file, coverCandidate)
        val hasCover = coverCandidate.exists() && coverCandidate.length() > 0L
        if (!hasCover) coverCandidate.delete()
        BookEntity(
          title = fallbackTitle.ifBlank { "Documento PDF" },
          author = "Documento PDF",
          genre = "Documentos",
          description = "Documento PDF de $pageCount páginas importado desde el dispositivo.",
          format = BookFormat.PDF,
          filePath = file.absolutePath,
          coverPath = if (hasCover) coverCandidate.absolutePath else null,
          contentHash = contentHash,
          totalUnits = pageCount,
          totalWordCount = pageCount * 280,
          fileSizeBytes = file.length(),
          addedAt = System.currentTimeMillis(),
          lastOpenedAt = System.currentTimeMillis()
        )
      }
    }
  }

  private fun resolveDisplayName(uri: Uri): String? {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
      ?.use { cursor ->
        if (cursor.moveToFirst()) {
          val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (idx >= 0) return cursor.getString(idx)
        }
      }
    return uri.lastPathSegment
  }

  private fun detectFormat(file: File, displayName: String): BookFormat? {
    val header = ByteArray(8)
    val read = runCatching {
      file.inputStream().use { it.read(header) }
    }.getOrDefault(0)

    if (read >= 5) {
      val ascii = String(header, 0, 5, Charsets.US_ASCII)
      if (ascii == "%PDF-") return BookFormat.PDF
    }
    if (read >= 2 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()) {
      val isEpub = runCatching {
        java.util.zip.ZipFile(file).use { zip ->
          zip.getEntry("META-INF/container.xml") != null ||
            zip.entries().asSequence().any { it.name.endsWith(".opf", ignoreCase = true) }
        }
      }.getOrDefault(false)
      if (isEpub) return BookFormat.EPUB
    }
    return when {
      displayName.endsWith(".pdf", ignoreCase = true) -> BookFormat.PDF
      displayName.endsWith(".epub", ignoreCase = true) -> BookFormat.EPUB
      else -> null
    }
  }
}
