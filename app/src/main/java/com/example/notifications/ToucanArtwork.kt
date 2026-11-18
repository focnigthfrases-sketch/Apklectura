package com.example.notifications

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import java.io.File
import java.io.FileOutputStream

/**
 * Original geometric Toucan mascot ("Tucán de Lettor") drawn with Canvas & Path.
 * Rendered once, cached in memory and persisted to app cache directory for fast notification reuse.
 */
object ToucanArtwork {

  private const val CACHE_FILENAME = "lettor_toucan_badge_v1.png"
  private const val DEFAULT_SIZE_PX = 192

  @Volatile
  private var cachedBitmap: Bitmap? = null
  private val lock = Any()

  fun getOrCreateBitmap(context: Context, sizePx: Int = DEFAULT_SIZE_PX): Bitmap {
    cachedBitmap?.takeIf { !it.isRecycled && it.width == sizePx }?.let { return it }

    synchronized(lock) {
      cachedBitmap?.takeIf { !it.isRecycled && it.width == sizePx }?.let { return it }

      val cacheFile = File(context.cacheDir, CACHE_FILENAME)
      if (cacheFile.exists() && cacheFile.length() > 0L) {
        val decoded = BitmapFactory.decodeFile(cacheFile.absolutePath)
        if (decoded != null && decoded.width == sizePx) {
          cachedBitmap = decoded
          return decoded
        }
      }

      val generated = renderToucanBitmap(sizePx)
      runCatching {
        FileOutputStream(cacheFile).use { fos ->
          generated.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
      }
      cachedBitmap = generated
      return generated
    }
  }

  /**
   * Renders the original editorial Toucan perched with an open book onto a new ARGB_8888 Bitmap.
   */
  fun renderToucanBitmap(sizePx: Int = DEFAULT_SIZE_PX): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val s = sizePx.toFloat()

    // 1. Warm Parchment Medallion Backdrop
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      shader = LinearGradient(
        0f, 0f, s, s,
        intArrayOf(
          Color.parseColor("#FAF5EC"),
          Color.parseColor("#EFE4D2")
        ),
        null,
        Shader.TileMode.CLAMP
      )
    }
    canvas.drawCircle(s * 0.5f, s * 0.5f, s * 0.48f, bgPaint)

    val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = s * 0.025f
      color = Color.parseColor("#D6C6B2")
    }
    canvas.drawCircle(s * 0.5f, s * 0.5f, s * 0.465f, ringPaint)

    // 2. Toucan Body & Tail (Deep Ink-Espresso Plumage)
    val plumagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
      color = Color.parseColor("#231D19")
    }
    val bodyPath = Path().apply {
      moveTo(s * 0.40f, s * 0.18f)
      cubicTo(s * 0.24f, s * 0.19f, s * 0.17f, s * 0.34f, s * 0.18f, s * 0.54f)
      cubicTo(s * 0.18f, s * 0.68f, s * 0.16f, s * 0.78f, s * 0.13f, s * 0.85f)
      lineTo(s * 0.25f, s * 0.83f)
      lineTo(s * 0.29f, s * 0.88f)
      lineTo(s * 0.35f, s * 0.77f)
      cubicTo(s * 0.48f, s * 0.75f, s * 0.56f, s * 0.64f, s * 0.56f, s * 0.50f)
      cubicTo(s * 0.56f, s * 0.37f, s * 0.52f, s * 0.27f, s * 0.49f, s * 0.21f)
      cubicTo(s * 0.47f, s * 0.19f, s * 0.44f, s * 0.18f, s * 0.40f, s * 0.18f)
      close()
    }
    canvas.drawPath(bodyPath, plumagePaint)

    // 3. Soft Folded Wing Highlight
    val wingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
      color = Color.parseColor("#352D28")
    }
    val wingPath = Path().apply {
      moveTo(s * 0.24f, s * 0.45f)
      cubicTo(s * 0.20f, s * 0.57f, s * 0.22f, s * 0.70f, s * 0.31f, s * 0.73f)
      cubicTo(s * 0.38f, s * 0.70f, s * 0.40f, s * 0.57f, s * 0.36f, s * 0.46f)
      close()
    }
    canvas.drawPath(wingPath, wingPaint)

    // 4. Cream-Ochre Bib / Throat
    val bibPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
      shader = LinearGradient(
        s * 0.36f, s * 0.26f, s * 0.52f, s * 0.56f,
        intArrayOf(
          Color.parseColor("#FFF8EB"),
          Color.parseColor("#F5DFB0")
        ),
        null,
        Shader.TileMode.CLAMP
      )
    }
    val bibPath = Path().apply {
      moveTo(s * 0.40f, s * 0.24f)
      cubicTo(s * 0.49f, s * 0.24f, s * 0.54f, s * 0.35f, s * 0.53f, s * 0.50f)
      cubicTo(s * 0.49f, s * 0.57f, s * 0.41f, s * 0.56f, s * 0.38f, s * 0.47f)
      cubicTo(s * 0.36f, s * 0.38f, s * 0.36f, s * 0.29f, s * 0.40f, s * 0.24f)
      close()
    }
    canvas.drawPath(bibPath, bibPaint)

    // 5. Eye Ring (Sage) & Expressive Eye
    val eyeRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
      color = Color.parseColor("#556851")
    }
    canvas.drawCircle(s * 0.425f, s * 0.315f, s * 0.052f, eyeRingPaint)

    val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
      color = Color.parseColor("#1A1614")
    }
    canvas.drawCircle(s * 0.428f, s * 0.315f, s * 0.032f, eyePaint)

    val catchlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
      color = Color.WHITE
    }
    canvas.drawCircle(s * 0.437f, s * 0.305f, s * 0.011f, catchlightPaint)

    // 6. Signature Editorial Tri-Tone Beak (Golden Ochre -> Terracotta -> Dark Tip)
    val upperBeakPath = Path().apply {
      moveTo(s * 0.50f, s * 0.21f)
      cubicTo(s * 0.69f, s * 0.19f, s * 0.85f, s * 0.28f, s * 0.88f, s * 0.47f)
      cubicTo(s * 0.88f, s * 0.50f, s * 0.85f, s * 0.51f, s * 0.82f, s * 0.49f)
      cubicTo(s * 0.72f, s * 0.43f, s * 0.62f, s * 0.42f, s * 0.51f, s * 0.43f)
      close()
    }
    val upperBeakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
      shader = LinearGradient(
        s * 0.50f, s * 0.21f, s * 0.88f, s * 0.48f,
        intArrayOf(
          Color.parseColor("#F0B850"),
          Color.parseColor("#D96B38"),
          Color.parseColor("#B5482D")
        ),
        floatArrayOf(0f, 0.65f, 1f),
        Shader.TileMode.CLAMP
      )
    }
    canvas.drawPath(upperBeakPath, upperBeakPaint)

    // Beak tip accent
    canvas.save()
    canvas.clipPath(upperBeakPath)
    val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#2A211D")
    }
    canvas.drawOval(RectF(s * 0.79f, s * 0.34f, s * 0.91f, s * 0.52f), tipPaint)
    canvas.restore()

    // Lower mandible
    val lowerBeakPath = Path().apply {
      moveTo(s * 0.51f, s * 0.445f)
      cubicTo(s * 0.62f, s * 0.44f, s * 0.72f, s * 0.45f, s * 0.80f, s * 0.495f)
      cubicTo(s * 0.70f, s * 0.515f, s * 0.59f, s * 0.51f, s * 0.51f, s * 0.485f)
      close()
    }
    val lowerBeakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
      color = Color.parseColor("#9B3A22")
    }
    canvas.drawPath(lowerBeakPath, lowerBeakPaint)

    // Subtle beak ridge highlight
    val ridgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = s * 0.014f
      strokeCap = Paint.Cap.ROUND
      color = Color.parseColor("#FFE4A3")
    }
    val ridgePath = Path().apply {
      moveTo(s * 0.54f, s * 0.245f)
      cubicTo(s * 0.66f, s * 0.24f, s * 0.76f, s * 0.29f, s * 0.81f, s * 0.37f)
    }
    canvas.drawPath(ridgePath, ridgePaint)

    // 7. Open Book Foreground Base (Lettor Editorial Motif)
    val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
      color = Color.parseColor("#B5482D")
    }
    val bookCoverPath = Path().apply {
      moveTo(s * 0.34f, s * 0.76f)
      quadTo(s * 0.53f, s * 0.71f, s * 0.63f, s * 0.77f)
      quadTo(s * 0.73f, s * 0.71f, s * 0.87f, s * 0.76f)
      lineTo(s * 0.85f, s * 0.86f)
      quadTo(s * 0.73f, s * 0.81f, s * 0.63f, s * 0.88f)
      quadTo(s * 0.53f, s * 0.81f, s * 0.36f, s * 0.86f)
      close()
    }
    canvas.drawPath(bookCoverPath, coverPaint)

    val pagesPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
      color = Color.parseColor("#FFFBF4")
    }
    val pagesPath = Path().apply {
      moveTo(s * 0.37f, s * 0.735f)
      quadTo(s * 0.52f, s * 0.69f, s * 0.63f, s * 0.755f)
      quadTo(s * 0.74f, s * 0.69f, s * 0.85f, s * 0.735f)
      lineTo(s * 0.84f, s * 0.815f)
      quadTo(s * 0.73f, s * 0.775f, s * 0.63f, s * 0.835f)
      quadTo(s * 0.53f, s * 0.775f, s * 0.38f, s * 0.815f)
      close()
    }
    canvas.drawPath(pagesPath, pagesPaint)

    val spineLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = s * 0.012f
      color = Color.parseColor("#C7B59D")
    }
    canvas.drawLine(s * 0.63f, s * 0.755f, s * 0.63f, s * 0.84f, spineLinePaint)

    return bitmap
  }

  /**
   * Clears in-memory and disk cache (useful for testing or theme upgrades).
   */
  fun clearCache(context: Context) {
    synchronized(lock) {
      cachedBitmap = null
      File(context.cacheDir, CACHE_FILENAME).delete()
    }
  }
}
