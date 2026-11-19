package com.readicop.lettor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.readicop.lettor.domain.engine.EpubParser
import com.readicop.lettor.domain.engine.LazyEpubSession
import com.readicop.lettor.domain.engine.LocalBookIntelligence
import com.readicop.lettor.domain.engine.LocalDictionaryAndTranslator
import java.io.File
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

      val ch1 = session.getChapter(1)
      assertTrue(ch1.plainText.contains("En un lugar de la Mancha"))

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
}
