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

  fun contains(other: PdfNormalizedRect, epsilon: Float = 0.004f): Boolean =
    left <= other.left + epsilon &&
      top <= other.top + epsilon &&
      right >= other.right - epsilon &&
      bottom >= other.bottom - epsilon
}

data class PdfZoomTile(
  val pageIndex: Int,
  val region: PdfNormalizedRect,
  val renderedScale: Float,
  val bitmap: Bitmap
)

fun computePdfZoomTileRegion(
  viewportWidthPx: Float,
  viewportHeightPx: Float,
  pageWidthPx: Float,
  pageHeightPx: Float,
  scale: Float,
  panOffsetX: Float,
  panOffsetY: Float,
  overscanFraction: Float = 0.16f
): PdfNormalizedRect? {
  if (viewportWidthPx <= 0f || viewportHeightPx <= 0f || pageWidthPx <= 0f || pageHeightPx <= 0f || scale <= 1.05f) {
    return null
  }
  val vx0 = (0f - viewportWidthPx * 0.5f - panOffsetX) / scale
  val vy0 = (0f - viewportHeightPx * 0.5f - panOffsetY) / scale
  val vx1 = (viewportWidthPx - viewportWidthPx * 0.5f - panOffsetX) / scale
  val vy1 = (viewportHeightPx - viewportHeightPx * 0.5f - panOffsetY) / scale

  val rawL = ((vx0 + pageWidthPx * 0.5f) / pageWidthPx).coerceIn(0f, 1f)
  val rawT = ((vy0 + pageHeightPx * 0.5f) / pageHeightPx).coerceIn(0f, 1f)
  val rawR = ((vx1 + pageWidthPx * 0.5f) / pageWidthPx).coerceIn(0f, 1f)
  val rawB = ((vy1 + pageHeightPx * 0.5f) / pageHeightPx).coerceIn(0f, 1f)

  val spanW = (rawR - rawL).coerceAtLeast(0f)
  val spanH = (rawB - rawT).coerceAtLeast(0f)
  if (spanW <= 0.005f || spanH <= 0.005f) return null

  val padW = spanW * overscanFraction
  val padH = spanH * overscanFraction

  return PdfNormalizedRect(
    left = (rawL - padW).coerceIn(0f, 1f),
    top = (rawT - padH).coerceIn(0f, 1f),
    right = (rawR + padW).coerceIn(0f, 1f),
    bottom = (rawB + padH).coerceIn(0f, 1f)
  )
}

data class PdfRegionRenderSpec(
  val bitmapWidthPx: Int,
  val bitmapHeightPx: Int,
  val translatePtX: Float,
  val translatePtY: Float,
  val scaleX: Float,
  val scaleY: Float
)

fun computePdfRegionRenderSpec(
  pagePtWidth: Float,
  pagePtHeight: Float,
  region: PdfNormalizedRect,
  fullPageTargetWidthPx: Float,
  maxBitmapDimPx: Int = 2400
): PdfRegionRenderSpec? {
  val normW = region.width
  val normH = region.height
  if (normW <= 0.005f || normH <= 0.005f) return null

  val pagePtW = pagePtWidth.coerceAtLeast(1f)
  val pagePtH = pagePtHeight.coerceAtLeast(1f)
  val desiredPxPerPt = (fullPageTargetWidthPx / pagePtW).coerceAtLeast(0.5f)

  val regionPtL = region.left * pagePtW
  val regionPtT = region.top * pagePtH
  val regionPtW = normW * pagePtW
  val regionPtH = normH * pagePtH

  val rawBmpW = regionPtW * desiredPxPerPt
  val rawBmpH = regionPtH * desiredPxPerPt
  val maxDim = maxBitmapDimPx.coerceAtLeast(64).toFloat()
  val capFactor = minOf(1f, maxDim / maxOf(rawBmpW, rawBmpH, 1f))

  val bmpW = (rawBmpW * capFactor).toInt().coerceIn(64, maxBitmapDimPx)
  val bmpH = (rawBmpH * capFactor).toInt().coerceIn(64, maxBitmapDimPx)

  return PdfRegionRenderSpec(
    bitmapWidthPx = bmpW,
    bitmapHeightPx = bmpH,
    translatePtX = -regionPtL,
    translatePtY = -regionPtT,
    scaleX = bmpW.toFloat() / regionPtW,
    scaleY = bmpH.toFloat() / regionPtH
  )
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

data class PdfChapterOutlineItem(
  val index: Int,
  val title: String,
  val pageIndex: Int,
  val level: Int = 0
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
    val safeWidth = targetWidthPx.coerceIn(480, 2400)
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
        val targetHeight = (safeWidth * aspectRatio).toInt().coerceIn(480, 3600)
        val bitmap = Bitmap.createBitmap(safeWidth, targetHeight, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        cache.put(cacheKey, bitmap)
        bitmap
      }
    }
  }

  /**
   * Renders a normalized sub-region of a PDF page at full zoomed pixel resolution using an affine
   * PdfRenderer transform matrix so zoomed text stays sharp at any zoom factor without creating
   * giant full-page bitmaps.
   */
  suspend fun renderPageRegion(
    pageIndex: Int,
    region: PdfNormalizedRect,
    fullPageTargetWidthPx: Float,
    renderedScale: Float
  ): PdfZoomTile? = withContext(Dispatchers.IO) {
    if (region.width <= 0.005f || region.height <= 0.005f) return@withContext null

    mutex.withLock {
      val activeRenderer = renderer ?: run {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        pfd = descriptor
        PdfRenderer(descriptor).also { renderer = it }
      }
      if (pageIndex < 0 || pageIndex >= activeRenderer.pageCount) return@withLock null

      activeRenderer.openPage(pageIndex).use { page ->
        val spec = computePdfRegionRenderSpec(
          pagePtWidth = page.width.toFloat(),
          pagePtHeight = page.height.toFloat(),
          region = region,
          fullPageTargetWidthPx = fullPageTargetWidthPx
        ) ?: return@withLock null

        val matrix = android.graphics.Matrix().apply {
          postTranslate(spec.translatePtX, spec.translatePtY)
          postScale(spec.scaleX, spec.scaleY)
        }

        val bitmap = Bitmap.createBitmap(spec.bitmapWidthPx, spec.bitmapHeightPx, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.WHITE)
        page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

        PdfZoomTile(
          pageIndex = pageIndex,
          region = region,
          renderedScale = renderedScale,
          bitmap = bitmap
        )
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

  suspend fun extractOutline(): List<PdfChapterOutlineItem> = withContext(Dispatchers.IO) {
    textMutex.withLock {
      runCatching {
        ensurePdfBoxInitialized(appContext)
        val doc = pdDocument ?: PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).also {
          pdDocument = it
        }
        extractOutlineChapters(doc)
      }.getOrDefault(emptyList())
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

    fun extractOutlineChapters(doc: PDDocument): List<PdfChapterOutlineItem> {
      val outline = doc.documentCatalog?.documentOutline ?: return emptyList()
      val pageCount = doc.numberOfPages
      if (pageCount <= 0) return emptyList()

      val pageLookup = HashMap<com.tom_roush.pdfbox.cos.COSDictionary, Int>(pageCount)
      doc.pages.forEachIndexed { idx, page ->
        pageLookup[page.cosObject] = idx
      }

      val result = mutableListOf<PdfChapterOutlineItem>()
      val visited = HashSet<com.tom_roush.pdfbox.cos.COSDictionary>()

      fun walk(
        node: com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode,
        level: Int
      ) {
        for (child in node.children()) {
          val dict = child.cosObject
          if (dict != null && !visited.add(dict)) continue

          val title = child.title?.trim().orEmpty()
          if (title.isNotEmpty()) {
            val pageIndex = runCatching {
              val destPage = child.findDestinationPage(doc)
              if (destPage != null) {
                pageLookup[destPage.cosObject] ?: doc.pages.indexOf(destPage)
              } else {
                val directDest = child.destination
                  as? com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
                val actionDest = (child.action as? com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo)
                  ?.destination
                  as? com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
                val pageDest = directDest ?: actionDest
                pageDest?.retrievePageNumber()?.takeIf { it >= 0 }
                  ?: pageDest?.page?.let { p -> pageLookup[p.cosObject] ?: doc.pages.indexOf(p) }
                  ?: -1
              }
            }.getOrDefault(-1)

            if (pageIndex in 0 until pageCount) {
              result.add(
                PdfChapterOutlineItem(
                  index = result.size,
                  title = title,
                  pageIndex = pageIndex,
                  level = level
                )
              )
            }
          }
          walk(child, (level + 1).coerceAtMost(3))
        }
      }

      walk(outline, 0)
      return result
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
