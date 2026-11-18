package com.example.domain.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PdfBookSession(private val file: File) : Closeable {
  private val mutex = Mutex()
  private var pfd: ParcelFileDescriptor? = null
  private var renderer: PdfRenderer? = null

  private val cache = object : LruCache<String, Bitmap>(6) {
    override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
      // Let GC reclaim evicted bitmaps safely without recycling while Compose might draw
    }
  }

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

  override fun close() {
    runCatching { renderer?.close() }
    runCatching { pfd?.close() }
    renderer = null
    pfd = null
    cache.evictAll()
  }

  companion object {
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
