package com.readicop.lettor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.readicop.lettor.domain.engine.EpubParser
import com.readicop.lettor.domain.engine.LazyEpubSession
import com.readicop.lettor.domain.engine.LocalBookIntelligence
import com.readicop.lettor.domain.engine.LocalDictionaryAndTranslator
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  private fun copySampleEpub(context: Context): File {
    val tempEpub = File(context.cacheDir, "test_quijote.epub")
    context.assets.open("samples/don_quijote_seleccion.epub").use { input ->
      tempEpub.outputStream().use { out -> input.copyTo(out) }
    }
    return tempEpub
  }

  @Test
  fun `app_name is Lettor and package is com_readicop_lettor`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Lettor", appName)
    assertEquals("com.readicop.lettor", BuildConfig.APPLICATION_ID)
    assertEquals("com.readicop.lettor", context.packageName)
  }

  @Test
  fun `bundled epub sample parses chapters and metadata via LazyEpubSession`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val tempEpub = copySampleEpub(context)

    LazyEpubSession(tempEpub).use { session ->
      session.open()
      assertEquals("Don Quijote de la Mancha (Selección)", session.metadata.title)
      assertEquals("Miguel de Cervantes Saavedra", session.metadata.author)
      assertEquals(4, session.chapterStubs.size)

      assertEquals(null, session.getCachedChapter(1))
      val ch1 = session.getChapter(1)
      assertTrue(ch1.plainText.contains("En un lugar de la Mancha"))
      assertTrue(session.getCachedChapter(1) === ch1)

      val hits = session.searchAllChapters("molinos")
      assertTrue(hits.isNotEmpty())
    }
    tempEpub.delete()
  }

  @Test
  fun `local intelligence summarizes chapter and answers book questions`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val tempEpub = copySampleEpub(context)
    val doc = EpubParser.parseDocument(tempEpub)

    val summary = LocalBookIntelligence.summarizeChapter(doc.chapters[1], wordsPerMinute = 200)
    assertTrue(summary.wordCount > 50)
    assertTrue(summary.keySentences.isNotEmpty())
    assertTrue(summary.estimatedMinutes >= 1)

    val answer = LocalBookIntelligence.askBook(doc, "molinos de viento")
    assertTrue(answer.passages.isNotEmpty())
    assertFalse(answer.summaryAnswer.isBlank())
    tempEpub.delete()
  }

  @Test
  fun `local dictionary and translator resolve literary words and phrases`() {
    val entry = LocalDictionaryAndTranslator.lookupWord("hidalgos")
    assertTrue(entry.isExactMatch)
    assertTrue(entry.definition.contains("nobleza", ignoreCase = true) || entry.englishTranslation.contains("nobleman", ignoreCase = true))

    val translation = LocalDictionaryAndTranslator.translateLocally("En un lugar de la Mancha")
    assertTrue(translation.translatedText.contains("La Mancha", ignoreCase = true))
  }

  @Test
  fun `resume notification formats real chapter and percentage progress`() {
    val epubBook = com.readicop.lettor.data.local.BookEntity(
      id = 7L,
      title = "Don Quijote",
      author = "Cervantes",
      format = com.readicop.lettor.data.local.BookFormat.EPUB,
      filePath = "/tmp/fake.epub",
      totalUnits = 4,
      currentUnit = 1,
      progressFraction = 0.35f
    )
    assertEquals("Capítulo 2 de 4 · 35 %", com.readicop.lettor.notifications.ResumeReadingScheduler.formatProgressSummary(epubBook))

    val pdfBook = epubBook.copy(
      format = com.readicop.lettor.data.local.BookFormat.PDF,
      totalUnits = 10,
      currentUnit = 2,
      progressFraction = 0.30f
    )
    assertEquals("Página 3 de 10 · 30 %", com.readicop.lettor.notifications.ResumeReadingScheduler.formatProgressSummary(pdfBook))
  }

  @Test
  fun `resume scheduler avoids quiet night and madrugada hours and respects cooldown`() {
    val tz = java.util.TimeZone.getTimeZone("UTC")
    val cal = java.util.Calendar.getInstance(tz).apply {
      set(2026, java.util.Calendar.NOVEMBER, 17, 2, 15, 0)
      set(java.util.Calendar.MILLISECOND, 0)
    }
    // 24h from 02:15 would land at 02:15 (madrugada) -> must shift to 09:30 same day
    val triggerFromNight = com.readicop.lettor.notifications.ResumeReadingScheduler.calculateNextAllowedTriggerMillis(
      nowMillis = cal.timeInMillis,
      delayHours = 24,
      lastNotifiedAtMillis = 0L,
      timeZone = tz
    )
    val resultCal = java.util.Calendar.getInstance(tz).apply { timeInMillis = triggerFromNight }
    assertEquals(18, resultCal.get(java.util.Calendar.DAY_OF_MONTH))
    assertEquals(9, resultCal.get(java.util.Calendar.HOUR_OF_DAY))
    assertEquals(30, resultCal.get(java.util.Calendar.MINUTE))

    // 24h from 14:15 should stay at 14:15 next day
    cal.set(java.util.Calendar.HOUR_OF_DAY, 14)
    val triggerDaytime = com.readicop.lettor.notifications.ResumeReadingScheduler.calculateNextAllowedTriggerMillis(
      nowMillis = cal.timeInMillis,
      delayHours = 24,
      lastNotifiedAtMillis = 0L,
      timeZone = tz
    )
    val dayCal = java.util.Calendar.getInstance(tz).apply { timeInMillis = triggerDaytime }
    assertEquals(18, dayCal.get(java.util.Calendar.DAY_OF_MONTH))
    assertEquals(14, dayCal.get(java.util.Calendar.HOUR_OF_DAY))

    // If notified recently with 48h cooldown, trigger respects cooldown window
    val triggerCooldown = com.readicop.lettor.notifications.ResumeReadingScheduler.calculateNextAllowedTriggerMillis(
      nowMillis = cal.timeInMillis,
      delayHours = 24,
      lastNotifiedAtMillis = cal.timeInMillis,
      cooldownMillis = 48L * 3_600_000L,
      timeZone = tz
    )
    val coolCal = java.util.Calendar.getInstance(tz).apply { timeInMillis = triggerCooldown }
    assertEquals(19, coolCal.get(java.util.Calendar.DAY_OF_MONTH))
  }

  @Test
  fun `toucan vector small icon and cached canvas bitmap render properly`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    com.readicop.lettor.notifications.ToucanArtwork.clearCache(context)

    val smallDrawable = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_notification_toucan)
    assertTrue(smallDrawable != null)

    val first = com.readicop.lettor.notifications.ToucanArtwork.getOrCreateBitmap(context)
    val second = com.readicop.lettor.notifications.ToucanArtwork.getOrCreateBitmap(context)
    assertEquals(192, first.width)
    assertEquals(192, first.height)
    assertTrue(first === second)
    assertTrue(File(context.cacheDir, "lettor_toucan_badge_v1.png").exists())
  }

  @Test
  fun `citation formatter produces clean literary quotes for epub and pdf`() {
    val pdfQuote = com.readicop.lettor.ui.reader.formatBookCitation(
      selectedText = "  En un lugar   de la Mancha  ",
      bookTitle = "Don Quijote",
      bookAuthor = "Miguel de Cervantes",
      locationLabel = "pág. 12"
    )
    assertEquals("«En un lugar de la Mancha» — Don Quijote, Miguel de Cervantes (pág. 12)", pdfQuote)

    val noAuthorQuote = com.readicop.lettor.ui.reader.formatBookCitation(
      selectedText = "Texto breve",
      bookTitle = "Antología",
      bookAuthor = "",
      locationLabel = "cap. 2, pág. 3"
    )
    assertEquals("«Texto breve» — Antología (cap. 2, pág. 3)", noAuthorQuote)
  }

  @Test
  fun `epub character selection expands words and extracts multi-block ranges independently of layout`() {
    val p0 = "En un lugar de la Mancha."
    val p1 = "De cuyo nombre no quiero acordarme."
    val blocks = listOf(
      com.readicop.lettor.domain.engine.EpubBlock.Paragraph(
        androidx.compose.ui.text.AnnotatedString(p0),
        p0
      ),
      com.readicop.lettor.domain.engine.EpubBlock.Paragraph(
        androidx.compose.ui.text.AnnotatedString(p1),
        p1
      )
    )

    val wordRange = com.readicop.lettor.ui.reader.expandWordRangeAtOffset(p0, 7)
    assertEquals("lugar", p0.substring(wordRange))

    val multiSelection = com.readicop.lettor.ui.reader.EpubTextSelection(
      chapterIndex = 0,
      anchorA = com.readicop.lettor.ui.reader.EpubCharPosition(1, 7),
      anchorB = com.readicop.lettor.ui.reader.EpubCharPosition(0, 6)
    )
    val extracted = com.readicop.lettor.ui.reader.extractSelectedEpubText(blocks, multiSelection)
    assertEquals("lugar de la Mancha.\n\nDe cuyo", extracted)
  }

  @Test
  fun `pdf engine extracts normalized words for digital pdf and detects scanned image-only pdf`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    com.readicop.lettor.domain.engine.PdfBookSession.ensurePdfBoxInitialized(context)

    val digitalPdf = File(context.cacheDir, "test_digital.pdf")
    com.tom_roush.pdfbox.pdmodel.PDDocument().use { doc ->
      val page = com.tom_roush.pdfbox.pdmodel.PDPage()
      doc.addPage(page)
      com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, page).use { cs ->
        cs.beginText()
        cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 14f)
        cs.newLineAtOffset(72f, 700f)
        cs.showText("Lettor lectura digital")
        cs.endText()
      }
      doc.save(digitalPdf)
    }

    val digitalData = com.tom_roush.pdfbox.pdmodel.PDDocument.load(digitalPdf).use { doc ->
      com.readicop.lettor.domain.engine.PdfBookSession.extractWordsFromPdPage(doc, 0)
    }
    assertFalse(digitalData.isScanned)
    assertEquals(listOf("Lettor", "lectura", "digital"), digitalData.words.map { it.text })
    digitalData.words.forEach { w ->
      assertTrue(w.rect.left in 0f..1f && w.rect.right in 0f..1f)
      assertTrue(w.rect.top in 0f..1f && w.rect.bottom in 0f..1f)
      assertTrue(w.rect.right > w.rect.left)
      assertTrue(w.rect.bottom > w.rect.top)
    }
    val selectedPdfText = com.readicop.lettor.ui.reader.extractSelectedPdfText(
      digitalData.words,
      com.readicop.lettor.ui.reader.PdfWordSelection(pageIndex = 0, anchorWordA = 2, anchorWordB = 0)
    )
    assertEquals("Lettor lectura digital", selectedPdfText)

    val scannedPdf = File(context.cacheDir, "test_scanned.pdf")
    com.tom_roush.pdfbox.pdmodel.PDDocument().use { doc ->
      val page = com.tom_roush.pdfbox.pdmodel.PDPage()
      doc.addPage(page)
      com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, page).use { cs ->
        cs.addRect(50f, 50f, 200f, 200f)
        cs.fill()
      }
      doc.save(scannedPdf)
    }

    val scannedData = com.tom_roush.pdfbox.pdmodel.PDDocument.load(scannedPdf).use { doc ->
      com.readicop.lettor.domain.engine.PdfBookSession.extractWordsFromPdPage(doc, 0)
    }
    assertTrue(scannedData.isScanned)
    assertTrue(scannedData.words.isEmpty())

    digitalPdf.delete()
    scannedPdf.delete()
  }

  @Test
  fun `saved epub highlight resolves only selected characters across blocks and slices`() {
    val p0 = "En un lugar de la Mancha, de cuyo nombre no quiero acordarme."
    val p1 = "No ha mucho tiempo que vivía un hidalgo."
    val singleBlockHighlight = com.readicop.lettor.data.local.HighlightEntity(
      id = 1L,
      bookId = 10L,
      unitIndex = 0,
      blockIndex = 0,
      endBlockIndex = 0,
      startCharOffset = 6,
      endCharOffset = 11,
      excerpt = "lugar",
      color = com.readicop.lettor.data.local.HighlightColor.TERRACOTTA,
      isStickyNoteOnly = false
    )

    // First slice [0..30): must highlight strictly "lugar" [6..11), not the full paragraph
    val slice0Range = com.readicop.lettor.ui.reader.resolveSavedHighlightRangeInSlice(
      highlight = singleBlockHighlight,
      fullBlockText = p0,
      blockIndex = 0,
      sliceStartOffset = 0,
      sliceLength = 30
    )
    assertEquals(6 until 11, slice0Range)
    assertEquals("lugar", p0.substring(slice0Range!!))

    // Second slice [30..length) of the SAME paragraph must NOT be highlighted
    val slice1Range = com.readicop.lettor.ui.reader.resolveSavedHighlightRangeInSlice(
      highlight = singleBlockHighlight,
      fullBlockText = p0,
      blockIndex = 0,
      sliceStartOffset = 30,
      sliceLength = p0.length - 30
    )
    assertEquals(null, slice1Range)

    // Another block must NOT be highlighted
    val otherBlockRange = com.readicop.lettor.ui.reader.resolveSavedHighlightRangeInSlice(
      highlight = singleBlockHighlight,
      fullBlockText = p1,
      blockIndex = 1,
      sliceStartOffset = 0,
      sliceLength = p1.length
    )
    assertEquals(null, otherBlockRange)

    // Multi-block highlight spanning from p0[51..] ("acordarme.") to p1[..11] ("No ha mucho")
    val multiBlockHighlight = com.readicop.lettor.data.local.HighlightEntity(
      id = 2L,
      bookId = 10L,
      unitIndex = 0,
      blockIndex = 0,
      endBlockIndex = 1,
      startCharOffset = 51,
      endCharOffset = 11,
      excerpt = "acordarme.\n\nNo ha mucho",
      color = com.readicop.lettor.data.local.HighlightColor.SAGE,
      isStickyNoteOnly = false
    )
    val startSliceTail = com.readicop.lettor.ui.reader.resolveSavedHighlightRangeInSlice(
      highlight = multiBlockHighlight,
      fullBlockText = p0,
      blockIndex = 0,
      sliceStartOffset = 30,
      sliceLength = p0.length - 30
    )
    assertEquals(21 until (p0.length - 30), startSliceTail)

    val endBlockHead = com.readicop.lettor.ui.reader.resolveSavedHighlightRangeInSlice(
      highlight = multiBlockHighlight,
      fullBlockText = p1,
      blockIndex = 1,
      sliceStartOffset = 0,
      sliceLength = p1.length
    )
    assertEquals(0 until 11, endBlockHead)
  }

  @Test
  fun `room highlight persistence preserves exact character offsets when reopening book`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
      context,
      com.readicop.lettor.data.local.LettorDatabase::class.java
    ).allowMainThreadQueries().build()

    try {
      kotlinx.coroutines.runBlocking {
        val bookId = db.bookDao().insertBook(
          com.readicop.lettor.data.local.BookEntity(
            title = "Quijote",
            author = "Cervantes",
            format = com.readicop.lettor.data.local.BookFormat.EPUB,
            filePath = "/tmp/quijote.epub"
          )
        )
        db.highlightDao().insertHighlight(
          com.readicop.lettor.data.local.HighlightEntity(
            bookId = bookId,
            unitIndex = 1,
            blockIndex = 2,
            endBlockIndex = 2,
            startCharOffset = 14,
            endCharOffset = 29,
            excerpt = "de la Mancha",
            stickyNote = "",
            color = com.readicop.lettor.data.local.HighlightColor.SKY,
            isStickyNoteOnly = false
          )
        )
        val loaded = db.highlightDao().getAllHighlightsOnce().filter { it.bookId == bookId }
        assertEquals(1, loaded.size)
        val saved = loaded.first()
        assertEquals(2, saved.blockIndex)
        assertEquals(2, saved.endBlockIndex)
        assertEquals(14, saved.startCharOffset)
        assertEquals(29, saved.endCharOffset)
        assertEquals(com.readicop.lettor.data.local.HighlightColor.SKY, saved.color)
        assertFalse(saved.isStickyNoteOnly)
      }
    } finally {
      db.close()
    }
  }

  @Test
  fun `pdf zoom tile region calculation and render spec keep crisp resolution at high zoom`() {
    // Unzoomed scale returns null (uses base bitmap)
    assertEquals(
      null,
      com.readicop.lettor.domain.engine.computePdfZoomTileRegion(
        viewportWidthPx = 1080f,
        viewportHeightPx = 1600f,
        pageWidthPx = 1080f,
        pageHeightPx = 1520f,
        scale = 1.0f,
        panOffsetX = 0f,
        panOffsetY = 0f
      )
    )

    // Centered 4x zoom crops strictly around center + overscan
    val region4x = com.readicop.lettor.domain.engine.computePdfZoomTileRegion(
      viewportWidthPx = 1080f,
      viewportHeightPx = 1520f,
      pageWidthPx = 1080f,
      pageHeightPx = 1520f,
      scale = 4.0f,
      panOffsetX = 0f,
      panOffsetY = 0f,
      overscanFraction = 0.10f
    )!!
    assertTrue(region4x.left in 0.30f..0.40f)
    assertTrue(region4x.right in 0.60f..0.70f)
    assertTrue(region4x.top in 0.30f..0.40f)
    assertTrue(region4x.bottom in 0.60f..0.70f)

    val visibleOnly = com.readicop.lettor.domain.engine.computePdfZoomTileRegion(
      viewportWidthPx = 1080f,
      viewportHeightPx = 1520f,
      pageWidthPx = 1080f,
      pageHeightPx = 1520f,
      scale = 4.0f,
      panOffsetX = 0f,
      panOffsetY = 0f,
      overscanFraction = 0f
    )!!
    assertTrue(region4x.contains(visibleOnly))

    // Render spec produces high pixel density matching zoomed viewport while capping huge dimensions
    val spec4x = com.readicop.lettor.domain.engine.computePdfRegionRenderSpec(
      pagePtWidth = 612f,
      pagePtHeight = 792f,
      region = region4x,
      fullPageTargetWidthPx = 1080f * 4.0f
    )!!
    // Region width is ~0.30 -> ~1296px wide (far sharper than scaling a 1080px base page)
    assertTrue(spec4x.bitmapWidthPx >= 1000)
    assertTrue(spec4x.bitmapHeightPx >= 1000)
    assertTrue(spec4x.bitmapWidthPx <= 2400)
    assertTrue(spec4x.bitmapHeightPx <= 2400)
    assertTrue(spec4x.translatePtX < 0f)
    assertTrue(spec4x.translatePtY < 0f)
    assertTrue(spec4x.scaleX > 5f)
    assertTrue(spec4x.scaleY > 5f)

    // Extreme 8x zoom safely caps max bitmap dim at 2400px without OOM
    val spec8x = com.readicop.lettor.domain.engine.computePdfRegionRenderSpec(
      pagePtWidth = 612f,
      pagePtHeight = 792f,
      region = region4x,
      fullPageTargetWidthPx = 1080f * 8.0f
    )!!
    assertEquals(2400, maxOf(spec8x.bitmapWidthPx, spec8x.bitmapHeightPx))
  }

  @Test
  fun `pdf deliberate center tap zone and selection hold delay respect thresholds`() {
    val viewport = androidx.compose.ui.unit.IntSize(1000, 2000)

    // Center of viewport toggles controls
    assertTrue(
      com.readicop.lettor.ui.reader.isDeliberatePdfCenterTap(
        androidx.compose.ui.geometry.Offset(500f, 1000f),
        viewport
      )
    )
    assertTrue(
      com.readicop.lettor.ui.reader.isDeliberatePdfCenterTap(
        androidx.compose.ui.geometry.Offset(250f, 400f),
        viewport
      )
    )

    // Edge / margin / corner / gesture-bar touches never toggle PDF controls
    assertFalse(
      com.readicop.lettor.ui.reader.isDeliberatePdfCenterTap(
        androidx.compose.ui.geometry.Offset(100f, 1000f),
        viewport
      )
    )
    assertFalse(
      com.readicop.lettor.ui.reader.isDeliberatePdfCenterTap(
        androidx.compose.ui.geometry.Offset(900f, 1000f),
        viewport
      )
    )
    assertFalse(
      com.readicop.lettor.ui.reader.isDeliberatePdfCenterTap(
        androidx.compose.ui.geometry.Offset(500f, 120f),
        viewport
      )
    )
    assertFalse(
      com.readicop.lettor.ui.reader.isDeliberatePdfCenterTap(
        androidx.compose.ui.geometry.Offset(500f, 1900f),
        viewport
      )
    )
    assertFalse(
      com.readicop.lettor.ui.reader.isDeliberatePdfCenterTap(
        androidx.compose.ui.geometry.Offset(500f, 1000f),
        androidx.compose.ui.unit.IntSize.Zero
      )
    )

    // Selection delay is in 400-500ms window and strictly longer than deliberate tap window
    assertTrue(com.readicop.lettor.ui.reader.SELECTION_LONG_PRESS_DELAY_MS in 400L..500L)
    assertTrue(
      com.readicop.lettor.ui.reader.PDF_DELIBERATE_TAP_MAX_DURATION_MS <
        com.readicop.lettor.ui.reader.SELECTION_LONG_PRESS_DELAY_MS
    )
  }

  @Test
  fun `epub paragraph button expands active selection to full paragraph or selects fallback block`() {
    val p0 = "  Primer párrafo completo de prueba.  "
    val p1 = "Segundo párrafo con varias palabras para probar."
    val blocks = listOf(
      com.readicop.lettor.domain.engine.EpubBlock.Paragraph(
        androidx.compose.ui.text.AnnotatedString(p0),
        p0
      ),
      com.readicop.lettor.domain.engine.EpubBlock.Paragraph(
        androidx.compose.ui.text.AnnotatedString(p1),
        p1
      )
    )

    // Partial word selection in block 0 expands to full trimmed paragraph bounds [2..36)
    val wordSel = com.readicop.lettor.ui.reader.EpubTextSelection(
      chapterIndex = 3,
      anchorA = com.readicop.lettor.ui.reader.EpubCharPosition(0, 9),
      anchorB = com.readicop.lettor.ui.reader.EpubCharPosition(0, 16)
    )
    val expanded = com.readicop.lettor.ui.reader.expandEpubSelectionToParagraph(
      blocks = blocks,
      selection = wordSel,
      fallbackBlockIndex = 1,
      chapterIndex = 3
    )!!
    assertEquals(0, expanded.start.blockIndex)
    assertEquals(2, expanded.start.charOffset)
    assertEquals(0, expanded.end.blockIndex)
    assertEquals(36, expanded.end.charOffset)
    assertEquals("Primer párrafo completo de prueba.", com.readicop.lettor.ui.reader.extractSelectedEpubText(blocks, expanded))

    // Null selection falls back to reading position block 1
    val fallback = com.readicop.lettor.ui.reader.expandEpubSelectionToParagraph(
      blocks = blocks,
      selection = null,
      fallbackBlockIndex = 1,
      chapterIndex = 3
    )!!
    assertEquals(1, fallback.start.blockIndex)
    assertEquals(0, fallback.start.charOffset)
    assertEquals(p1.length, fallback.end.charOffset)
    assertEquals(p1, com.readicop.lettor.ui.reader.extractSelectedEpubText(blocks, fallback))
  }

  @Test
  fun `pdf paragraph expansion detects paragraph boundaries and expands word selection`() {
    fun word(idx: Int, text: String, l: Float, t: Float, r: Float, b: Float) =
      com.readicop.lettor.domain.engine.PdfGlyphWord(
        index = idx,
        text = text,
        rect = com.readicop.lettor.domain.engine.PdfNormalizedRect(l, t, r, b),
        charRects = emptyList()
      )

    // Paragraph 1: two lines (line 0 + line 1), followed by vertical gap before Paragraph 2 (line 2 + line 3)
    val words = listOf(
      // Line 0 (y 0.10..0.12)
      word(0, "En", 0.10f, 0.10f, 0.18f, 0.12f),
      word(1, "un", 0.20f, 0.10f, 0.30f, 0.12f),
      word(2, "lugar", 0.32f, 0.10f, 0.88f, 0.12f),
      // Line 1 (y 0.13..0.15) - short last line of Paragraph 1
      word(3, "de", 0.10f, 0.13f, 0.20f, 0.15f),
      word(4, "la", 0.22f, 0.13f, 0.30f, 0.15f),
      word(5, "Mancha.", 0.32f, 0.13f, 0.50f, 0.15f),
      // Line 2 (y 0.19..0.21) - Paragraph 2 after vertical gap
      word(6, "No", 0.14f, 0.19f, 0.22f, 0.21f),
      word(7, "ha", 0.24f, 0.19f, 0.32f, 0.21f),
      word(8, "mucho", 0.34f, 0.19f, 0.88f, 0.21f),
      // Line 3 (y 0.22..0.24)
      word(9, "tiempo.", 0.10f, 0.22f, 0.35f, 0.24f)
    )

    val para1 = com.readicop.lettor.ui.reader.expandPdfParagraphRange(words, 1, 1)
    assertEquals(0..5, para1)

    val para2 = com.readicop.lettor.ui.reader.expandPdfParagraphRange(words, 7, 7)
    assertEquals(6..9, para2)
  }

  @Test
  fun `pdf engine extracts outline chapters including nested items and returns empty when missing`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    com.readicop.lettor.domain.engine.PdfBookSession.ensurePdfBoxInitialized(context)

    val outlinedPdf = File(context.cacheDir, "test_outline.pdf")
    val plainPdf = File(context.cacheDir, "test_no_outline.pdf")

    try {
      com.tom_roush.pdfbox.pdmodel.PDDocument().use { doc ->
        val page0 = com.tom_roush.pdfbox.pdmodel.PDPage()
        val page1 = com.tom_roush.pdfbox.pdmodel.PDPage()
        val page2 = com.tom_roush.pdfbox.pdmodel.PDPage()
        doc.addPage(page0)
        doc.addPage(page1)
        doc.addPage(page2)

        val outline = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline()
        doc.documentCatalog.documentOutline = outline

        val ch1 = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem().apply {
          title = "Prólogo"
          setDestination(page0)
        }
        val ch2 = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem().apply {
          title = "Capítulo 1"
          setDestination(page1)
        }
        val sub = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem().apply {
          title = "Sección 1.1"
          setDestination(page2)
        }
        ch2.addLast(sub)
        outline.addLast(ch1)
        outline.addLast(ch2)

        doc.save(outlinedPdf)
      }

      val extracted = com.tom_roush.pdfbox.pdmodel.PDDocument.load(outlinedPdf).use { doc ->
        com.readicop.lettor.domain.engine.PdfBookSession.extractOutlineChapters(doc)
      }
      assertEquals(3, extracted.size)
      assertEquals("Prólogo", extracted[0].title)
      assertEquals(0, extracted[0].pageIndex)
      assertEquals(0, extracted[0].level)

      assertEquals("Capítulo 1", extracted[1].title)
      assertEquals(1, extracted[1].pageIndex)
      assertEquals(0, extracted[1].level)

      assertEquals("Sección 1.1", extracted[2].title)
      assertEquals(2, extracted[2].pageIndex)
      assertEquals(1, extracted[2].level)

      com.tom_roush.pdfbox.pdmodel.PDDocument().use { doc ->
        doc.addPage(com.tom_roush.pdfbox.pdmodel.PDPage())
        doc.save(plainPdf)
      }
      val emptyOutline = com.tom_roush.pdfbox.pdmodel.PDDocument.load(plainPdf).use { doc ->
        com.readicop.lettor.domain.engine.PdfBookSession.extractOutlineChapters(doc)
      }
      assertTrue(emptyOutline.isEmpty())
    } finally {
      outlinedPdf.delete()
      plainPdf.delete()
    }
  }

  @Test
  fun `pdf teardrop handle anchors sit flush at bottom corners of start and end words`() {
    val words = listOf(
      com.readicop.lettor.domain.engine.PdfGlyphWord(
        index = 0,
        text = "En",
        rect = com.readicop.lettor.domain.engine.PdfNormalizedRect(0.10f, 0.20f, 0.20f, 0.25f),
        charRects = emptyList()
      ),
      com.readicop.lettor.domain.engine.PdfGlyphWord(
        index = 1,
        text = "Mancha",
        rect = com.readicop.lettor.domain.engine.PdfNormalizedRect(0.30f, 0.20f, 0.50f, 0.25f),
        charRects = emptyList()
      )
    )
    val pageText = com.readicop.lettor.domain.engine.PdfPageTextData(
      pageIndex = 0,
      words = words,
      isScanned = false
    )
    val metrics = com.readicop.lettor.ui.reader.PdfPageRenderMetrics(
      pageIndex = 0,
      viewportPosInRoot = androidx.compose.ui.geometry.Offset.Zero,
      viewportSize = androidx.compose.ui.unit.IntSize(1000, 1000),
      basePageSizePx = androidx.compose.ui.geometry.Size(1000f, 1000f),
      scale = 1f,
      panOffset = androidx.compose.ui.geometry.Offset.Zero
    )

    val forward = com.readicop.lettor.ui.reader.computePdfSelectionGeometry(
      selection = com.readicop.lettor.ui.reader.PdfWordSelection(pageIndex = 0, anchorWordA = 0, anchorWordB = 1),
      pageText = pageText,
      metrics = metrics,
      overlayContainerPosInRoot = androidx.compose.ui.geometry.Offset.Zero
    )
    val startAnchor = requireNotNull(forward.anchorAPos)
    val endAnchor = requireNotNull(forward.anchorBPos)
    assertEquals(100f, startAnchor.x, 0.5f)
    assertEquals(250f, startAnchor.y, 0.5f)
    assertEquals(500f, endAnchor.x, 0.5f)
    assertEquals(250f, endAnchor.y, 0.5f)

    // Reversed anchors swap which handle attaches to left vs right while staying on bottom edge
    val backward = com.readicop.lettor.ui.reader.computePdfSelectionGeometry(
      selection = com.readicop.lettor.ui.reader.PdfWordSelection(pageIndex = 0, anchorWordA = 1, anchorWordB = 0),
      pageText = pageText,
      metrics = metrics,
      overlayContainerPosInRoot = androidx.compose.ui.geometry.Offset.Zero
    )
    val revStartAnchor = requireNotNull(backward.anchorAPos)
    val revEndAnchor = requireNotNull(backward.anchorBPos)
    assertEquals(500f, revStartAnchor.x, 0.5f)
    assertEquals(250f, revStartAnchor.y, 0.5f)
    assertEquals(100f, revEndAnchor.x, 0.5f)
    assertEquals(250f, revEndAnchor.y, 0.5f)
  }

  @Test
  fun `ime back intercept helper hides keyboard only when ime height is positive`() {
    assertTrue(com.readicop.lettor.ui.components.shouldHideImeInsteadOfDismissing(420))
    assertFalse(com.readicop.lettor.ui.components.shouldHideImeInsteadOfDismissing(0))
  }

  @Test
  fun `reader preferences persist font sizes below 14sp down to 8sp and brightness down to 1 percent`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repo = com.readicop.lettor.data.preferences.ReaderPreferencesRepository(context)
    kotlinx.coroutines.runBlocking {
      repo.setFontSizeSp(10f)
      val after10 = repo.preferencesFlow.first()
      assertEquals(10f, after10.fontSizeSp, 0.01f)

      repo.setFontSizeSp(5f) // clamped at MIN_READER_FONT_SIZE_SP (8f)
      val afterMin = repo.preferencesFlow.first()
      assertEquals(com.readicop.lettor.data.preferences.MIN_READER_FONT_SIZE_SP, afterMin.fontSizeSp, 0.01f)

      repo.setReaderBrightness(0.02f)
      val after2Pct = repo.preferencesFlow.first()
      assertEquals(0.02f, after2Pct.readerBrightness, 0.001f)

      repo.setReaderBrightness(0.0f) // clamped at MIN_READER_BRIGHTNESS (0.01f), not stuck at 0.05f
      val afterMinBright = repo.preferencesFlow.first()
      assertEquals(com.readicop.lettor.data.preferences.MIN_READER_BRIGHTNESS, afterMinBright.readerBrightness, 0.001f)

      repo.setReaderBrightness(-1f) // Auto
      val afterAuto = repo.preferencesFlow.first()
      assertEquals(-1f, afterAuto.readerBrightness, 0.001f)
      assertEquals(
        android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
        com.readicop.lettor.ui.reader.resolveWindowBrightness(afterAuto.readerBrightness),
        0.001f
      )
      assertEquals(
        0.02f,
        com.readicop.lettor.ui.reader.resolveWindowBrightness(0.02f),
        0.001f
      )
    }
  }

  @Test
  fun `epub boundary swipe advances directly to next chapter only from last page on forward gesture`() {
    assertEquals(
      com.readicop.lettor.ui.reader.EpubBoundaryAction.NEXT_CHAPTER,
      com.readicop.lettor.ui.reader.resolveEpubBoundarySwipe(
        startedOnFirstPage = false,
        startedOnLastPage = true,
        chapterIndex = 2,
        totalChapters = 5,
        dx = -180f,
        dy = 20f,
        swipeThresholdPx = 100f
      )
    )

    // Arriving at last page from previous page must NOT skip straight to next chapter
    assertEquals(
      com.readicop.lettor.ui.reader.EpubBoundaryAction.NONE,
      com.readicop.lettor.ui.reader.resolveEpubBoundarySwipe(
        startedOnFirstPage = false,
        startedOnLastPage = false,
        chapterIndex = 2,
        totalChapters = 5,
        dx = -220f,
        dy = 10f,
        swipeThresholdPx = 100f
      )
    )

    // Last chapter cannot advance further
    assertEquals(
      com.readicop.lettor.ui.reader.EpubBoundaryAction.NONE,
      com.readicop.lettor.ui.reader.resolveEpubBoundarySwipe(
        startedOnFirstPage = false,
        startedOnLastPage = true,
        chapterIndex = 4,
        totalChapters = 5,
        dx = -220f,
        dy = 0f,
        swipeThresholdPx = 100f
      )
    )

    // Vertical scroll or tiny drag does not advance
    assertEquals(
      com.readicop.lettor.ui.reader.EpubBoundaryAction.NONE,
      com.readicop.lettor.ui.reader.resolveEpubBoundarySwipe(
        startedOnFirstPage = false,
        startedOnLastPage = true,
        chapterIndex = 1,
        totalChapters = 5,
        dx = -150f,
        dy = 180f,
        swipeThresholdPx = 100f
      )
    )

    // Backward swipe on first page goes to previous chapter
    assertEquals(
      com.readicop.lettor.ui.reader.EpubBoundaryAction.PREVIOUS_CHAPTER,
      com.readicop.lettor.ui.reader.resolveEpubBoundarySwipe(
        startedOnFirstPage = true,
        startedOnLastPage = false,
        chapterIndex = 2,
        totalChapters = 5,
        dx = 180f,
        dy = 15f,
        swipeThresholdPx = 100f
      )
    )
  }

  @Test
  fun `epub vertical boundary scroll transitions directly between chapters only at scroll limits`() {
    assertEquals(
      com.readicop.lettor.ui.reader.EpubBoundaryAction.NEXT_CHAPTER,
      com.readicop.lettor.ui.reader.resolveEpubVerticalBoundaryScroll(
        startedAtTop = false,
        startedAtBottom = true,
        stillAtTop = false,
        stillAtBottom = true,
        chapterIndex = 1,
        totalChapters = 4,
        dx = 12f,
        dy = -160f,
        swipeThresholdPx = 90f
      )
    )

    // Scrolling to reach bottom mid-gesture must stop at end of chapter first
    assertEquals(
      com.readicop.lettor.ui.reader.EpubBoundaryAction.NONE,
      com.readicop.lettor.ui.reader.resolveEpubVerticalBoundaryScroll(
        startedAtTop = false,
        startedAtBottom = false,
        stillAtTop = false,
        stillAtBottom = true,
        chapterIndex = 1,
        totalChapters = 4,
        dx = 0f,
        dy = -220f,
        swipeThresholdPx = 90f
      )
    )

    // Horizontal swipe or short drag must not change chapter
    assertEquals(
      com.readicop.lettor.ui.reader.EpubBoundaryAction.NONE,
      com.readicop.lettor.ui.reader.resolveEpubVerticalBoundaryScroll(
        startedAtTop = false,
        startedAtBottom = true,
        stillAtTop = false,
        stillAtBottom = true,
        chapterIndex = 1,
        totalChapters = 4,
        dx = 220f,
        dy = -110f,
        swipeThresholdPx = 90f
      )
    )

    // Pulling down at top goes back to previous chapter
    assertEquals(
      com.readicop.lettor.ui.reader.EpubBoundaryAction.PREVIOUS_CHAPTER,
      com.readicop.lettor.ui.reader.resolveEpubVerticalBoundaryScroll(
        startedAtTop = true,
        startedAtBottom = false,
        stillAtTop = true,
        stillAtBottom = false,
        chapterIndex = 2,
        totalChapters = 4,
        dx = 10f,
        dy = 150f,
        swipeThresholdPx = 90f
      )
    )
  }

  @Test
  fun `epub pages cache reuses computed chapter pages across switches unless layout changes`() {
    val cache = com.readicop.lettor.ui.reader.EpubPagesCache<String>()
    val layoutA = com.readicop.lettor.ui.reader.EpubPaginationLayoutKey(
      availableWidthPx = 900,
      availableHeightPx = 1600,
      fontSizeSp = 16f,
      lineHeightMultiplier = 1.45f,
      readerFont = com.readicop.lettor.data.preferences.ReaderFont.LITERATA,
      horizontalPaddingDp = 22
    )
    var ch0Computations = 0
    var ch1Computations = 0

    val ch0First = cache.getOrCompute(0, layoutA) {
      ch0Computations++
      listOf("p0-0", "p0-1", "p0-2")
    }
    cache.getOrCompute(1, layoutA) {
      ch1Computations++
      listOf("p1-0", "p1-1")
    }
    val ch0Returned = cache.getOrCompute(0, layoutA) {
      ch0Computations++
      listOf("recomputed")
    }

    assertEquals(1, ch0Computations)
    assertEquals(1, ch1Computations)
    assertEquals(ch0First, ch0Returned)
    assertEquals(2, cache.cachedChapterCount)

    // Changing font size invalidates cached pagination cleanly
    val layoutB = layoutA.copy(fontSizeSp = 12f)
    cache.getOrCompute(0, layoutB) {
      ch0Computations++
      listOf("p0-smaller-font")
    }
    assertEquals(2, ch0Computations)
    assertEquals(1, cache.cachedChapterCount)
  }

  @Test
  fun `reader view model remembers and restores last read page per epub chapter when switching back`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val tempEpub = copySampleEpub(context)
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
      context,
      com.readicop.lettor.data.local.LettorDatabase::class.java
    ).allowMainThreadQueries().build()

    try {
      val prefsRepo = com.readicop.lettor.data.preferences.ReaderPreferencesRepository(context)
      val repo = com.readicop.lettor.data.repository.LibraryRepository(
        appContext = context,
        bookDao = db.bookDao(),
        bookmarkDao = db.bookmarkDao(),
        highlightDao = db.highlightDao(),
        vocabularyDao = db.vocabularyDao(),
        importer = com.readicop.lettor.domain.engine.BookImporter(context),
        backupManager = com.readicop.lettor.domain.engine.BackupManager(
          context = context,
          bookDao = db.bookDao(),
          bookmarkDao = db.bookmarkDao(),
          highlightDao = db.highlightDao(),
          vocabularyDao = db.vocabularyDao()
        ),
        preferencesRepository = prefsRepo
      )

      val bookId = kotlinx.coroutines.runBlocking {
        db.bookDao().insertBook(
          com.readicop.lettor.data.local.BookEntity(
            title = "Don Quijote",
            author = "Cervantes",
            format = com.readicop.lettor.data.local.BookFormat.EPUB,
            filePath = tempEpub.absolutePath,
            totalUnits = 4,
            currentUnit = 0,
            currentSubOffset = 0
          )
        )
      }

      runCatching {
        androidx.work.WorkManager.initialize(
          context,
          androidx.work.Configuration.Builder().build()
        )
      }

      val vm = com.readicop.lettor.ui.reader.ReaderViewModel(
        appContext = context,
        bookId = bookId,
        initialUnit = -1,
        repository = repo,
        preferencesRepository = prefsRepo
      )

      var latestState = vm.uiState.value
      val collectJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
        vm.uiState.collect { latestState = it }
      }

      fun awaitState(
        timeoutMs: Long = 5000L,
        predicate: (com.readicop.lettor.ui.reader.ReaderUiState) -> Boolean
      ): com.readicop.lettor.ui.reader.ReaderUiState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
          org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
          val current = vm.uiState.value
          latestState = current
          if (predicate(current)) return current
          Thread.sleep(15L)
        }
        throw AssertionError("Timed out waiting for ReaderUiState; last=$latestState")
      }

      try {
        val loaded = awaitState {
          it.contentState is com.readicop.lettor.ui.reader.ReaderContentState.EpubReady
        }
        assertEquals(0, (loaded.contentState as com.readicop.lettor.ui.reader.ReaderContentState.EpubReady).activeChapter.index)

        // User reads Chapter 1 (index 0) up to page 15 (0-based index 14)
        vm.onPositionChanged(unitIndex = 0, subOffset = 14, unitProgressFraction = 0.2f)
        assertEquals(14, vm.rememberedSubOffsetForChapter(0))

        // Advance to Chapter 2 (index 1) and read up to page 3 (0-based index 2)
        vm.jumpToUnit(1)
        val atCh1 = awaitState {
          val c = it.contentState as? com.readicop.lettor.ui.reader.ReaderContentState.EpubReady
          c != null && c.activeChapter.index == 1
        }
        assertEquals(0, atCh1.currentSubOffset)
        vm.onPositionChanged(unitIndex = 1, subOffset = 2, unitProgressFraction = 0.4f)
        assertEquals(2, vm.rememberedSubOffsetForChapter(1))

        // Return to Chapter 1 (index 0): must restore page 15 (0-based 14), NOT reset to 0
        vm.jumpToUnit(0)
        val backAtCh0 = awaitState {
          val c = it.contentState as? com.readicop.lettor.ui.reader.ReaderContentState.EpubReady
          c != null && c.activeChapter.index == 0
        }
        assertEquals(14, backAtCh0.currentSubOffset)

        // Advancing back to Chapter 2 (index 1) restores page 3 (0-based 2)
        vm.jumpToUnit(1)
        val backAtCh1 = awaitState {
          val c = it.contentState as? com.readicop.lettor.ui.reader.ReaderContentState.EpubReady
          c != null && c.activeChapter.index == 1
        }
        assertEquals(2, backAtCh1.currentSubOffset)
      } finally {
        collectJob.cancel()
        vm.onReaderStopped()
      }
    } finally {
      db.close()
      tempEpub.delete()
    }
  }

  @Test
  fun `vertical epub cover detector matches cover-only chapters and ignores prose pages`() {
    val meta = com.readicop.lettor.domain.engine.EpubMetadata(
      title = "Don Quijote",
      author = "Cervantes",
      genre = "Clásicos",
      description = "",
      chapterCount = 4,
      coverZipPath = "images/cover.jpg"
    )
    val coverBlock = com.readicop.lettor.domain.engine.EpubBlock.Image(
      zipEntryPath = "images/cover.jpg",
      alt = "Portada"
    )
    val coverChapter = com.readicop.lettor.domain.engine.EpubChapter(
      index = 0,
      title = "Portada",
      zipPath = "text/cover.xhtml",
      blocks = listOf(
        coverBlock,
        com.readicop.lettor.domain.engine.EpubBlock.Paragraph(
          androidx.compose.ui.text.AnnotatedString("\u00A0"),
          "\u00A0"
        )
      ),
      plainText = ""
    )
    assertEquals(
      coverBlock,
      com.readicop.lettor.ui.reader.findVerticalEpubCoverImageBlock(coverChapter, meta)
    )

    val textWithImageChapter = coverChapter.copy(
      index = 2,
      title = "Capítulo II",
      zipPath = "text/ch2.xhtml",
      blocks = listOf(
        com.readicop.lettor.domain.engine.EpubBlock.Image("images/ill1.png"),
        com.readicop.lettor.domain.engine.EpubBlock.Paragraph(
          androidx.compose.ui.text.AnnotatedString("En un lugar de la Mancha."),
          "En un lugar de la Mancha."
        )
      ),
      plainText = "En un lugar de la Mancha."
    )
    assertEquals(
      null,
      com.readicop.lettor.ui.reader.findVerticalEpubCoverImageBlock(textWithImageChapter, meta)
    )
  }

  @Test
  fun `vertical epub cover sizing preserves original aspect ratio and fits inside screen bounds`() {
    // Portrait 2:3 cover (2000x3000) inside portrait phone (340dp x 720dp)
    val (wPortrait, hPortrait) = com.readicop.lettor.ui.reader.computeFittedCoverDimensions(
      imageWidthPx = 2000,
      imageHeightPx = 3000,
      maxWidthDp = 340f,
      maxHeightDp = 720f
    )
    assertEquals(340f, wPortrait, 0.01f)
    assertEquals(510f, hPortrait, 0.01f)
    assertEquals(2f / 3f, wPortrait / hPortrait, 0.001f)

    // Large portrait cover inside short landscape viewport (680dp x 280dp): height-constrained, zero clipping
    val (wLandscape, hLandscape) = com.readicop.lettor.ui.reader.computeFittedCoverDimensions(
      imageWidthPx = 2400,
      imageHeightPx = 3600,
      maxWidthDp = 680f,
      maxHeightDp = 280f
    )
    assertTrue(wLandscape <= 680f)
    assertEquals(280f, hLandscape, 0.01f)
    assertEquals(2f / 3f, wLandscape / hLandscape, 0.001f)

    // Wide landscape cover (1600x1000) inside portrait viewport (360dp x 700dp)
    val (wWide, hWide) = com.readicop.lettor.ui.reader.computeFittedCoverDimensions(
      imageWidthPx = 1600,
      imageHeightPx = 1000,
      maxWidthDp = 360f,
      maxHeightDp = 700f
    )
    assertEquals(360f, wWide, 0.01f)
    assertEquals(225f, hWide, 0.01f)
    assertEquals(1.6f, wWide / hWide, 0.001f)
  }

  @Test
  fun `horizontal epub cover detector handles varied cover structures across books and skips non-cover pages`() {
    val meta = com.readicop.lettor.domain.engine.EpubMetadata(
      title = "La Regenta",
      author = "Clarín",
      genre = "Clásicos",
      description = "",
      chapterCount = 4,
      coverZipPath = "OEBPS/Images/0001.jpg"
    )
    val frontCoverImg = com.readicop.lettor.domain.engine.EpubBlock.Image("OEBPS/Images/0001.jpg")
    val frontCoverChapter0 = com.readicop.lettor.domain.engine.EpubChapter(
      index = 0,
      title = "Capítulo 1",
      zipPath = "OEBPS/Text/p001.xhtml",
      blocks = listOf(frontCoverImg),
      plainText = ""
    )
    assertEquals(
      frontCoverImg,
      com.readicop.lettor.ui.reader.findHorizontalEpubCoverImageBlock(frontCoverChapter0, meta)
    )

    val titlepageBlock = com.readicop.lettor.domain.engine.EpubBlock.Image("OEBPS/Images/front.png")
    val titlepageChapter = com.readicop.lettor.domain.engine.EpubChapter(
      index = 1,
      title = "Title Page",
      zipPath = "OEBPS/Text/titlepage.xhtml",
      blocks = listOf(titlepageBlock),
      plainText = ""
    )
    assertEquals(
      titlepageBlock,
      com.readicop.lettor.ui.reader.findHorizontalEpubCoverImageBlock(titlepageChapter, meta)
    )

    val metaMatchAtIndex1 = com.readicop.lettor.domain.engine.EpubChapter(
      index = 1,
      title = "Frontispicio",
      zipPath = "OEBPS/Text/front.xhtml",
      blocks = listOf(frontCoverImg),
      plainText = ""
    )
    assertEquals(
      frontCoverImg,
      com.readicop.lettor.ui.reader.findHorizontalEpubCoverImageBlock(metaMatchAtIndex1, meta)
    )

    val nonCoverIllustrationChapter = com.readicop.lettor.domain.engine.EpubChapter(
      index = 4,
      title = "Ilustración",
      zipPath = "OEBPS/Text/ill4.xhtml",
      blocks = listOf(com.readicop.lettor.domain.engine.EpubBlock.Image("OEBPS/Images/plate4.jpg")),
      plainText = ""
    )
    assertEquals(
      null,
      com.readicop.lettor.ui.reader.findHorizontalEpubCoverImageBlock(nonCoverIllustrationChapter, meta)
    )

    val proseWithImageChapter = com.readicop.lettor.domain.engine.EpubChapter(
      index = 0,
      title = "Portada",
      zipPath = "OEBPS/Text/cover.xhtml",
      blocks = listOf(
        frontCoverImg,
        com.readicop.lettor.domain.engine.EpubBlock.Paragraph(
          androidx.compose.ui.text.AnnotatedString("Texto introductorio."),
          "Texto introductorio."
        )
      ),
      plainText = "Texto introductorio."
    )
    assertEquals(
      null,
      com.readicop.lettor.ui.reader.findHorizontalEpubCoverImageBlock(proseWithImageChapter, meta)
    )
  }

  @Test
  fun `svg wrapped epub cover with title and desc parses cleanly without spurious text blocks`() {
    val tempEpub = File.createTempFile("svg_cover_book", ".epub")
    try {
      java.util.zip.ZipOutputStream(tempEpub.outputStream()).use { zip ->
        zip.putNextEntry(java.util.zip.ZipEntry("META-INF/container.xml"))
        zip.write(
          """
          <?xml version="1.0"?>
          <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
            <rootfiles>
              <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
            </rootfiles>
          </container>
          """.trimIndent().toByteArray()
        )
        zip.closeEntry()

        zip.putNextEntry(java.util.zip.ZipEntry("OEBPS/content.opf"))
        zip.write(
          """
          <?xml version="1.0" encoding="utf-8"?>
          <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
              <dc:title>Libro SVG</dc:title>
              <dc:creator>Autor</dc:creator>
              <meta name="cover" content="Images/portada.png"/>
            </metadata>
            <manifest>
              <item id="img_cover" href="Images/portada.png" media-type="image/png"/>
              <item id="titlepage" href="Text/titlepage.xhtml" media-type="application/xhtml+xml"/>
              <item id="ch1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
            </manifest>
            <spine>
              <itemref idref="titlepage"/>
              <itemref idref="ch1"/>
            </spine>
          </package>
          """.trimIndent().toByteArray()
        )
        zip.closeEntry()

        zip.putNextEntry(java.util.zip.ZipEntry("OEBPS/Text/titlepage.xhtml"))
        zip.write(
          """
          <?xml version="1.0" encoding="utf-8"?>
          <html xmlns="http://www.w3.org/1999/xhtml">
            <head><title>Cover</title></head>
            <body>
              <div>
                <svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 600 900">
                  <title>Cover Image</title>
                  <desc>Cubierta del libro</desc>
                  <image width="600" height="900" xlink:href="../Images/portada.png"/>
                </svg>
              </div>
            </body>
          </html>
          """.trimIndent().toByteArray()
        )
        zip.closeEntry()

        zip.putNextEntry(java.util.zip.ZipEntry("OEBPS/Text/ch1.xhtml"))
        zip.write("<html><body><p>Primer capítulo.</p></body></html>".toByteArray())
        zip.closeEntry()

        zip.putNextEntry(java.util.zip.ZipEntry("OEBPS/Images/portada.png"))
        zip.write(byteArrayOf(1, 2, 3, 4))
        zip.closeEntry()
      }

      val doc = com.readicop.lettor.domain.engine.EpubParser.parseDocument(tempEpub)
      assertEquals("OEBPS/Images/portada.png", doc.metadata.coverZipPath)
      val detected = com.readicop.lettor.ui.reader.findHorizontalEpubCoverImageBlock(
        doc.chapters.first(),
        doc.metadata
      )
      assertEquals("OEBPS/Images/portada.png", detected?.zipEntryPath)
    } finally {
      tempEpub.delete()
    }
  }

  @Test
  fun `vertical epub scroll restore helper returns exact offset on same layout and adapts across font changes`() {
    val layout16 = com.readicop.lettor.ui.reader.EpubVerticalLayoutSignature(
      fontSizeSp = 16f,
      lineHeightMultiplier = 1.45f,
      readerFont = com.readicop.lettor.data.preferences.ReaderFont.LITERATA,
      horizontalPaddingDp = 22
    )
    val layout24 = layout16.copy(fontSizeSp = 24f)
    val saved = com.readicop.lettor.ui.reader.EpubVerticalScrollPosition(
      chapterId = "OEBPS/ch1.xhtml",
      chapterIndex = 0,
      blockIndex = 12,
      scrollOffsetPx = 185,
      blockScrollFraction = 0.50f,
      characterOffsetInBlock = 240,
      layoutSignature = layout16
    )

    // Same layout: exact pixel offset restored
    assertEquals(
      12 to 185,
      com.readicop.lettor.ui.reader.resolveVerticalScrollRestoreTarget(
        saved = saved,
        blockCount = 25,
        currentLayout = layout16
      )
    )

    // Font size changed & new character line top available: snaps to exact text line top
    assertEquals(
      12 to 312,
      com.readicop.lettor.ui.reader.resolveVerticalScrollRestoreTarget(
        saved = saved,
        blockCount = 25,
        currentLayout = layout24,
        newItemHeightPx = 640,
        newLineTopPx = 312
      )
    )

    // Font size changed without TextLayoutResult: falls back to proportional block offset
    assertEquals(
      12 to 300,
      com.readicop.lettor.ui.reader.resolveVerticalScrollRestoreTarget(
        saved = saved,
        blockCount = 25,
        currentLayout = layout24,
        newItemHeightPx = 600,
        newLineTopPx = null
      )
    )
  }

  @Test
  fun `reader view model remembers and restores exact vertical epub scroll position per unique chapter id`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val tempEpub = copySampleEpub(context)
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
      context,
      com.readicop.lettor.data.local.LettorDatabase::class.java
    ).allowMainThreadQueries().build()

    try {
      val prefsRepo = com.readicop.lettor.data.preferences.ReaderPreferencesRepository(context)
      kotlinx.coroutines.runBlocking {
        prefsRepo.setEpubScrollMode(true)
      }
      val repo = com.readicop.lettor.data.repository.LibraryRepository(
        appContext = context,
        bookDao = db.bookDao(),
        bookmarkDao = db.bookmarkDao(),
        highlightDao = db.highlightDao(),
        vocabularyDao = db.vocabularyDao(),
        importer = com.readicop.lettor.domain.engine.BookImporter(context),
        backupManager = com.readicop.lettor.domain.engine.BackupManager(
          context = context,
          bookDao = db.bookDao(),
          bookmarkDao = db.bookmarkDao(),
          highlightDao = db.highlightDao(),
          vocabularyDao = db.vocabularyDao()
        ),
        preferencesRepository = prefsRepo
      )

      val bookId = kotlinx.coroutines.runBlocking {
        db.bookDao().insertBook(
          com.readicop.lettor.data.local.BookEntity(
            title = "Don Quijote",
            author = "Cervantes",
            format = com.readicop.lettor.data.local.BookFormat.EPUB,
            filePath = tempEpub.absolutePath,
            totalUnits = 4,
            currentUnit = 0,
            currentSubOffset = 0
          )
        )
      }

      runCatching {
        androidx.work.WorkManager.initialize(
          context,
          androidx.work.Configuration.Builder().build()
        )
      }

      val vm = com.readicop.lettor.ui.reader.ReaderViewModel(
        appContext = context,
        bookId = bookId,
        initialUnit = -1,
        repository = repo,
        preferencesRepository = prefsRepo
      )

      var latestState = vm.uiState.value
      val collectJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
        vm.uiState.collect { latestState = it }
      }

      fun awaitState(
        timeoutMs: Long = 5000L,
        predicate: (com.readicop.lettor.ui.reader.ReaderUiState) -> Boolean
      ): com.readicop.lettor.ui.reader.ReaderUiState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
          org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
          val current = vm.uiState.value
          latestState = current
          if (predicate(current)) return current
          Thread.sleep(15L)
        }
        throw AssertionError("Timed out waiting for ReaderUiState; last=$latestState")
      }

      try {
        val loaded = awaitState {
          it.contentState is com.readicop.lettor.ui.reader.ReaderContentState.EpubReady &&
            it.preferences.epubScrollMode
        }
        val ch0 = (loaded.contentState as com.readicop.lettor.ui.reader.ReaderContentState.EpubReady).activeChapter
        val ch0Id = com.readicop.lettor.ui.reader.epubChapterUniqueId(ch0)
        val layout16 = com.readicop.lettor.ui.reader.EpubVerticalLayoutSignature(
          fontSizeSp = loaded.preferences.fontSizeSp,
          lineHeightMultiplier = loaded.preferences.lineHeightMultiplier,
          readerFont = loaded.preferences.readerFont,
          horizontalPaddingDp = loaded.preferences.horizontalPaddingDp
        )

        val ch0Pos = com.readicop.lettor.ui.reader.EpubVerticalScrollPosition(
          chapterId = ch0Id,
          chapterIndex = 0,
          blockIndex = 6,
          scrollOffsetPx = 240,
          blockScrollFraction = 0.6f,
          characterOffsetInBlock = 150,
          layoutSignature = layout16
        )
        vm.onVerticalPositionChanged(ch0, ch0Pos, 0.25f)

        // Advance to Chapter 2 (index 1) and scroll to its own position
        vm.jumpToUnit(1)
        val atCh1 = awaitState {
          val c = it.contentState as? com.readicop.lettor.ui.reader.ReaderContentState.EpubReady
          c != null && c.activeChapter.index == 1
        }
        val ch1 = (atCh1.contentState as com.readicop.lettor.ui.reader.ReaderContentState.EpubReady).activeChapter
        val ch1Id = com.readicop.lettor.ui.reader.epubChapterUniqueId(ch1)
        assertEquals(null, atCh1.verticalChapterPositions[ch1Id])

        val ch1Pos = com.readicop.lettor.ui.reader.EpubVerticalScrollPosition(
          chapterId = ch1Id,
          chapterIndex = 1,
          blockIndex = 3,
          scrollOffsetPx = 95,
          blockScrollFraction = 0.25f,
          characterOffsetInBlock = 70,
          layoutSignature = layout16
        )
        vm.onVerticalPositionChanged(ch1, ch1Pos, 0.5f)

        // Return to Chapter 1: exact vertical position (unique chapter ID, block 6, 240px, char 150) is restored
        vm.jumpToUnit(0)
        val backAtCh0 = awaitState {
          val c = it.contentState as? com.readicop.lettor.ui.reader.ReaderContentState.EpubReady
          c != null && c.activeChapter.index == 0
        }
        val restoredCh0 = requireNotNull(backAtCh0.verticalChapterPositions[ch0Id])
        assertEquals(6, restoredCh0.blockIndex)
        assertEquals(240, restoredCh0.scrollOffsetPx)
        assertEquals(150, restoredCh0.characterOffsetInBlock)

        // Advance again to Chapter 2: its exact saved vertical position is preserved too
        vm.jumpToUnit(1)
        val backAtCh1 = awaitState {
          val c = it.contentState as? com.readicop.lettor.ui.reader.ReaderContentState.EpubReady
          c != null && c.activeChapter.index == 1
        }
        val restoredCh1 = requireNotNull(backAtCh1.verticalChapterPositions[ch1Id])
        assertEquals(3, restoredCh1.blockIndex)
        assertEquals(95, restoredCh1.scrollOffsetPx)
      } finally {
        collectJob.cancel()
        vm.onReaderStopped()
      }
    } finally {
      db.close()
      tempEpub.delete()
    }
  }

  @Test
  fun `horizontal epub semantic restore finds exact block and character page across font changes`() {
    val layout16 = com.readicop.lettor.ui.reader.EpubPaginationLayoutKey(
      availableWidthPx = 900,
      availableHeightPx = 1500,
      fontSizeSp = 16f,
      lineHeightMultiplier = 1.45f,
      readerFont = com.readicop.lettor.data.preferences.ReaderFont.LITERATA,
      horizontalPaddingDp = 22
    )
    val layout24 = layout16.copy(fontSizeSp = 24f)

    val pagesAt16 = listOf(
      com.readicop.lettor.ui.reader.EpubRenderedPage(
        listOf(
          com.readicop.lettor.ui.reader.PageSliceItem.HeadingSlice(0, 1, "Capítulo I"),
          com.readicop.lettor.ui.reader.PageSliceItem.TextSlice(
            blockIndex = 1,
            text = androidx.compose.ui.text.AnnotatedString("Primera mitad del párrafo"),
            fullBlockText = "Primera mitad del párrafo y segunda mitad del párrafo.",
            startCharOffsetInBlock = 0
          )
        )
      ),
      com.readicop.lettor.ui.reader.EpubRenderedPage(
        listOf(
          com.readicop.lettor.ui.reader.PageSliceItem.TextSlice(
            blockIndex = 1,
            text = androidx.compose.ui.text.AnnotatedString("y segunda mitad del párrafo."),
            fullBlockText = "Primera mitad del párrafo y segunda mitad del párrafo.",
            startCharOffsetInBlock = 26
          )
        )
      )
    )

    val anchorOnPage1 = com.readicop.lettor.ui.reader.extractHorizontalAnchorForPage(pagesAt16, 1)
    assertEquals(1 to 26, anchorOnPage1)

    val savedPos = com.readicop.lettor.ui.reader.EpubHorizontalPosition(
      chapterId = "OEBPS/ch1.xhtml",
      chapterIndex = 0,
      subPage = 1,
      anchorBlockIndex = anchorOnPage1.first,
      anchorCharOffsetInBlock = anchorOnPage1.second,
      chapterProgressFraction = 1f,
      hasSemanticAnchor = true,
      layoutKey = layout16
    )

    // Same layout restores subPage 1 directly
    assertEquals(
      1,
      com.readicop.lettor.ui.reader.resolveHorizontalPageRestoreTarget(
        saved = savedPos,
        pages = pagesAt16,
        currentLayoutKey = layout16
      )
    )

    // Larger font splits into 4 pages: charOffset 26 in block 1 now sits on page index 2
    val pagesAt24 = listOf(
      com.readicop.lettor.ui.reader.EpubRenderedPage(
        listOf(com.readicop.lettor.ui.reader.PageSliceItem.HeadingSlice(0, 1, "Capítulo I"))
      ),
      com.readicop.lettor.ui.reader.EpubRenderedPage(
        listOf(
          com.readicop.lettor.ui.reader.PageSliceItem.TextSlice(
            blockIndex = 1,
            text = androidx.compose.ui.text.AnnotatedString("Primera mitad "),
            fullBlockText = "Primera mitad del párrafo y segunda mitad del párrafo.",
            startCharOffsetInBlock = 0
          )
        )
      ),
      com.readicop.lettor.ui.reader.EpubRenderedPage(
        listOf(
          com.readicop.lettor.ui.reader.PageSliceItem.TextSlice(
            blockIndex = 1,
            text = androidx.compose.ui.text.AnnotatedString("del párrafo y segunda "),
            fullBlockText = "Primera mitad del párrafo y segunda mitad del párrafo.",
            startCharOffsetInBlock = 14
          )
        )
      ),
      com.readicop.lettor.ui.reader.EpubRenderedPage(
        listOf(
          com.readicop.lettor.ui.reader.PageSliceItem.TextSlice(
            blockIndex = 1,
            text = androidx.compose.ui.text.AnnotatedString("mitad del párrafo."),
            fullBlockText = "Primera mitad del párrafo y segunda mitad del párrafo.",
            startCharOffsetInBlock = 36
          )
        )
      )
    )

    assertEquals(
      2,
      com.readicop.lettor.ui.reader.resolveHorizontalPageRestoreTarget(
        saved = savedPos,
        pages = pagesAt24,
        currentLayoutKey = layout24
      )
    )
  }

  @Test
  fun `vertical and horizontal boundary guards block accidental chapter chaining during scroll`() {
    // In-list scroll consumed during gesture must never trigger a chapter jump even at bottom
    assertEquals(
      com.readicop.lettor.ui.reader.EpubBoundaryAction.NONE,
      com.readicop.lettor.ui.reader.resolveEpubVerticalBoundaryScroll(
        startedAtTop = false,
        startedAtBottom = true,
        stillAtTop = false,
        stillAtBottom = true,
        chapterIndex = 1,
        totalChapters = 4,
        dx = 0f,
        dy = -240f,
        swipeThresholdPx = 150f,
        gestureScrolledInsideList = true,
        gestureStartedScrollInProgress = false,
        overscrollAccumulatedY = -200f
      )
    )

    // Gesture begun while fling/scroll momentum was still active stops cleanly at chapter boundary
    assertEquals(
      com.readicop.lettor.ui.reader.EpubBoundaryAction.NONE,
      com.readicop.lettor.ui.reader.resolveEpubVerticalBoundaryScroll(
        startedAtTop = false,
        startedAtBottom = true,
        stillAtTop = false,
        stillAtBottom = true,
        chapterIndex = 1,
        totalChapters = 4,
        dx = 0f,
        dy = -240f,
        swipeThresholdPx = 150f,
        gestureScrolledInsideList = false,
        gestureStartedScrollInProgress = true,
        overscrollAccumulatedY = -240f
      )
    )

    // Horizontal swipe while pager changed page or is mid-scroll must not jump chapters
    assertEquals(
      com.readicop.lettor.ui.reader.EpubBoundaryAction.NONE,
      com.readicop.lettor.ui.reader.resolveEpubBoundarySwipe(
        startedOnFirstPage = true,
        startedOnLastPage = false,
        chapterIndex = 2,
        totalChapters = 4,
        dx = 220f,
        dy = 0f,
        swipeThresholdPx = 120f,
        pagerSettledOnStartPage = false,
        pagerScrollInProgress = false
      )
    )
  }

  @Test
  fun `reader view model preserves semantic horizontal position by unique chapter id and bridges modes`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val tempEpub = copySampleEpub(context)
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
      context,
      com.readicop.lettor.data.local.LettorDatabase::class.java
    ).allowMainThreadQueries().build()

    try {
      val prefsRepo = com.readicop.lettor.data.preferences.ReaderPreferencesRepository(context)
      kotlinx.coroutines.runBlocking {
        prefsRepo.setEpubScrollMode(false)
      }
      val repo = com.readicop.lettor.data.repository.LibraryRepository(
        appContext = context,
        bookDao = db.bookDao(),
        bookmarkDao = db.bookmarkDao(),
        highlightDao = db.highlightDao(),
        vocabularyDao = db.vocabularyDao(),
        importer = com.readicop.lettor.domain.engine.BookImporter(context),
        backupManager = com.readicop.lettor.domain.engine.BackupManager(
          context = context,
          bookDao = db.bookDao(),
          bookmarkDao = db.bookmarkDao(),
          highlightDao = db.highlightDao(),
          vocabularyDao = db.vocabularyDao()
        ),
        preferencesRepository = prefsRepo
      )

      val bookId = kotlinx.coroutines.runBlocking {
        db.bookDao().insertBook(
          com.readicop.lettor.data.local.BookEntity(
            title = "Don Quijote",
            author = "Cervantes",
            format = com.readicop.lettor.data.local.BookFormat.EPUB,
            filePath = tempEpub.absolutePath,
            totalUnits = 4,
            currentUnit = 0,
            currentSubOffset = 0
          )
        )
      }

      runCatching {
        androidx.work.WorkManager.initialize(
          context,
          androidx.work.Configuration.Builder().build()
        )
      }

      val vm = com.readicop.lettor.ui.reader.ReaderViewModel(
        appContext = context,
        bookId = bookId,
        initialUnit = -1,
        repository = repo,
        preferencesRepository = prefsRepo
      )

      var latestState = vm.uiState.value
      val collectJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
        vm.uiState.collect { latestState = it }
      }

      fun awaitState(
        timeoutMs: Long = 5000L,
        predicate: (com.readicop.lettor.ui.reader.ReaderUiState) -> Boolean
      ): com.readicop.lettor.ui.reader.ReaderUiState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
          org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
          val current = vm.uiState.value
          latestState = current
          if (predicate(current)) return current
          Thread.sleep(15L)
        }
        throw AssertionError("Timed out waiting for ReaderUiState; last=$latestState")
      }

      try {
        val loaded = awaitState {
          it.contentState is com.readicop.lettor.ui.reader.ReaderContentState.EpubReady
        }
        val ready0 = loaded.contentState as com.readicop.lettor.ui.reader.ReaderContentState.EpubReady
        val ch0 = ready0.activeChapter
        val ch0Id = com.readicop.lettor.ui.reader.epubChapterUniqueId(ch0, ready0.chapterStubs)
        val layout16 = com.readicop.lettor.ui.reader.EpubPaginationLayoutKey(
          availableWidthPx = 900,
          availableHeightPx = 1500,
          fontSizeSp = 16f,
          lineHeightMultiplier = 1.45f,
          readerFont = com.readicop.lettor.data.preferences.ReaderFont.LITERATA,
          horizontalPaddingDp = 22
        )

        val ch0Pos = com.readicop.lettor.ui.reader.EpubHorizontalPosition(
          chapterId = ch0Id,
          chapterIndex = 0,
          subPage = 7,
          anchorBlockIndex = 4,
          anchorCharOffsetInBlock = 112,
          chapterProgressFraction = 0.8f,
          hasSemanticAnchor = true,
          layoutKey = layout16
        )
        vm.onHorizontalPositionChanged(ch0, ch0Pos, 0.2f)

        // Advance to chapter 1 and save its position
        vm.jumpToUnit(1)
        val atCh1 = awaitState {
          val c = it.contentState as? com.readicop.lettor.ui.reader.ReaderContentState.EpubReady
          c != null && c.activeChapter.index == 1
        }
        val ch1 = (atCh1.contentState as com.readicop.lettor.ui.reader.ReaderContentState.EpubReady).activeChapter
        val ch1Id = com.readicop.lettor.ui.reader.epubChapterUniqueId(ch1, ready0.chapterStubs)
        vm.onHorizontalPositionChanged(
          ch1,
          com.readicop.lettor.ui.reader.EpubHorizontalPosition(
            chapterId = ch1Id,
            chapterIndex = 1,
            subPage = 2,
            anchorBlockIndex = 2,
            anchorCharOffsetInBlock = 45,
            chapterProgressFraction = 0.3f,
            hasSemanticAnchor = true,
            layoutKey = layout16
          ),
          0.4f
        )

        // Switch to vertical scroll mode while in chapter 1, then go back to chapter 0:
        // chapter 0's semantic anchor (block 4, char 112) must bridge into verticalChapterPositions!
        vm.setEpubScrollMode(true)
        awaitState { it.preferences.epubScrollMode }

        vm.jumpToUnit(0)
        val backCh0Vertical = awaitState {
          val c = it.contentState as? com.readicop.lettor.ui.reader.ReaderContentState.EpubReady
          c != null && c.activeChapter.index == 0
        }
        val bridged = requireNotNull(backCh0Vertical.verticalChapterPositions[ch0Id])
        assertEquals(4, bridged.blockIndex)
        assertEquals(112, bridged.characterOffsetInBlock)
      } finally {
        collectJob.cancel()
        vm.onReaderStopped()
      }
    } finally {
      db.close()
      tempEpub.delete()
    }
  }

  @Test
  fun `backward chapter boundary jumps open unvisited and visited chapters at end in both horizontal and vertical modes`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val tempEpub = copySampleEpub(context)
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
      context,
      com.readicop.lettor.data.local.LettorDatabase::class.java
    ).allowMainThreadQueries().build()

    try {
      val prefsRepo = com.readicop.lettor.data.preferences.ReaderPreferencesRepository(context)
      kotlinx.coroutines.runBlocking {
        prefsRepo.setEpubScrollMode(false)
      }
      val repo = com.readicop.lettor.data.repository.LibraryRepository(
        appContext = context,
        bookDao = db.bookDao(),
        bookmarkDao = db.bookmarkDao(),
        highlightDao = db.highlightDao(),
        vocabularyDao = db.vocabularyDao(),
        importer = com.readicop.lettor.domain.engine.BookImporter(context),
        backupManager = com.readicop.lettor.domain.engine.BackupManager(
          context = context,
          bookDao = db.bookDao(),
          bookmarkDao = db.bookmarkDao(),
          highlightDao = db.highlightDao(),
          vocabularyDao = db.vocabularyDao()
        ),
        preferencesRepository = prefsRepo
      )

      val bookId = kotlinx.coroutines.runBlocking {
        db.bookDao().insertBook(
          com.readicop.lettor.data.local.BookEntity(
            title = "Don Quijote",
            author = "Cervantes",
            format = com.readicop.lettor.data.local.BookFormat.EPUB,
            filePath = tempEpub.absolutePath,
            totalUnits = 4,
            currentUnit = 0,
            currentSubOffset = 0
          )
        )
      }

      runCatching {
        androidx.work.WorkManager.initialize(
          context,
          androidx.work.Configuration.Builder().build()
        )
      }

      val vm = com.readicop.lettor.ui.reader.ReaderViewModel(
        appContext = context,
        bookId = bookId,
        initialUnit = -1,
        repository = repo,
        preferencesRepository = prefsRepo
      )

      var latestState = vm.uiState.value
      val collectJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
        vm.uiState.collect { latestState = it }
      }

      fun awaitState(
        timeoutMs: Long = 5000L,
        predicate: (com.readicop.lettor.ui.reader.ReaderUiState) -> Boolean
      ): com.readicop.lettor.ui.reader.ReaderUiState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
          org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
          val current = vm.uiState.value
          latestState = current
          if (predicate(current)) return current
          Thread.sleep(15L)
        }
        throw AssertionError("Timed out waiting for ReaderUiState; last=$latestState")
      }

      try {
        val loaded = awaitState {
          it.contentState is com.readicop.lettor.ui.reader.ReaderContentState.EpubReady
        }
        val loadedReady = loaded.contentState as com.readicop.lettor.ui.reader.ReaderContentState.EpubReady
        val stubs = loadedReady.chapterStubs

        // Save an early offset in chapter 1 first, then jump straight to last chapter (index 3) via TOC/slider
        vm.onHorizontalPositionChanged(
          loadedReady.activeChapter,
          com.readicop.lettor.ui.reader.EpubHorizontalPosition(
            chapterId = com.readicop.lettor.ui.reader.epubChapterUniqueId(0, stubs[0].zipPath, stubs),
            chapterIndex = 0,
            subPage = 1,
            hasSemanticAnchor = true
          ),
          0.1f
        )
        vm.jumpToUnit(3)
        val atLast = awaitState {
          val c = it.contentState as? com.readicop.lettor.ui.reader.ReaderContentState.EpubReady
          c != null && c.activeChapter.index == 3
        }
        assertFalse((atLast.contentState as com.readicop.lettor.ui.reader.ReaderContentState.EpubReady).openAtEnd)

        // Walk backward through unvisited chapters 2 and 1, then visited chapter 0:
        // each boundary transition must open at end (openAtEnd = true) and clear openAtEnd once settled.
        for (targetIdx in listOf(2, 1, 0)) {
          vm.jumpToChapterAtBoundary(targetIdx, openAtEnd = true)
          val arrived = awaitState {
            val c = it.contentState as? com.readicop.lettor.ui.reader.ReaderContentState.EpubReady
            c != null && c.activeChapter.index == targetIdx
          }
          val ready = arrived.contentState as com.readicop.lettor.ui.reader.ReaderContentState.EpubReady
          assertTrue("Expected openAtEnd=true entering chapter $targetIdx backward", ready.openAtEnd)

          val chId = com.readicop.lettor.ui.reader.epubChapterUniqueId(ready.activeChapter, stubs)
          vm.onHorizontalPositionChanged(
            ready.activeChapter,
            com.readicop.lettor.ui.reader.EpubHorizontalPosition(
              chapterId = chId,
              chapterIndex = targetIdx,
              subPage = 9,
              anchorBlockIndex = 2,
              anchorCharOffsetInBlock = 0,
              chapterProgressFraction = 1f,
              hasSemanticAnchor = true
            ),
            0.5f
          )
          val settled = awaitState {
            val c = it.contentState as? com.readicop.lettor.ui.reader.ReaderContentState.EpubReady
            c != null && !c.openAtEnd
          }
          assertEquals(9, settled.currentSubOffset)
        }

        // Now verify the same backward boundary behavior in Vertical scroll mode + forward reset
        vm.setEpubScrollMode(true)
        awaitState { it.preferences.epubScrollMode }

        vm.jumpToUnit(3)
        awaitState {
          val c = it.contentState as? com.readicop.lettor.ui.reader.ReaderContentState.EpubReady
          c != null && c.activeChapter.index == 3
        }

        vm.jumpToChapterAtBoundary(2, openAtEnd = true)
        val verticalCh2 = awaitState {
          val c = it.contentState as? com.readicop.lettor.ui.reader.ReaderContentState.EpubReady
          c != null && c.activeChapter.index == 2
        }
        val verticalReady2 = verticalCh2.contentState as com.readicop.lettor.ui.reader.ReaderContentState.EpubReady
        assertTrue(verticalReady2.openAtEnd)

        val ch2Id = com.readicop.lettor.ui.reader.epubChapterUniqueId(verticalReady2.activeChapter, stubs)
        vm.onVerticalPositionChanged(
          verticalReady2.activeChapter,
          com.readicop.lettor.ui.reader.EpubVerticalScrollPosition(
            chapterId = ch2Id,
            chapterIndex = 2,
            blockIndex = (verticalReady2.activeChapter.blocks.lastIndex).coerceAtLeast(0),
            scrollOffsetPx = 420
          ),
          0.75f
        )
        awaitState {
          val c = it.contentState as? com.readicop.lettor.ui.reader.ReaderContentState.EpubReady
          c != null && !c.openAtEnd
        }

        // Forward boundary jump to chapter 3 opens cleanly at start (subOffset = 0, openAtEnd = false)
        vm.jumpToChapterAtBoundary(3, openAtEnd = false)
        val forwardCh3 = awaitState {
          val c = it.contentState as? com.readicop.lettor.ui.reader.ReaderContentState.EpubReady
          c != null && c.activeChapter.index == 3
        }
        assertEquals(0, forwardCh3.currentSubOffset)
        assertFalse((forwardCh3.contentState as com.readicop.lettor.ui.reader.ReaderContentState.EpubReady).openAtEnd)
      } finally {
        collectJob.cancel()
        vm.onReaderStopped()
      }
    } finally {
      db.close()
      tempEpub.delete()
    }
  }

  @Test
  fun `vertical mode supports both vertical pull and horizontal swipe at chapter boundaries`() {
    // At bottom of chapter, horizontal left swipe advances directly to next chapter
    assertEquals(
      com.readicop.lettor.ui.reader.EpubBoundaryAction.NEXT_CHAPTER,
      com.readicop.lettor.ui.reader.resolveEpubVerticalOrHorizontalBoundaryAction(
        startedAtTop = false,
        startedAtBottom = true,
        stillAtTop = false,
        stillAtBottom = true,
        chapterIndex = 1,
        totalChapters = 4,
        dx = -160f,
        dy = 10f,
        swipeThresholdPx = 100f,
        gestureScrolledInsideList = false,
        gestureStartedScrollInProgress = false,
        overscrollAccumulatedY = 0f
      )
    )

    // At top of chapter, horizontal right swipe retreats directly to previous chapter (at end)
    assertEquals(
      com.readicop.lettor.ui.reader.EpubBoundaryAction.PREVIOUS_CHAPTER,
      com.readicop.lettor.ui.reader.resolveEpubVerticalOrHorizontalBoundaryAction(
        startedAtTop = true,
        startedAtBottom = false,
        stillAtTop = true,
        stillAtBottom = false,
        chapterIndex = 2,
        totalChapters = 4,
        dx = 160f,
        dy = -10f,
        swipeThresholdPx = 100f,
        gestureScrolledInsideList = false,
        gestureStartedScrollInProgress = false,
        overscrollAccumulatedY = 0f
      )
    )

    // Internal scroll in progress still blocks horizontal or vertical boundary jumps
    assertEquals(
      com.readicop.lettor.ui.reader.EpubBoundaryAction.NONE,
      com.readicop.lettor.ui.reader.resolveEpubVerticalOrHorizontalBoundaryAction(
        startedAtTop = false,
        startedAtBottom = true,
        stillAtTop = false,
        stillAtBottom = true,
        chapterIndex = 1,
        totalChapters = 4,
        dx = -180f,
        dy = 0f,
        swipeThresholdPx = 100f,
        gestureScrolledInsideList = true,
        gestureStartedScrollInProgress = false,
        overscrollAccumulatedY = 0f
      )
    )
  }

  @Test
  fun `continuous vertical epub feed inserts chapter dividers between chapters and resolves block targets`() {
    val meta = com.readicop.lettor.domain.engine.EpubMetadata(
      title = "Obra",
      author = "Autor",
      genre = "Ficción",
      description = "",
      chapterCount = 3,
      coverZipPath = "images/cover.jpg"
    )
    val stubs = listOf(
      com.readicop.lettor.domain.engine.EpubChapterStub(0, "Portada", "cover.xhtml"),
      com.readicop.lettor.domain.engine.EpubChapterStub(1, "Capítulo 1", "ch1.xhtml"),
      com.readicop.lettor.domain.engine.EpubChapterStub(2, "Capítulo 2", "ch2.xhtml")
    )
    val ch0 = com.readicop.lettor.domain.engine.EpubChapter(
      index = 0,
      title = "Portada",
      zipPath = "cover.xhtml",
      blocks = listOf(com.readicop.lettor.domain.engine.EpubBlock.Image("images/cover.jpg")),
      plainText = ""
    )
    val ch1 = com.readicop.lettor.domain.engine.EpubChapter(
      index = 1,
      title = "Capítulo 1",
      zipPath = "ch1.xhtml",
      blocks = listOf(
        com.readicop.lettor.domain.engine.EpubBlock.Heading(1, "Capítulo 1"),
        com.readicop.lettor.domain.engine.EpubBlock.Paragraph(
          androidx.compose.ui.text.AnnotatedString("Párrafo inicial."),
          "Párrafo inicial."
        )
      ),
      plainText = "Capítulo 1\n\nPárrafo inicial."
    )

    val entries = com.readicop.lettor.ui.reader.buildVerticalScrollFeedEntries(
      chapterStubs = stubs,
      loadedChapters = mapOf(0 to ch0, 1 to ch1),
      metadata = meta
    )

    // Expected: CoverPage(0), ChapterDivider(1), BlockEntry(1,0), BlockEntry(1,1), ChapterDivider(2), LoadingPlaceholder(2)
    assertEquals(6, entries.size)
    assertTrue(entries[0] is com.readicop.lettor.ui.reader.EpubVerticalFeedEntry.CoverPage)
    assertTrue(entries[1] is com.readicop.lettor.ui.reader.EpubVerticalFeedEntry.ChapterDivider)
    assertEquals(1, entries[1].chapterIndex)
    assertTrue(entries[2] is com.readicop.lettor.ui.reader.EpubVerticalFeedEntry.BlockEntry)
    assertTrue(entries[4] is com.readicop.lettor.ui.reader.EpubVerticalFeedEntry.ChapterDivider)
    assertEquals(2, entries[4].chapterIndex)
    assertTrue(entries[5] is com.readicop.lettor.ui.reader.EpubVerticalFeedEntry.LoadingPlaceholder)

    assertEquals(0, com.readicop.lettor.ui.reader.resolveVerticalFeedIndex(entries, 0, 0))
    assertEquals(3, com.readicop.lettor.ui.reader.resolveVerticalFeedIndex(entries, 1, 1))
    assertEquals(5, com.readicop.lettor.ui.reader.resolveVerticalFeedIndex(entries, 2, 0))
  }

  @Test
  fun `horizontal boundary swipe succeeds on a single slightly diagonal swipe`() {
    assertEquals(
      com.readicop.lettor.ui.reader.EpubBoundaryAction.NEXT_CHAPTER,
      com.readicop.lettor.ui.reader.resolveEpubBoundarySwipe(
        startedOnFirstPage = false,
        startedOnLastPage = true,
        chapterIndex = 0,
        totalChapters = 5,
        dx = -110f,
        dy = 100f,
        swipeThresholdPx = 80f
      )
    )
    assertEquals(
      com.readicop.lettor.ui.reader.EpubBoundaryAction.PREVIOUS_CHAPTER,
      com.readicop.lettor.ui.reader.resolveEpubBoundarySwipe(
        startedOnFirstPage = true,
        startedOnLastPage = false,
        chapterIndex = 3,
        totalChapters = 5,
        dx = 110f,
        dy = -95f,
        swipeThresholdPx = 80f
      )
    )
  }

  @Test
  fun `continuous horizontal epub slots connect covers and chapter pages seamlessly and map flat indices`() {
    val meta = com.readicop.lettor.domain.engine.EpubMetadata(
      title = "Obra",
      author = "Autor",
      genre = "Ficción",
      description = "",
      chapterCount = 4,
      coverZipPath = "images/cover.jpg"
    )
    val stubs = listOf(
      com.readicop.lettor.domain.engine.EpubChapterStub(0, "Portada", "cover.xhtml"),
      com.readicop.lettor.domain.engine.EpubChapterStub(1, "Capítulo 1", "ch1.xhtml"),
      com.readicop.lettor.domain.engine.EpubChapterStub(2, "Capítulo 2", "ch2.xhtml"),
      com.readicop.lettor.domain.engine.EpubChapterStub(3, "Capítulo 3", "ch3.xhtml")
    )
    val ch0 = com.readicop.lettor.domain.engine.EpubChapter(
      index = 0,
      title = "Portada",
      zipPath = "cover.xhtml",
      blocks = listOf(com.readicop.lettor.domain.engine.EpubBlock.Image("images/cover.jpg")),
      plainText = ""
    )
    val ch1 = com.readicop.lettor.domain.engine.EpubChapter(
      index = 1,
      title = "Capítulo 1",
      zipPath = "ch1.xhtml",
      blocks = listOf(
        com.readicop.lettor.domain.engine.EpubBlock.Heading(1, "Capítulo 1")
      ),
      plainText = "Capítulo 1"
    )
    val ch2 = com.readicop.lettor.domain.engine.EpubChapter(
      index = 2,
      title = "Capítulo 2",
      zipPath = "ch2.xhtml",
      blocks = listOf(
        com.readicop.lettor.domain.engine.EpubBlock.Heading(1, "Capítulo 2")
      ),
      plainText = "Capítulo 2"
    )

    val fakePageA = com.readicop.lettor.ui.reader.EpubRenderedPage(
      listOf(com.readicop.lettor.ui.reader.PageSliceItem.HeadingSlice(0, 1, "A"))
    )
    val fakePageB = com.readicop.lettor.ui.reader.EpubRenderedPage(
      listOf(com.readicop.lettor.ui.reader.PageSliceItem.HeadingSlice(1, 1, "B"))
    )
    val slots = com.readicop.lettor.ui.reader.buildHorizontalPageSlots(
      chapterStubs = stubs,
      loadedChapters = mapOf(0 to ch0, 1 to ch1, 2 to ch2),
      metadata = meta
    ) { ch ->
      when (ch.index) {
        1 -> listOf(fakePageA, fakePageB)
        2 -> listOf(fakePageA, fakePageB, fakePageA)
        else -> listOf(fakePageA)
      }
    }

    // Slots: ch0 Cover(0), ch1 pages(1, 2), ch2 pages(3, 4, 5), ch3 Loading(6)
    assertEquals(7, slots.size)
    assertTrue(slots[0] is com.readicop.lettor.ui.reader.EpubHorizontalPageSlot.CoverPageSlot)
    assertTrue(slots[1] is com.readicop.lettor.ui.reader.EpubHorizontalPageSlot.ChapterPageSlot)
    assertEquals(1, slots[2].chapterIndex)
    assertEquals(1, (slots[2] as com.readicop.lettor.ui.reader.EpubHorizontalPageSlot.ChapterPageSlot).subPageIndex)
    assertEquals(2, slots[3].chapterIndex)
    assertEquals(0, (slots[3] as com.readicop.lettor.ui.reader.EpubHorizontalPageSlot.ChapterPageSlot).subPageIndex)
    assertTrue(slots[6] is com.readicop.lettor.ui.reader.EpubHorizontalPageSlot.LoadingSlot)

    // Flat lookup maps cover, exact sub-page, and end-of-chapter clamp across boundaries
    assertEquals(0, com.readicop.lettor.ui.reader.resolveHorizontalFlatPageIndex(slots, 0, 0))
    assertEquals(2, com.readicop.lettor.ui.reader.resolveHorizontalFlatPageIndex(slots, 1, 99))
    assertEquals(3, com.readicop.lettor.ui.reader.resolveHorizontalFlatPageIndex(slots, 2, 0))
    assertEquals(5, com.readicop.lettor.ui.reader.resolveHorizontalFlatPageIndex(slots, 2, 2))
    assertEquals(6, com.readicop.lettor.ui.reader.resolveHorizontalFlatPageIndex(slots, 3, 0))
  }

  @Test
  fun `vertical epub fling velocity scaling damps fast flicks gently in both directions`() {
    val downVelocity = 4000f
    val upVelocity = -4000f
    val dampedDown = com.readicop.lettor.ui.reader.scaleVerticalFlingVelocity(downVelocity)
    val dampedUp = com.readicop.lettor.ui.reader.scaleVerticalFlingVelocity(upVelocity)

    assertTrue("Expected gentle damping slightly below raw speed", dampedDown in 2200f..3200f)
    assertEquals(-dampedDown, dampedUp, 0.01f)
    assertEquals(0f, com.readicop.lettor.ui.reader.scaleVerticalFlingVelocity(0f), 0.01f)
  }

  @Test
  fun `onHorizontalPositionChanged switches active chapter seamlessly across continuous swipes`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val tempEpub = copySampleEpub(context)
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
      context,
      com.readicop.lettor.data.local.LettorDatabase::class.java
    ).allowMainThreadQueries().build()

    try {
      val prefsRepo = com.readicop.lettor.data.preferences.ReaderPreferencesRepository(context)
      kotlinx.coroutines.runBlocking {
        prefsRepo.setEpubScrollMode(false)
      }
      val repo = com.readicop.lettor.data.repository.LibraryRepository(
        appContext = context,
        bookDao = db.bookDao(),
        bookmarkDao = db.bookmarkDao(),
        highlightDao = db.highlightDao(),
        vocabularyDao = db.vocabularyDao(),
        importer = com.readicop.lettor.domain.engine.BookImporter(context),
        backupManager = com.readicop.lettor.domain.engine.BackupManager(
          context = context,
          bookDao = db.bookDao(),
          bookmarkDao = db.bookmarkDao(),
          highlightDao = db.highlightDao(),
          vocabularyDao = db.vocabularyDao()
        ),
        preferencesRepository = prefsRepo
      )
      val bookId = kotlinx.coroutines.runBlocking {
        db.bookDao().insertBook(
          com.readicop.lettor.data.local.BookEntity(
            title = "Don Quijote",
            author = "Cervantes",
            format = com.readicop.lettor.data.local.BookFormat.EPUB,
            filePath = tempEpub.absolutePath,
            totalUnits = 4,
            currentUnit = 0,
            currentSubOffset = 0
          )
        )
      }
      runCatching {
        androidx.work.WorkManager.initialize(
          context,
          androidx.work.Configuration.Builder().build()
        )
      }

      val vm = com.readicop.lettor.ui.reader.ReaderViewModel(
        appContext = context,
        bookId = bookId,
        initialUnit = 0,
        repository = repo,
        preferencesRepository = prefsRepo
      )

      var latestState = vm.uiState.value
      val collectJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
        vm.uiState.collect { latestState = it }
      }

      fun awaitState(
        timeoutMs: Long = 5000L,
        predicate: (com.readicop.lettor.ui.reader.ReaderUiState) -> Boolean
      ): com.readicop.lettor.ui.reader.ReaderUiState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
          org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
          val current = vm.uiState.value
          latestState = current
          if (predicate(current)) return current
          Thread.sleep(15L)
        }
        throw AssertionError("Timed out waiting for ReaderUiState; last=$latestState")
      }

      try {
        val readyState = awaitState {
          val c = it.contentState as? com.readicop.lettor.ui.reader.ReaderContentState.EpubReady
          c != null && c.loadedChapters.containsKey(1)
        }
        val ready = readyState.contentState as com.readicop.lettor.ui.reader.ReaderContentState.EpubReady
        val ch1 = ready.loadedChapters.getValue(1)
        val ch1Id = com.readicop.lettor.ui.reader.epubChapterUniqueId(ch1, ready.chapterStubs)

        vm.onHorizontalPositionChanged(
          chapter = ch1,
          position = com.readicop.lettor.ui.reader.EpubHorizontalPosition(
            chapterId = ch1Id,
            chapterIndex = 1,
            subPage = 0,
            hasSemanticAnchor = true
          ),
          overallProgressFraction = 0.25f
        )

        val updated = awaitState {
          val c = it.contentState as? com.readicop.lettor.ui.reader.ReaderContentState.EpubReady
          c != null && c.activeChapter.index == 1 && !c.openAtEnd
        }
        assertEquals(1, updated.currentUnit)
      } finally {
        collectJob.cancel()
        vm.onReaderStopped()
      }
    } finally {
      db.close()
      tempEpub.delete()
    }
  }

  @Test
  fun `horizontal epub tap zones divide left center and right accurately`() {
    val width = 1000f
    // Left 28% -> previous page
    assertEquals(
      com.readicop.lettor.ui.reader.EpubHorizontalTapAction.PREVIOUS_PAGE,
      com.readicop.lettor.ui.reader.resolveEpubHorizontalTapAction(120f, width)
    )
    assertEquals(
      com.readicop.lettor.ui.reader.EpubHorizontalTapAction.PREVIOUS_PAGE,
      com.readicop.lettor.ui.reader.resolveEpubHorizontalTapAction(279f, width)
    )
    // Center 28%..72% -> toggle controls
    assertEquals(
      com.readicop.lettor.ui.reader.EpubHorizontalTapAction.TOGGLE_CONTROLS,
      com.readicop.lettor.ui.reader.resolveEpubHorizontalTapAction(280f, width)
    )
    assertEquals(
      com.readicop.lettor.ui.reader.EpubHorizontalTapAction.TOGGLE_CONTROLS,
      com.readicop.lettor.ui.reader.resolveEpubHorizontalTapAction(500f, width)
    )
    assertEquals(
      com.readicop.lettor.ui.reader.EpubHorizontalTapAction.TOGGLE_CONTROLS,
      com.readicop.lettor.ui.reader.resolveEpubHorizontalTapAction(720f, width)
    )
    // Right >72% -> next page
    assertEquals(
      com.readicop.lettor.ui.reader.EpubHorizontalTapAction.NEXT_PAGE,
      com.readicop.lettor.ui.reader.resolveEpubHorizontalTapAction(721f, width)
    )
    assertEquals(
      com.readicop.lettor.ui.reader.EpubHorizontalTapAction.NEXT_PAGE,
      com.readicop.lettor.ui.reader.resolveEpubHorizontalTapAction(980f, width)
    )
    // Zero-width safety fallback
    assertEquals(
      com.readicop.lettor.ui.reader.EpubHorizontalTapAction.TOGGLE_CONTROLS,
      com.readicop.lettor.ui.reader.resolveEpubHorizontalTapAction(50f, 0f)
    )
  }

  @Test
  fun `epub screen tap zone uses tall centered vertical rectangle excluding top and bottom bands`() {
    val width = 1000f
    val height = 2000f
    val halfWidth = 150f // center X = 500 (350..650), Y in 16%..84% (320..1680)

    // Dead center and upper/lower middle inside vertical rectangle -> CENTER_RECT
    assertEquals(
      com.readicop.lettor.ui.reader.EpubScreenTapZone.CENTER_RECT,
      com.readicop.lettor.ui.reader.resolveEpubScreenTapZone(500f, 1000f, width, height, halfWidth)
    )
    assertEquals(
      com.readicop.lettor.ui.reader.EpubScreenTapZone.CENTER_RECT,
      com.readicop.lettor.ui.reader.resolveEpubScreenTapZone(600f, 450f, width, height, halfWidth)
    )
    assertEquals(
      com.readicop.lettor.ui.reader.resolveEpubScreenTapZone(420f, 1550f, width, height, halfWidth),
      com.readicop.lettor.ui.reader.EpubScreenTapZone.CENTER_RECT
    )
    // Top and bottom margins along center column -> NOT inside vertical rectangle
    assertEquals(
      com.readicop.lettor.ui.reader.EpubScreenTapZone.PREVIOUS_PAGE,
      com.readicop.lettor.ui.reader.resolveEpubScreenTapZone(480f, 200f, width, height, halfWidth)
    )
    assertEquals(
      com.readicop.lettor.ui.reader.EpubScreenTapZone.NEXT_PAGE,
      com.readicop.lettor.ui.reader.resolveEpubScreenTapZone(520f, 1850f, width, height, halfWidth)
    )
    // Left and right sides outside vertical rectangle
    assertEquals(
      com.readicop.lettor.ui.reader.EpubScreenTapZone.PREVIOUS_PAGE,
      com.readicop.lettor.ui.reader.resolveEpubScreenTapZone(120f, 1000f, width, height, halfWidth)
    )
    assertEquals(
      com.readicop.lettor.ui.reader.resolveEpubScreenTapZone(880f, 1000f, width, height, halfWidth),
      com.readicop.lettor.ui.reader.EpubScreenTapZone.NEXT_PAGE
    )
  }

  @Test
  fun `epub tap outcome dismisses open controls on outside tap instead of paging`() {
    assertEquals(
      com.readicop.lettor.ui.reader.EpubTapOutcome.DISMISS_CONTROLS,
      com.readicop.lettor.ui.reader.resolveEpubTapOutcome(
        com.readicop.lettor.ui.reader.EpubScreenTapZone.PREVIOUS_PAGE,
        controlsVisible = true
      )
    )
    assertEquals(
      com.readicop.lettor.ui.reader.EpubTapOutcome.DISMISS_CONTROLS,
      com.readicop.lettor.ui.reader.resolveEpubTapOutcome(
        com.readicop.lettor.ui.reader.EpubScreenTapZone.NEXT_PAGE,
        controlsVisible = true
      )
    )
    assertEquals(
      com.readicop.lettor.ui.reader.EpubTapOutcome.TOGGLE_CONTROLS,
      com.readicop.lettor.ui.reader.resolveEpubTapOutcome(
        com.readicop.lettor.ui.reader.EpubScreenTapZone.CENTER_RECT,
        controlsVisible = true
      )
    )
    assertEquals(
      com.readicop.lettor.ui.reader.EpubTapOutcome.NEXT_PAGE,
      com.readicop.lettor.ui.reader.resolveEpubTapOutcome(
        com.readicop.lettor.ui.reader.EpubScreenTapZone.NEXT_PAGE,
        controlsVisible = false
      )
    )
  }

  @Test
  fun `global epub page counters round trip cleanly across multiple chapters`() {
    val counts = listOf(1, 4, 3, 5) // total = 13 pages (0..12)
    var expectedGlobal = 0
    for (chIdx in counts.indices) {
      for (sub in 0 until counts[chIdx]) {
        assertEquals(
          expectedGlobal,
          com.readicop.lettor.ui.reader.resolveGlobalPageFromChapterCounts(counts, chIdx, sub)
        )
        assertEquals(
          chIdx to sub,
          com.readicop.lettor.ui.reader.resolveChapterAndSubPageForGlobalPage(counts, expectedGlobal)
        )
        expectedGlobal++
      }
    }
    assertEquals(3 to 4, com.readicop.lettor.ui.reader.resolveChapterAndSubPageForGlobalPage(counts, 999))
    assertEquals(0 to 0, com.readicop.lettor.ui.reader.resolveChapterAndSubPageForGlobalPage(counts, -3))
  }
}
