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
}
