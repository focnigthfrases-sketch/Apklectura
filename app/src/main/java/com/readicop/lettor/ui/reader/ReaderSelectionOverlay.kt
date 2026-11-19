package com.readicop.lettor.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readicop.lettor.ui.theme.ReaderSurfaceColors
import kotlin.math.roundToInt

/**
 * Formats a clean literary citation for the user's clipboard/share sheet:
 * e.g., «texto» — Título, Autor (pág. X)
 */
fun formatBookCitation(
  selectedText: String,
  bookTitle: String,
  bookAuthor: String,
  locationLabel: String
): String {
  val cleanQuote = selectedText.trim().replace(Regex("\\s+"), " ")
  val cleanTitle = bookTitle.trim().ifBlank { "Libro" }
  val cleanAuthor = bookAuthor.trim()
  val cleanLocation = locationLabel.trim()

  val attribution = buildString {
    append(cleanTitle)
    if (cleanAuthor.isNotBlank()) {
      append(", ")
      append(cleanAuthor)
    }
    if (cleanLocation.isNotBlank()) {
      append(" (")
      append(cleanLocation)
      append(')')
    }
  }
  return "«$cleanQuote» — $attribution"
}

private enum class SelectionCanvasIconType {
  COPY,
  QUOTE,
  HIGHLIGHT,
  SHARE
}

@Composable
fun ReaderSelectionOverlay(
  containerSize: IntSize,
  topSafeInsetPx: Float,
  bottomSafeInsetPx: Float,
  surfaceColors: ReaderSurfaceColors,
  handlesVisible: Boolean,
  menuVisible: Boolean,
  anchorAPos: Offset?,
  anchorBPos: Offset?,
  selectionBounds: Rect?,
  copyEnabled: Boolean,
  highlightEnabled: Boolean,
  onDragAnchorA: (Offset) -> Unit,
  onDragAnchorB: (Offset) -> Unit,
  onDragFinished: () -> Unit = {},
  onCopyPlain: () -> Unit,
  onCopyCitation: () -> Unit,
  onHighlight: () -> Unit,
  onShare: () -> Unit,
  modifier: Modifier = Modifier
) {
  if (containerSize.width <= 0 || containerSize.height <= 0) return
  if (!handlesVisible && !menuVisible) return

  val baseDensity = LocalDensity.current
  // Lock fontScale = 1f so handles and floating menu buttons are strictly measured in dp
  // and never expand when reader fontSizeSp or system accessibility fontScale increases.
  CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, fontScale = 1f)) {
    val density = LocalDensity.current
    val edgePadPx = with(density) { 10.dp.toPx() }
    val handleClearancePx = with(density) { 12.dp.toPx() }

    Box(modifier = modifier.fillMaxSize()) {
      if (handlesVisible) {
        anchorAPos?.let { posA ->
          if (posA.y >= -handleClearancePx && posA.y <= containerSize.height + handleClearancePx) {
            SelectionHandleDot(
              handleKey = "anchor_a",
              position = Offset(
                x = posA.x.coerceIn(edgePadPx, (containerSize.width - edgePadPx).coerceAtLeast(edgePadPx)),
                y = posA.y.coerceIn(edgePadPx, (containerSize.height - edgePadPx).coerceAtLeast(edgePadPx))
              ),
              surfaceColors = surfaceColors,
              contentDescription = "Manejador de inicio de selección",
              testTag = "selection_handle_start",
              onDragTo = onDragAnchorA,
              onDragFinished = onDragFinished
            )
          }
        }

        anchorBPos?.let { posB ->
          if (posB.y >= -handleClearancePx && posB.y <= containerSize.height + handleClearancePx) {
            SelectionHandleDot(
              handleKey = "anchor_b",
              position = Offset(
                x = posB.x.coerceIn(edgePadPx, (containerSize.width - edgePadPx).coerceAtLeast(edgePadPx)),
                y = posB.y.coerceIn(edgePadPx, (containerSize.height - edgePadPx).coerceAtLeast(edgePadPx))
              ),
              surfaceColors = surfaceColors,
              contentDescription = "Manejador de fin de selección",
              testTag = "selection_handle_end",
              onDragTo = onDragAnchorB,
              onDragFinished = onDragFinished
            )
          }
        }
      }

      if (menuVisible && selectionBounds != null) {
        SelectionFloatingMenu(
          selectionBounds = selectionBounds,
          containerSize = containerSize,
          topSafeInsetPx = topSafeInsetPx,
          bottomSafeInsetPx = bottomSafeInsetPx,
          surfaceColors = surfaceColors,
          copyEnabled = copyEnabled,
          highlightEnabled = highlightEnabled,
          onCopyPlain = onCopyPlain,
          onCopyCitation = onCopyCitation,
          onHighlight = onHighlight,
          onShare = onShare
        )
      }
    }
  }
}

@Composable
private fun SelectionHandleDot(
  handleKey: String,
  position: Offset,
  surfaceColors: ReaderSurfaceColors,
  contentDescription: String,
  testTag: String,
  onDragTo: (Offset) -> Unit,
  onDragFinished: () -> Unit
) {
  val density = LocalDensity.current
  val touchSizeDp = 48.dp
  val halfTouchPx = with(density) { (touchSizeDp / 2f).toPx() }
  val dotRadiusPx = with(density) { 6.dp.toPx() } // 12.dp diameter

  val latestPos by rememberUpdatedState(position)
  val latestOnDragTo by rememberUpdatedState(onDragTo)
  val latestOnDragFinished by rememberUpdatedState(onDragFinished)

  Box(
    modifier = Modifier
      .offset {
        IntOffset(
          x = (position.x - halfTouchPx).roundToInt(),
          y = (position.y - halfTouchPx).roundToInt()
        )
      }
      .size(touchSizeDp)
      .semantics { this.contentDescription = contentDescription }
      .testTag(testTag)
      .pointerInput(handleKey) {
        var activePointerPos = Offset.Zero
        detectDragGestures(
          onDragStart = {
            activePointerPos = latestPos
          },
          onDragEnd = { latestOnDragFinished() },
          onDragCancel = { latestOnDragFinished() },
          onDrag = { change, dragAmount ->
            change.consume()
            activePointerPos += dragAmount
            latestOnDragTo(activePointerPos)
          }
        )
      },
    contentAlignment = Alignment.Center
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      val c = Offset(size.width * 0.5f, size.height * 0.5f)
      val shadowCenter = Offset(c.x, c.y + 1.5.dp.toPx())

      // Soft multi-layered shadow so the handle stays visible over any light/sepia/dark/ebony backdrop
      drawCircle(
        color = Color.Black.copy(alpha = if (surfaceColors.isDark) 0.16f else 0.06f),
        radius = dotRadiusPx + 5.dp.toPx(),
        center = shadowCenter
      )
      drawCircle(
        color = Color.Black.copy(alpha = if (surfaceColors.isDark) 0.24f else 0.12f),
        radius = dotRadiusPx + 3.dp.toPx(),
        center = shadowCenter
      )
      drawCircle(
        color = Color.Black.copy(alpha = if (surfaceColors.isDark) 0.34f else 0.20f),
        radius = dotRadiusPx + 1.2.dp.toPx(),
        center = shadowCenter
      )

      // Outer subtle contrast halo (essential on pure black Ebony and bright Paper)
      drawCircle(
        color = if (surfaceColors.isDark) Color.White.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.90f),
        radius = dotRadiusPx + 1.dp.toPx(),
        center = c
      )

      // 12.dp diameter accent circle matching reader accent
      drawCircle(
        color = surfaceColors.accent,
        radius = dotRadiusPx,
        center = c
      )
    }
  }
}

@Composable
private fun SelectionFloatingMenu(
  selectionBounds: Rect,
  containerSize: IntSize,
  topSafeInsetPx: Float,
  bottomSafeInsetPx: Float,
  surfaceColors: ReaderSurfaceColors,
  copyEnabled: Boolean,
  highlightEnabled: Boolean,
  onCopyPlain: () -> Unit,
  onCopyCitation: () -> Unit,
  onHighlight: () -> Unit,
  onShare: () -> Unit
) {
  val density = LocalDensity.current
  var menuSize by remember { mutableStateOf(IntSize.Zero) }

  val edgeMarginPx = with(density) { 10.dp.toPx() }
  val gapAbovePx = with(density) { 14.dp.toPx() }
  val gapBelowPx = with(density) { 26.dp.toPx() }

  val minX = edgeMarginPx
  val maxX = (containerSize.width - menuSize.width - edgeMarginPx).coerceAtLeast(minX)
  val rawX = selectionBounds.center.x - menuSize.width * 0.5f
  val clampedX = rawX.coerceIn(minX, maxX)

  val minY = (topSafeInsetPx + edgeMarginPx).coerceAtLeast(edgeMarginPx)
  val maxY = (containerSize.height - menuSize.height - bottomSafeInsetPx - edgeMarginPx).coerceAtLeast(minY)
  val aboveY = selectionBounds.top - menuSize.height - gapAbovePx
  val belowY = selectionBounds.bottom + gapBelowPx
  val targetY = if (aboveY >= minY) {
    aboveY.coerceIn(minY, maxY)
  } else {
    belowY.coerceIn(minY, maxY)
  }

  val menuShape = RoundedCornerShape(16.dp)
  val borderColor = if (surfaceColors.isDark) {
    surfaceColors.divider.copy(alpha = 0.95f)
  } else {
    surfaceColors.divider
  }

  Surface(
    shape = menuShape,
    color = surfaceColors.surface,
    shadowElevation = 10.dp,
    modifier = Modifier
      .offset { IntOffset(clampedX.roundToInt(), targetY.roundToInt()) }
      .onSizeChanged { menuSize = it }
      .border(1.dp, borderColor, menuShape)
      .testTag("selection_floating_menu")
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
      SelectionCanvasMenuButton(
        label = "Copiar",
        iconType = SelectionCanvasIconType.COPY,
        enabled = copyEnabled,
        surfaceColors = surfaceColors,
        testTag = "selection_action_copy",
        onClick = onCopyPlain
      )
      SelectionCanvasDivider(surfaceColors.divider)
      SelectionCanvasMenuButton(
        label = "Cita",
        iconType = SelectionCanvasIconType.QUOTE,
        enabled = copyEnabled,
        surfaceColors = surfaceColors,
        testTag = "selection_action_copy_quote",
        onClick = onCopyCitation
      )
      SelectionCanvasDivider(surfaceColors.divider)
      SelectionCanvasMenuButton(
        label = "Resaltar",
        iconType = SelectionCanvasIconType.HIGHLIGHT,
        enabled = highlightEnabled,
        surfaceColors = surfaceColors,
        testTag = "selection_action_highlight",
        onClick = onHighlight
      )
      SelectionCanvasDivider(surfaceColors.divider)
      SelectionCanvasMenuButton(
        label = "Compartir",
        iconType = SelectionCanvasIconType.SHARE,
        enabled = copyEnabled,
        surfaceColors = surfaceColors,
        testTag = "selection_action_share",
        onClick = onShare
      )
    }
  }
}

@Composable
private fun SelectionCanvasDivider(color: Color) {
  Box(
    modifier = Modifier
      .width(1.dp)
      .height(26.dp)
      .background(color.copy(alpha = 0.7f))
  )
}

@Composable
private fun SelectionCanvasMenuButton(
  label: String,
  iconType: SelectionCanvasIconType,
  enabled: Boolean,
  surfaceColors: ReaderSurfaceColors,
  testTag: String,
  onClick: () -> Unit
) {
  val iconTint = surfaceColors.text
  val accentTint = surfaceColors.accent

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
    modifier = Modifier
      .widthIn(min = 56.dp)
      .height(48.dp)
      .clip(RoundedCornerShape(12.dp))
      .then(
        if (enabled) {
          Modifier.clickable(role = Role.Button, onClick = onClick)
        } else {
          Modifier
        }
      )
      .alpha(if (enabled) 1f else 0.34f)
      .padding(horizontal = 8.dp, vertical = 4.dp)
      .semantics {
        contentDescription = label
        if (!enabled) disabled()
      }
      .testTag(testTag)
  ) {
    Canvas(modifier = Modifier.size(18.dp)) {
      val w = size.width
      val h = size.height
      val strokePx = 1.65.dp.toPx()
      val stroke = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)

      when (iconType) {
        SelectionCanvasIconType.COPY -> {
          // Back sheet arc/frame
          val backPath = Path().apply {
            moveTo(w * 0.34f, h * 0.24f)
            lineTo(w * 0.34f, h * 0.16f)
            quadraticTo(w * 0.34f, h * 0.10f, w * 0.40f, h * 0.10f)
            lineTo(w * 0.80f, h * 0.10f)
            quadraticTo(w * 0.86f, h * 0.10f, w * 0.86f, h * 0.16f)
            lineTo(w * 0.86f, h * 0.66f)
            quadraticTo(w * 0.86f, h * 0.72f, w * 0.80f, h * 0.72f)
            lineTo(w * 0.72f, h * 0.72f)
          }
          drawPath(backPath, color = iconTint.copy(alpha = 0.78f), style = stroke)

          // Front rounded document sheet
          drawRoundRect(
            color = iconTint,
            topLeft = Offset(w * 0.14f, h * 0.28f),
            size = Size(w * 0.54f, h * 0.62f),
            cornerRadius = CornerRadius(2.4.dp.toPx(), 2.4.dp.toPx()),
            style = stroke
          )
          // Subtle text lines on front sheet
          drawLine(
            color = accentTint,
            start = Offset(w * 0.27f, h * 0.49f),
            end = Offset(w * 0.55f, h * 0.49f),
            strokeWidth = strokePx * 0.9f,
            cap = StrokeCap.Round
          )
          drawLine(
            color = iconTint.copy(alpha = 0.75f),
            start = Offset(w * 0.27f, h * 0.66f),
            end = Offset(w * 0.49f, h * 0.66f),
            strokeWidth = strokePx * 0.9f,
            cap = StrokeCap.Round
          )
        }

        SelectionCanvasIconType.QUOTE -> {
          // Fine rounded guillemets « » + editorial attribution stroke
          val leftChevron1 = Path().apply {
            moveTo(w * 0.30f, h * 0.22f)
            lineTo(w * 0.13f, h * 0.40f)
            lineTo(w * 0.30f, h * 0.58f)
          }
          val leftChevron2 = Path().apply {
            moveTo(w * 0.47f, h * 0.22f)
            lineTo(w * 0.30f, h * 0.40f)
            lineTo(w * 0.47f, h * 0.58f)
          }
          val rightChevron1 = Path().apply {
            moveTo(w * 0.53f, h * 0.22f)
            lineTo(w * 0.70f, h * 0.40f)
            lineTo(w * 0.53f, h * 0.58f)
          }
          val rightChevron2 = Path().apply {
            moveTo(w * 0.70f, h * 0.22f)
            lineTo(w * 0.87f, h * 0.40f)
            lineTo(w * 0.70f, h * 0.58f)
          }
          drawPath(leftChevron1, color = accentTint, style = stroke)
          drawPath(leftChevron2, color = accentTint, style = stroke)
          drawPath(rightChevron1, color = iconTint, style = stroke)
          drawPath(rightChevron2, color = iconTint, style = stroke)

          // Attribution dash line underneath
          drawLine(
            color = iconTint.copy(alpha = 0.82f),
            start = Offset(w * 0.22f, h * 0.80f),
            end = Offset(w * 0.78f, h * 0.80f),
            strokeWidth = strokePx,
            cap = StrokeCap.Round
          )
        }

        SelectionCanvasIconType.HIGHLIGHT -> {
          // Angled editorial highlighter nib + accented highlight underline
          val penBody = Path().apply {
            moveTo(w * 0.30f, h * 0.68f)
            lineTo(w * 0.24f, h * 0.56f)
            lineTo(w * 0.64f, h * 0.14f)
            quadraticTo(w * 0.70f, h * 0.08f, w * 0.76f, h * 0.14f)
            lineTo(w * 0.84f, h * 0.22f)
            quadraticTo(w * 0.90f, h * 0.28f, w * 0.84f, h * 0.34f)
            lineTo(w * 0.44f, h * 0.74f)
            close()
          }
          drawPath(penBody, color = iconTint, style = stroke)
          // Nib collar line
          drawLine(
            color = iconTint,
            start = Offset(w * 0.55f, h * 0.24f),
            end = Offset(w * 0.74f, h * 0.43f),
            strokeWidth = strokePx * 0.9f,
            cap = StrokeCap.Round
          )
          // Warm glowing highlight stroke beneath the tip
          drawLine(
            color = accentTint,
            start = Offset(w * 0.14f, h * 0.85f),
            end = Offset(w * 0.86f, h * 0.85f),
            strokeWidth = 2.4.dp.toPx(),
            cap = StrokeCap.Round
          )
        }

        SelectionCanvasIconType.SHARE -> {
          // Editorial upward share arrow + open rounded tray
          drawLine(
            color = accentTint,
            start = Offset(w * 0.50f, h * 0.62f),
            end = Offset(w * 0.50f, h * 0.14f),
            strokeWidth = strokePx,
            cap = StrokeCap.Round
          )
          val arrowHead = Path().apply {
            moveTo(w * 0.31f, h * 0.33f)
            lineTo(w * 0.50f, h * 0.14f)
            lineTo(w * 0.69f, h * 0.33f)
          }
          drawPath(arrowHead, color = accentTint, style = stroke)

          val tray = Path().apply {
            moveTo(w * 0.18f, h * 0.54f)
            lineTo(w * 0.18f, h * 0.78f)
            quadraticTo(w * 0.18f, h * 0.86f, w * 0.26f, h * 0.86f)
            lineTo(w * 0.74f, h * 0.86f)
            quadraticTo(w * 0.82f, h * 0.86f, w * 0.82f, h * 0.78f)
            lineTo(w * 0.82f, h * 0.54f)
          }
          drawPath(tray, color = iconTint, style = stroke)
        }
      }
    }

    Spacer(Modifier.height(2.dp))
    Text(
      text = label,
      fontSize = 10.sp,
      lineHeight = 12.sp,
      fontWeight = FontWeight.Medium,
      color = surfaceColors.text,
      maxLines = 1,
      overflow = TextOverflow.Clip
    )
  }
}
