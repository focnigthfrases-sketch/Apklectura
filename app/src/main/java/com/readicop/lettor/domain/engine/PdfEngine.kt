package com.readicop.lettor.domain.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.Closeable
import java.io.File
import java.io.StringWriter
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class PdfNormalizedRect(
  val left: Float,
  val top: Float,
  val right: Float,
  val bottom: Float
) {
  val width: Float get() = (right - left).coerceAtLeast(0f)
  val height: Float get() = (bottom - top).coerceAtLeast(0f)
  val centerX: Float get() = (left + right) * 0.5f
  val centerY: Float get() = (top + bottom) * 0.5f
}

data class PdfGlyphWord(
  val index: Int,
  val text: String,
  val rect: PdfNormalizedRect,
  val charRects: List<PdfNormalizedRect>
)

data class PdfPageTextData(
  val pageIndex: Int,
  val words: List<PdfGlyphWord>,
  val isScanned: Boolean
)

class PdfBookSession(
  private val file: File,
  private val appContext: Context? = null
) : Closeable {
  private val mutex = Mutex()
  private val textMutex = Mutex()
  private var pfd: ParcelFileDescriptor? = null
  private var renderer: PdfRenderer? = null
  private var pdDocument: PDDocument? = null

  private val cache = object : LruCache<String, Bitmap>(6) {
    override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
      // Let GC reclaim evicted bitmaps safely without recycling while Compose might draw
    }
  }

  private val pageTextCache = LruCache<Int, PdfPageTextData>(16)

  suspend fun open(): Int = withContext(Dispatchers.IO) {
    mutex.withLock {
      if (renderer == null) {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        pfd = descriptor
        renderer = PdfRenderer(descriptor)
      }
      renderer?.pageCount ?: 0
    }
  }

  suspend fun renderPage(pageIndex: Int, targetWidthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
    val safeWidth = targetWidthPx.coerceIn(480, 2200)
    val cacheKey = "${pageIndex}_$safeWidth"
    cache.get(cacheKey)?.let { return@withContext it }

    mutex.withLock {
      val activeRenderer = renderer ?: run {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        pfd = descriptor
        PdfRenderer(descriptor).also { renderer = it }
      }
      if (pageIndex < 0 || pageIndex >= activeRenderer.pageCount) return@withLock null

      activeRenderer.openPage(pageIndex).use { page ->
        val aspectRatio = page.height.toFloat() / page.width.coerceAtLeast(1).toFloat()
        val targetHeight = (safeWidth * aspectRatio).toInt().coerceIn(480, 3400)
        val bitmap = Bitmap.createBitmap(safeWidth, targetHeight, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        cache.put(cacheKey, bitmap)
        bitmap
      }
    }
  }

  suspend fun extractPageText(pageIndex: Int): PdfPageTextData = withContext(Dispatchers.IO) {
    pageTextCache.get(pageIndex)?.let { return@withContext it }

    textMutex.withLock {
      pageTextCache.get(pageIndex)?.let { return@withLock it }

      val extracted = runCatching {
        ensurePdfBoxInitialized(appContext)
        val doc = pdDocument ?: PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).also {
          pdDocument = it
        }
        if (pageIndex < 0 || pageIndex >= doc.numberOfPages) {
          PdfPageTextData(pageIndex = pageIndex, words = emptyList(), isScanned = true)
        } else {
          extractWordsFromPdPage(doc, pageIndex)
        }
      }.getOrElse {
        PdfPageTextData(pageIndex = pageIndex, words = emptyList(), isScanned = true)
      }

      pageTextCache.put(pageIndex, extracted)
      extracted
    }
  }

  override fun close() {
    runCatching { renderer?.close() }
    runCatching { pfd?.close() }
    runCatching { pdDocument?.close() }
    renderer = null
    pfd = null
    pdDocument = null
    cache.evictAll()
    pageTextCache.evictAll()
  }

  companion object {
    @Volatile
    private var pdfBoxReady = false

    fun ensurePdfBoxInitialized(context: Context?) {
      if (pdfBoxReady) return
      if (context != null) {
        synchronized(this) {
          if (!pdfBoxReady) {
            runCatching {
              PDFBoxResourceLoader.init(context.applicationContext)
            }
            pdfBoxReady = true
          }
        }
      }
    }

    fun extractWordsFromPdPage(doc: PDDocument, pageIndex: Int): PdfPageTextData {
      val collectedWords = mutableListOf<PdfGlyphWord>()
      val wordBuilder = StringBuilder()
      val charRects = mutableListOf<PdfNormalizedRect>()
      var lastPos: TextPosition? = null

      fun flushWord() {
        val raw = wordBuilder.toString().trim()
        if (raw.isNotEmpty() && charRects.isNotEmpty()) {
          val minL = charRects.minOf { it.left }
          val minT = charRects.minOf { it.top }
          val maxR = charRects.maxOf { it.right }
          val maxB = charRects.maxOf { it.bottom }
          collectedWords.add(
            PdfGlyphWord(
              index = collectedWords.size,
              text = raw,
              rect = PdfNormalizedRect(minL, minT, maxR, maxB),
              charRects = charRects.toList()
            )
          )
        }
        wordBuilder.clear()
        charRects.clear()
        lastPos = null
      }

      val stripper = object : PDFTextStripper() {
        override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
          if (textPositions == null) return
          for (tp in textPositions) {
            val uni = tp.unicode ?: continue
            if (uni.isEmpty() || uni.all { it.isWhitespace() }) {
              flushWord()
              continue
            }

            val prev = lastPos
            if (prev != null) {
              val gap = tp.xDirAdj - (prev.xDirAdj + prev.widthDirAdj)
              val spaceThresh = maxOf(prev.widthOfSpace * 0.42f, prev.heightDir * 0.28f, 1.5f)
              val lineJump = abs(tp.yDirAdj - prev.yDirAdj) > maxOf(prev.heightDir * 0.7f, 4f)
              if (gap > spaceThresh || gap < -prev.widthDirAdj * 1.5f || lineJump) {
                flushWord()
              }
            }

            val pageW = tp.pageWidth.coerceAtLeast(1f)
            val pageH = tp.pageHeight.coerceAtLeast(1f)
            val left = (tp.xDirAdj / pageW).coerceIn(0f, 1f)
            val right = ((tp.xDirAdj + tp.widthDirAdj.coerceAtLeast(0.5f)) / pageW).coerceIn(left, 1f)
            val baseHeight = maxOf(tp.heightDir, tp.fontSizeInPt * 0.78f, 4f)
            val top = ((tp.yDirAdj - baseHeight * 1.08f) / pageH).coerceIn(0f, 1f)
            val bottom = ((tp.yDirAdj + baseHeight * 0.28f) / pageH).coerceIn(top, 1f)

            val subCount = uni.length.coerceAtLeast(1)
            val subWidth = (right - left) / subCount.toFloat()
            uni.forEachIndexed { idx, ch ->
              if (!ch.isWhitespace()) {
                wordBuilder.append(ch)
                val cLeft = (left + idx * subWidth).coerceIn(0f, 1f)
                val cRight = (cLeft + subWidth).coerceIn(cLeft, 1f)
                charRects.add(PdfNormalizedRect(cLeft, top, cRight, bottom))
              }
            }
            lastPos = tp
          }
          flushWord()
        }
      }.apply {
        sortByPosition = true
        startPage = pageIndex + 1
        endPage = pageIndex + 1
      }

      stripper.writeText(doc, StringWriter())
      flushWord()

      return PdfPageTextData(
        pageIndex = pageIndex,
        words = collectedWords,
        isScanned = collectedWords.isEmpty()
      )
    }

    fun inspectAndExtractCover(pdfFile: File, coverDestFile: File?): Int {
      return ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { pdfRenderer ->
          val count = pdfRenderer.pageCount
          if (count > 0 && coverDestFile != null) {
            pdfRenderer.openPage(0).use { page ->
              val width = 540
              val height = (width * (page.height.toFloat() / page.width.coerceAtLeast(1))).toInt()
                .coerceIn(400, 900)
              val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
              Canvas(bmp).drawColor(Color.WHITE)
              page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
              coverDestFile.outputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
              }
              bmp.recycle()
            }
          }
          count.coerceAtLeast(1)
        }
      }
    }
  }
}
