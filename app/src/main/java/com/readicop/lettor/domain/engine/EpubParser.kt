package com.readicop.lettor.domain.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.core.text.HtmlCompat
import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

sealed interface EpubBlock {
  data class Heading(val level: Int, val text: String) : EpubBlock
  data class Paragraph(val content: AnnotatedString, val plainText: String) : EpubBlock
  data class Quote(val content: AnnotatedString, val plainText: String) : EpubBlock
  data class Image(val zipEntryPath: String, val alt: String = "") : EpubBlock
}

data class EpubChapter(
  val index: Int,
  val title: String,
  val zipPath: String,
  val blocks: List<EpubBlock>,
  val plainText: String
)

data class EpubChapterStub(
  val index: Int,
  val title: String,
  val zipPath: String
)

data class EpubMetadata(
  val title: String,
  val author: String,
  val genre: String,
  val description: String,
  val chapterCount: Int,
  val coverZipPath: String?
)

data class EpubDocument(
  val metadata: EpubMetadata,
  val chapters: List<EpubChapter>
)

data class EpubSearchHit(
  val chapterIndex: Int,
  val chapterTitle: String,
  val snippet: String
)

/**
 * Streaming/lazy EPUB session for large files (parses chapters on demand with LRU caching).
 */
class LazyEpubSession(val file: File) : Closeable {
  private var zipFile: ZipFile? = null
  private val chapterCache = LruCache<Int, EpubChapter>(12)
  @Volatile
  private var isClosed = false

  lateinit var metadata: EpubMetadata
    private set
  lateinit var chapterStubs: List<EpubChapterStub>
    private set

  @Synchronized
  fun open(): LazyEpubSession {
    isClosed = false
    val zip = ZipFile(file)
    zipFile = zip
    val (meta, stubs) = EpubParser.readIndex(zip, file.nameWithoutExtension)
    metadata = meta
    chapterStubs = stubs
    return this
  }

  @Synchronized
  fun getCachedChapter(index: Int): EpubChapter? {
    if (isClosed || !::chapterStubs.isInitialized || chapterStubs.isEmpty()) return null
    val safeIndex = index.coerceIn(0, (chapterStubs.size - 1).coerceAtLeast(0))
    return chapterCache.get(safeIndex)
  }

  @Synchronized
  fun getChapter(index: Int): EpubChapter {
    val safeIndex = index.coerceIn(0, (chapterStubs.size - 1).coerceAtLeast(0))
    chapterCache.get(safeIndex)?.let { return it }
    val stub = chapterStubs[safeIndex]
    val zip = zipFile ?: ZipFile(file).also { zipFile = it }
    val parsed = EpubParser.parseSingleChapter(zip, stub)
    chapterCache.put(safeIndex, parsed)
    return parsed
  }

  @Synchronized
  fun searchAllChapters(query: String): List<EpubSearchHit> {
    val trimmed = query.trim()
    if (trimmed.isBlank()) return emptyList()
    val hits = mutableListOf<EpubSearchHit>()
    for (stub in chapterStubs) {
      val ch = getChapter(stub.index)
      val idx = ch.plainText.indexOf(trimmed, ignoreCase = true)
      if (idx >= 0) {
        val start = (idx - 42).coerceAtLeast(0)
        val end = (idx + trimmed.length + 78).coerceAtMost(ch.plainText.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < ch.plainText.length) "…" else ""
        val snippet = prefix + ch.plainText.substring(start, end).replace('\n', ' ').trim() + suffix
        hits.add(EpubSearchHit(ch.index, ch.title, snippet))
      }
    }
    return hits
  }

  @Synchronized
  fun toEpubDocumentForQuery(maxChapters: Int = 60): EpubDocument {
    val chapters = chapterStubs.take(maxChapters).map { getChapter(it.index) }
    return EpubDocument(metadata = metadata, chapters = chapters)
  }

  @Synchronized
  override fun close() {
    isClosed = true
    chapterCache.evictAll()
    runCatching { zipFile?.close() }
    zipFile = null
  }
}

object EpubParser {

  fun inspectMetadataAndExtractCover(epubFile: File, coverDestFile: File?): EpubMetadata {
    ZipFile(epubFile).use { zip ->
      val opfPath = findOpfPath(zip) ?: return EpubMetadata(
        title = epubFile.nameWithoutExtension,
        author = "Autor desconocido",
        genre = "General",
        description = "",
        chapterCount = 1,
        coverZipPath = null
      )
      val opfParsed = parseOpf(zip, opfPath)
      val coverZipPath = opfParsed.coverZipPath
      if (coverZipPath != null && coverDestFile != null) {
        val entry = findZipEntryCaseInsensitive(zip, coverZipPath)
        if (entry != null) {
          zip.getInputStream(entry).use { input ->
            coverDestFile.outputStream().use { out -> input.copyTo(out) }
          }
        }
      }
      return EpubMetadata(
        title = opfParsed.title.ifBlank { epubFile.nameWithoutExtension },
        author = opfParsed.author.ifBlank { "Autor desconocido" },
        genre = inferGenre(opfParsed.subject, opfParsed.title),
        description = opfParsed.description,
        chapterCount = opfParsed.spinePaths.size.coerceAtLeast(1),
        coverZipPath = coverZipPath
      )
    }
  }

  internal fun readIndex(zip: ZipFile, fallbackName: String): Pair<EpubMetadata, List<EpubChapterStub>> {
    val opfPath = findOpfPath(zip)
      ?: throw IllegalArgumentException("El archivo EPUB no contiene un manifiesto válido.")
    val opf = parseOpf(zip, opfPath)
    val tocTitles = parseTocTitles(zip, opf)

    val stubs = opf.spinePaths.mapIndexed { idx, zipPath ->
      val normPath = normalizeZipPath(zipPath)
      val label = tocTitles[normPath] ?: "Capítulo ${idx + 1}"
      EpubChapterStub(index = idx, title = label, zipPath = zipPath)
    }.ifEmpty {
      listOf(EpubChapterStub(index = 0, title = opf.title.ifBlank { "Contenido" }, zipPath = ""))
    }

    val meta = EpubMetadata(
      title = opf.title.ifBlank { fallbackName },
      author = opf.author.ifBlank { "Autor desconocido" },
      genre = inferGenre(opf.subject, opf.title),
      description = opf.description,
      chapterCount = stubs.size,
      coverZipPath = opf.coverZipPath
    )
    return meta to stubs
  }

  internal fun parseSingleChapter(zip: ZipFile, stub: EpubChapterStub): EpubChapter {
    if (stub.zipPath.isBlank()) {
      return EpubChapter(
        index = stub.index,
        title = stub.title,
        zipPath = "",
        blocks = listOf(EpubBlock.Paragraph(AnnotatedString("Sin contenido legible."), "Sin contenido legible.")),
        plainText = ""
      )
    }
    return runCatching {
      val entry = findZipEntryCaseInsensitive(zip, stub.zipPath)
        ?: return EpubChapter(stub.index, stub.title, stub.zipPath, emptyList(), "")
      val html = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
      val baseDir = stub.zipPath.substringBeforeLast('/', "")
      val blocks = parseXhtmlBlocks(html, baseDir)
      val firstHeading = blocks.filterIsInstance<EpubBlock.Heading>().firstOrNull()?.text
      val title = if (stub.title.startsWith("Capítulo ") && !firstHeading.isNullOrBlank()) {
        firstHeading
      } else {
        stub.title
      }
      val plain = blocks.joinToString("\n\n") { block ->
        when (block) {
          is EpubBlock.Heading -> block.text
          is EpubBlock.Paragraph -> block.plainText
          is EpubBlock.Quote -> block.plainText
          is EpubBlock.Image -> ""
        }
      }.trim()

      EpubChapter(
        index = stub.index,
        title = title,
        zipPath = stub.zipPath,
        blocks = if (blocks.isEmpty()) listOf(EpubBlock.Paragraph(AnnotatedString(""), "")) else blocks,
        plainText = plain
      )
    }.getOrElse {
      EpubChapter(
        index = stub.index,
        title = stub.title,
        zipPath = stub.zipPath,
        blocks = listOf(
          EpubBlock.Paragraph(
            AnnotatedString("Este capítulo contiene formato XML dañado y fue omitido de forma segura."),
            "Este capítulo contiene formato XML dañado y fue omitido de forma segura."
          )
        ),
        plainText = ""
      )
    }
  }

  fun parseDocument(epubFile: File): EpubDocument {
    ZipFile(epubFile).use { zip ->
      val (meta, stubs) = readIndex(zip, epubFile.nameWithoutExtension)
      val chapters = stubs.map { parseSingleChapter(zip, it) }
      return EpubDocument(metadata = meta, chapters = chapters)
    }
  }

  fun loadChapterImage(epubFile: File, zipEntryPath: String, maxDimension: Int = 1400): Bitmap? {
    return runCatching {
      ZipFile(epubFile).use { zip ->
        val entry = findZipEntryCaseInsensitive(zip, zipEntryPath) ?: return@use null
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
        var sampleSize = 1
        while (boundsOpts.outWidth / sampleSize > maxDimension || boundsOpts.outHeight / sampleSize > maxDimension) {
          sampleSize *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
      }
    }.getOrNull()
  }

  private data class ManifestItem(
    val id: String,
    val href: String,
    val mediaType: String,
    val properties: String
  )

  private data class ParsedOpf(
    val title: String,
    val author: String,
    val subject: String,
    val description: String,
    val baseDir: String,
    val manifestById: Map<String, ManifestItem>,
    val spinePaths: List<String>,
    val ncxZipPath: String?,
    val navZipPath: String?,
    val coverZipPath: String?
  )

  private fun inferGenre(subject: String, title: String): String {
    val combined = "$subject $title".lowercase()
    return when {
      combined.contains("poes") || combined.contains("rima") || combined.contains("verso") -> "Poesía"
      combined.contains("quijote") || combined.contains("clásic") || combined.contains("clasic") -> "Clásicos"
      combined.contains("fantas") || combined.contains("ciencia fic") || combined.contains("sci-fi") -> "Ficción"
      combined.contains("histor") || combined.contains("biograf") -> "Historia"
      combined.contains("ensay") || combined.contains("filosof") -> "Ensayo"
      subject.isNotBlank() -> subject.split(',', ';').first().trim().take(24).replaceFirstChar { it.titlecase() }
      else -> "General"
    }
  }

  private fun findOpfPath(zip: ZipFile): String? {
    val containerEntry = findZipEntryCaseInsensitive(zip, "META-INF/container.xml")
    if (containerEntry != null) {
      runCatching {
        zip.getInputStream(containerEntry).use { stream ->
          val parser = newPullParser(stream)
          var event = parser.eventType
          while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.substringAfter(':') == "rootfile") {
              val fullPath = parser.getAttributeValue(null, "full-path")
              if (!fullPath.isNullOrBlank()) return normalizeZipPath(fullPath)
            }
            event = parser.next()
          }
        }
      }
    }
    val entries = zip.entries()
    while (entries.hasMoreElements()) {
      val name = entries.nextElement().name
      if (name.endsWith(".opf", ignoreCase = true)) {
        return normalizeZipPath(name)
      }
    }
    return null
  }

  private fun parseOpf(zip: ZipFile, opfPath: String): ParsedOpf {
    val baseDir = opfPath.substringBeforeLast('/', "")
    val entry = findZipEntryCaseInsensitive(zip, opfPath)
      ?: throw IllegalArgumentException("No se pudo abrir el archivo OPF.")

    var title = ""
    var author = ""
    var subject = ""
    var description = ""
    var coverMetaId: String? = null
    var spineTocId: String? = null
    val manifest = LinkedHashMap<String, ManifestItem>()
    val spineIds = mutableListOf<String>()

    zip.getInputStream(entry).use { stream ->
      val parser = newPullParser(stream)
      var event = parser.eventType
      while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG) {
          val localName = parser.name.substringAfter(':').lowercase()
          when (localName) {
            "title" -> if (title.isBlank()) title = readTagText(parser).trim()
            "creator" -> if (author.isBlank()) author = readTagText(parser).trim()
            "subject" -> if (subject.isBlank()) subject = readTagText(parser).trim()
            "description" -> if (description.isBlank()) {
              val raw = readTagText(parser).trim()
              description = HtmlCompat.fromHtml(raw, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
            }
            "meta" -> {
              val metaName = parser.getAttributeValue(null, "name")
              val content = parser.getAttributeValue(null, "content")
              if (metaName.equals("cover", ignoreCase = true) && !content.isNullOrBlank()) {
                coverMetaId = content
              }
            }
            "item" -> {
              val id = parser.getAttributeValue(null, "id").orEmpty()
              val href = parser.getAttributeValue(null, "href").orEmpty()
              val mediaType = parser.getAttributeValue(null, "media-type").orEmpty()
              val properties = parser.getAttributeValue(null, "properties").orEmpty()
              if (id.isNotBlank() && href.isNotBlank()) {
                manifest[id] = ManifestItem(id, href, mediaType, properties)
              }
            }
            "spine" -> {
              spineTocId = parser.getAttributeValue(null, "toc")
            }
            "itemref" -> {
              val idref = parser.getAttributeValue(null, "idref")
              val linear = parser.getAttributeValue(null, "linear")
              if (!idref.isNullOrBlank() && linear != "no") {
                spineIds.add(idref)
              }
            }
          }
        }
        event = parser.next()
      }
    }

    val spinePaths = spineIds.mapNotNull { id ->
      manifest[id]?.let { resolveZipPath(baseDir, it.href) }
    }

    val navItem = manifest.values.firstOrNull {
      it.properties.split(" ").any { prop -> prop == "nav" }
    }
    val ncxItem = (spineTocId?.let { manifest[it] })
      ?: manifest.values.firstOrNull { it.mediaType.contains("dtbncx", ignoreCase = true) }

    val coverItem = (coverMetaId?.let { metaId ->
      manifest[metaId] ?: manifest.values.firstOrNull { item ->
        item.mediaType.startsWith("image/") && (
          item.href.equals(metaId, ignoreCase = true) ||
            resolveZipPath(baseDir, item.href).equals(metaId, ignoreCase = true)
          )
      }
    })
      ?: manifest.values.firstOrNull {
        it.properties.split(Regex("\\s+")).any { prop -> prop.equals("cover-image", ignoreCase = true) }
      }
      ?: manifest.values.firstOrNull {
        it.id.contains("cover", ignoreCase = true) && it.mediaType.startsWith("image/")
      }
      ?: manifest.values.firstOrNull {
        (it.href.contains("cover", ignoreCase = true) ||
          it.href.contains("portada", ignoreCase = true) ||
          it.href.contains("cubierta", ignoreCase = true)) &&
          it.mediaType.startsWith("image/")
      }

    return ParsedOpf(
      title = title,
      author = author,
      subject = subject,
      description = description,
      baseDir = baseDir,
      manifestById = manifest,
      spinePaths = spinePaths,
      ncxZipPath = ncxItem?.let { resolveZipPath(baseDir, it.href) },
      navZipPath = navItem?.let { resolveZipPath(baseDir, it.href) },
      coverZipPath = coverItem?.let { resolveZipPath(baseDir, it.href) }
    )
  }

  private fun parseTocTitles(zip: ZipFile, opf: ParsedOpf): Map<String, String> {
    val result = mutableMapOf<String, String>()

    opf.navZipPath?.let { navPath ->
      findZipEntryCaseInsensitive(zip, navPath)?.let { entry ->
        val navDir = navPath.substringBeforeLast('/', "")
        val html = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val anchorRegex = Regex("""<a\b[^>]*href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        anchorRegex.findAll(html).forEach { match ->
          val rawHref = match.groupValues[1].substringBefore('#')
          val label = stripAllTagsAndDecode(match.groupValues[2]).trim()
          if (rawHref.isNotBlank() && label.isNotBlank()) {
            val resolved = resolveZipPath(navDir, rawHref)
            result.putIfAbsent(resolved, label)
          }
        }
      }
    }

    opf.ncxZipPath?.let { ncxPath ->
      findZipEntryCaseInsensitive(zip, ncxPath)?.let { entry ->
        val ncxDir = ncxPath.substringBeforeLast('/', "")
        runCatching {
          zip.getInputStream(entry).use { stream ->
            val parser = newPullParser(stream)
            var currentLabel = ""
            var inNavLabel = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
              when (event) {
                XmlPullParser.START_TAG -> {
                  val tag = parser.name.substringAfter(':').lowercase()
                  when (tag) {
                    "navlabel" -> {
                      inNavLabel = true
                      currentLabel = ""
                    }
                    "text" -> if (inNavLabel) {
                      currentLabel = readTagText(parser).trim()
                    }
                    "content" -> {
                      val src = parser.getAttributeValue(null, "src")?.substringBefore('#').orEmpty()
                      if (src.isNotBlank() && currentLabel.isNotBlank()) {
                        val resolved = resolveZipPath(ncxDir, src)
                        result.putIfAbsent(resolved, currentLabel)
                      }
                    }
                  }
                }
                XmlPullParser.END_TAG -> {
                  if (parser.name.substringAfter(':').lowercase() == "navlabel") {
                    inNavLabel = false
                  }
                }
              }
              event = parser.next()
            }
          }
        }
      }
    }

    return result
  }

  private fun parseXhtmlBlocks(rawHtml: String, baseDir: String): List<EpubBlock> {
    val bodyMatch = Regex("""<body\b[^>]*>(.*)</body>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
      .find(rawHtml)
    var content = bodyMatch?.groupValues?.get(1) ?: rawHtml
    content = content
      .replace(Regex("""<!--.*?-->""", setOf(RegexOption.DOT_MATCHES_ALL)), "")
      .replace(Regex("""<(script|style|head|title|desc)\b[^>]*>.*?</\1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")

    val blocks = mutableListOf<EpubBlock>()

    val blockRegex = Regex(
      """<(h[1-6]|p|blockquote|li|pre)\b[^>]*>(.*?)</\1>|<(?:img|image)\b[^>]*(?:src|xlink:href|href)\s*=\s*["']([^"']+)["'][^>]*/?>""",
      setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    var lastIndex = 0
    blockRegex.findAll(content).forEach { match ->
      if (match.range.first > lastIndex) {
        val between = content.substring(lastIndex, match.range.first)
        val plainBetween = stripAllTagsAndDecode(between).trim()
        if (plainBetween.length > 2) {
          blocks.add(EpubBlock.Paragraph(buildStyledInline(between), plainBetween))
        }
      }
      lastIndex = match.range.last + 1

      val imgSrc = match.groupValues[3]
      if (imgSrc.isNotBlank()) {
        val resolvedImg = resolveZipPath(baseDir, imgSrc)
        blocks.add(EpubBlock.Image(zipEntryPath = resolvedImg))
      } else {
        val tag = match.groupValues[1].lowercase()
        val inner = match.groupValues[2]

        val nestedImgRegex = Regex("""<(?:img|image)\b[^>]*(?:src|xlink:href|href)\s*=\s*["']([^"']+)["'][^>]*/?>""", RegexOption.IGNORE_CASE)
        nestedImgRegex.findAll(inner).forEach { imgMatch ->
          val nestedSrc = imgMatch.groupValues[1]
          if (nestedSrc.isNotBlank()) {
            blocks.add(EpubBlock.Image(zipEntryPath = resolveZipPath(baseDir, nestedSrc)))
          }
        }

        val plain = stripAllTagsAndDecode(inner).trim()
        if (plain.isNotBlank()) {
          when {
            tag.startsWith("h") -> {
              val level = tag.drop(1).toIntOrNull()?.coerceIn(1, 6) ?: 2
              blocks.add(EpubBlock.Heading(level = level, text = plain))
            }
            tag == "blockquote" -> {
              blocks.add(EpubBlock.Quote(content = buildStyledInline(inner), plainText = plain))
            }
            tag == "li" -> {
              val bulleted = buildAnnotatedString {
                append("•  ")
                append(buildStyledInline(inner))
              }
              blocks.add(EpubBlock.Paragraph(content = bulleted, plainText = "•  $plain"))
            }
            else -> {
              blocks.add(EpubBlock.Paragraph(content = buildStyledInline(inner), plainText = plain))
            }
          }
        }
      }
    }

    if (lastIndex < content.length) {
      val tail = content.substring(lastIndex)
      val plainTail = stripAllTagsAndDecode(tail).trim()
      if (plainTail.length > 2) {
        blocks.add(EpubBlock.Paragraph(buildStyledInline(tail), plainTail))
      }
    }

    return blocks
  }

  private fun buildStyledInline(htmlSnippet: String): AnnotatedString {
    val normalized = htmlSnippet
      .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
      .replace(Regex("""\s+"""), " ")

    val tokenRegex = Regex("""<(/?)(b|strong|i|em|u)\b[^>]*>|<[^>]+>""", RegexOption.IGNORE_CASE)
    var boldDepth = 0
    var italicDepth = 0
    var underlineDepth = 0

    return buildAnnotatedString {
      var cursor = 0
      tokenRegex.findAll(normalized).forEach { match ->
        if (match.range.first > cursor) {
          val textChunk = decodeEntities(normalized.substring(cursor, match.range.first))
          if (textChunk.isNotEmpty()) {
            appendStyled(textChunk, boldDepth > 0, italicDepth > 0, underlineDepth > 0)
          }
        }
        cursor = match.range.last + 1

        val isClosing = match.groupValues[1] == "/"
        val tag = match.groupValues[2].lowercase()
        when (tag) {
          "b", "strong" -> boldDepth = (boldDepth + if (isClosing) -1 else 1).coerceAtLeast(0)
          "i", "em" -> italicDepth = (italicDepth + if (isClosing) -1 else 1).coerceAtLeast(0)
          "u" -> underlineDepth = (underlineDepth + if (isClosing) -1 else 1).coerceAtLeast(0)
        }
      }
      if (cursor < normalized.length) {
        val tail = decodeEntities(normalized.substring(cursor))
        if (tail.isNotEmpty()) {
          appendStyled(tail, boldDepth > 0, italicDepth > 0, underlineDepth > 0)
        }
      }
    }.let { raw ->
      val trimmedText = raw.text.trim()
      if (trimmedText.length == raw.text.length) raw
      else {
        val startIdx = raw.text.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        val endIdx = (raw.text.indexOfLast { !it.isWhitespace() } + 1).coerceAtLeast(startIdx)
        raw.subSequence(startIdx, endIdx)
      }
    }
  }

  private fun AnnotatedString.Builder.appendStyled(
    text: String,
    bold: Boolean,
    italic: Boolean,
    underline: Boolean
  ) {
    if (!bold && !italic && !underline) {
      append(text)
    } else {
      val style = SpanStyle(
        fontWeight = if (bold) FontWeight.Bold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        textDecoration = if (underline) TextDecoration.Underline else null
      )
      pushStyle(style)
      append(text)
      pop()
    }
  }

  private fun stripAllTagsAndDecode(html: String): String {
    val noTags = html
      .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
      .replace(Regex("""<[^>]+>"""), " ")
      .replace(Regex("""[ \t\r]+"""), " ")
    return decodeEntities(noTags)
  }

  private fun decodeEntities(text: String): String {
    if (!text.contains('&')) return text
    return HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
  }

  private fun newPullParser(stream: java.io.InputStream): XmlPullParser {
    val factory = XmlPullParserFactory.newInstance().apply {
      isNamespaceAware = false
    }
    return factory.newPullParser().apply {
      setInput(stream, "UTF-8")
    }
  }

  private fun readTagText(parser: XmlPullParser): String {
    val sb = StringBuilder()
    var depth = 1
    while (depth > 0) {
      when (parser.next()) {
        XmlPullParser.TEXT -> sb.append(parser.text.orEmpty())
        XmlPullParser.START_TAG -> depth++
        XmlPullParser.END_TAG -> depth--
        XmlPullParser.END_DOCUMENT -> break
      }
    }
    return sb.toString()
  }

  private fun resolveZipPath(baseDir: String, relativeHref: String): String {
    val decoded = runCatching { java.net.URLDecoder.decode(relativeHref, "UTF-8") }.getOrDefault(relativeHref)
    val cleanHref = decoded.substringBefore('#').substringBefore('?')
    val combined = if (baseDir.isBlank() || cleanHref.startsWith("/")) {
      cleanHref.trimStart('/')
    } else {
      "$baseDir/$cleanHref"
    }
    return normalizeZipPath(combined)
  }

  private fun normalizeZipPath(path: String): String {
    val stack = ArrayDeque<String>()
    path.replace('\\', '/').split('/').forEach { segment ->
      when (segment) {
        "", "." -> Unit
        ".." -> if (stack.isNotEmpty()) stack.removeLast()
        else -> stack.addLast(segment)
      }
    }
    return stack.joinToString("/")
  }

  private fun findZipEntryCaseInsensitive(zip: ZipFile, path: String): java.util.zip.ZipEntry? {
    val normalized = normalizeZipPath(path)
    zip.getEntry(normalized)?.let { return it }
    val entries = zip.entries()
    while (entries.hasMoreElements()) {
      val candidate = entries.nextElement()
      if (normalizeZipPath(candidate.name).equals(normalized, ignoreCase = true)) {
        return candidate
      }
    }
    return null
  }
}
