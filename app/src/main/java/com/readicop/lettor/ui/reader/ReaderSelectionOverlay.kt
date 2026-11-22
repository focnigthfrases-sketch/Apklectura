package com.readicop.lettor.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readicop.lettor.ui.theme.ReaderSurfaceColors
import com.readicop.lettor.ui.theme.SelectionBarBorder
import com.readicop.lettor.ui.theme.SelectionBarDivider
import com.readicop.lettor.ui.theme.SelectionBarInk
import com.readicop.lettor.ui.theme.SelectionBarLightBackground
import kotlin.math.asin
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class SelectionHandleShape {
  DOT,
  TEARDROP
}

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
  PARAGRAPH,
  HIGHLIGHT,
  SHARE,
  TRANSLATE,
  DICTIONARY,
  MORE_MENU
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
  handleShape: SelectionHandleShape = SelectionHandleShape.DOT,
  useEpubExtendedMenu: Boolean = false,
  onDragAnchorA: (Offset) -> Unit,
  onDragAnchorB: (Offset) -> Unit,
  onDragFinished: () -> Unit = {},
  onCopyPlain: () -> Unit,
  onSelectParagraph: () -> Unit,
  onHighlight: () -> Unit,
  onShare: () -> Unit,
  onTranslate: () -> Unit = {},
  onDictionary: () -> Unit = {},
  onSelectWord: () -> Unit = {},
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
    val teardropHeightPx = with(density) { 16.dp.toPx() }
    val maxHandleY = if (handleShape == SelectionHandleShape.TEARDROP) {
      (containerSize.height - teardropHeightPx - edgePadPx).coerceAtLeast(edgePadPx)
    } else {
      (containerSize.height - edgePadPx).coerceAtLeast(edgePadPx)
    }

    Box(modifier = modifier.fillMaxSize()) {
      if (handlesVisible) {
        anchorAPos?.let { posA ->
          if (posA.y >= -handleClearancePx && posA.y <= containerSize.height + handleClearancePx) {
            SelectionHandleDot(
              handleKey = "anchor_a",
              position = Offset(
                x = posA.x.coerceIn(edgePadPx, (containerSize.width - edgePadPx).coerceAtLeast(edgePadPx)),
                y = posA.y.coerceIn(edgePadPx, maxHandleY)
              ),
              surfaceColors = surfaceColors,
              handleShape = handleShape,
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
                y = posB.y.coerceIn(edgePadPx, maxHandleY)
              ),
              surfaceColors = surfaceColors,
              handleShape = handleShape,
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
          topSafeInsetPx = topSafePxCompat(topSafeInsetPx),
          bottomSafeInsetPx = bottomSafeInsetPx,
          extraBelowSelectionGapPx = if (handleShape == SelectionHandleShape.TEARDROP && handlesVisible) {
            with(density) { 18.dp.toPx() }
          } else {
            0f
          },
          surfaceColors = surfaceColors,
          copyEnabled = copyEnabled,
          highlightEnabled = highlightEnabled,
          useEpubExtendedMenu = useEpubExtendedMenu,
          onCopyPlain = onCopyPlain,
          onSelectParagraph = onSelectParagraph,
          onHighlight = onHighlight,
          onShare = onShare,
          onTranslate = onTranslate,
          onDictionary = onDictionary,
          onSelectWord = onSelectWord
        )
      }
    }
  }
}

private fun topSafePxCompat(value: Float): Float = value

@Composable
private fun SelectionHandleDot(
  handleKey: String,
  position: Offset,
  surfaceColors: ReaderSurfaceColors,
  handleShape: SelectionHandleShape,
  contentDescription: String,
  testTag: String,
  onDragTo: (Offset) -> Unit,
  onDragFinished: () -> Unit
) {
  val density = LocalDensity.current
  val touchSizeDp = 48.dp
  val halfTouchPx = with(density) { (touchSizeDp / 2f).toPx() }
  val dotRadiusPx = with(density) { 6.dp.toPx() } // 12.dp visual width/diameter
  val teardropTipLocalYPx = with(density) { 14.dp.toPx() }
  val anchorLocalYPx = if (handleShape == SelectionHandleShape.TEARDROP) teardropTipLocalYPx else halfTouchPx

  val latestPos by rememberUpdatedState(position)
  val latestOnDragTo by rememberUpdatedState(onDragTo)
  val latestOnDragFinished by rememberUpdatedState(onDragFinished)

  Box(
    modifier = Modifier
      .offset {
        IntOffset(
          x = (position.x - halfTouchPx).roundToInt(),
          y = (position.y - anchorLocalYPx).roundToInt()
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
      if (handleShape == SelectionHandleShape.TEARDROP) {
        val r = dotRadiusPx
        val tipToCenterDist = r * 1.65f
        val cx = size.width * 0.5f
        val tipY = teardropTipLocalYPx
        val cy = tipY + tipToCenterDist

        val sinA = (r / tipToCenterDist).coerceIn(0f, 0.99f)
        val cosA = sqrt((1f - sinA * sinA).coerceAtLeast(0f))
        val startAngleDeg = -Math.toDegrees(asin(sinA.toDouble())).toFloat()
        val sweepAngleDeg = 180f - 2f * startAngleDeg

        fun buildTeardropPath(inflatePx: Float, yShiftPx: Float = 0f): Path {
          val ir = (r + inflatePx).coerceAtLeast(1f)
          val iTipY = (tipY - inflatePx * 1.25f) + yShiftPx
          val iCy = cy + yShiftPx
          val rect = Rect(cx - ir, iCy - ir, cx + ir, iCy + ir)
          val dx = ir * cosA
          val dy = -ir * sinA
          return Path().apply {
            moveTo(cx, iTipY)
            lineTo(cx + dx, iCy + dy)
            arcTo(
              rect = rect,
              startAngleDegrees = startAngleDeg,
              sweepAngleDegrees = sweepAngleDeg,
              forceMoveTo = false
            )
            lineTo(cx, iTipY)
            close()
          }
        }

        // Soft multi-layered shadow so the droplet stays legible over any theme
        drawPath(
          path = buildTeardropPath(inflatePx = 4.2.dp.toPx(), yShiftPx = 1.4.dp.toPx()),
          color = Color.Black.copy(alpha = if (surfaceColors.isDark) 0.16f else 0.06f)
        )
        drawPath(
          path = buildTeardropPath(inflatePx = 2.6.dp.toPx(), yShiftPx = 1.2.dp.toPx()),
          color = Color.Black.copy(alpha = if (surfaceColors.isDark) 0.24f else 0.12f)
        )
        drawPath(
          path = buildTeardropPath(inflatePx = 1.2.dp.toPx(), yShiftPx = 1.0.dp.toPx()),
          color = Color.Black.copy(alpha = if (surfaceColors.isDark) 0.32f else 0.18f)
        )

        // Contrast halo
        drawPath(
          path = buildTeardropPath(inflatePx = 1.0.dp.toPx()),
          color = if (surfaceColors.isDark) Color.White.copy(alpha = 0.48f) else Color.White.copy(alpha = 0.92f)
        )

        // Crisp accent water droplet pointing up toward the text
        drawPath(
          path = buildTeardropPath(inflatePx = 0f),
          color = surfaceColors.accent
        )
      } else {
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
}

@Composable
private fun SelectionFloatingMenu(
  selectionBounds: Rect,
  containerSize: IntSize,
  topSafeInsetPx: Float,
  bottomSafeInsetPx: Float,
  extraBelowSelectionGapPx: Float,
  surfaceColors: ReaderSurfaceColors,
  copyEnabled: Boolean,
  highlightEnabled: Boolean,
  useEpubExtendedMenu: Boolean,
  onCopyPlain: () -> Unit,
  onSelectParagraph: () -> Unit,
  onHighlight: () -> Unit,
  onShare: () -> Unit,
  onTranslate: () -> Unit,
  onDictionary: () -> Unit,
  onSelectWord: () -> Unit
) {
  val density = LocalDensity.current
  var menuSize by remember { mutableStateOf(IntSize.Zero) }
  var moreMenuExpanded by remember { mutableStateOf(false) }

  val edgeMarginPx = with(density) { 10.dp.toPx() }
  val selectionGapPx = with(density) { 12.dp.toPx() }
  val tightGapPx = with(density) { 4.dp.toPx() }

  val minX = edgeMarginPx
  val maxX = (containerSize.width - menuSize.width - edgeMarginPx).coerceAtLeast(minX)
  val rawX = selectionBounds.center.x - menuSize.width * 0.5f
  val clampedX = rawX.coerceIn(minX, maxX)

  val menuH = menuSize.height.toFloat()
  val topLimit = (topSafeInsetPx + edgeMarginPx).coerceAtLeast(edgeMarginPx)
  val bottomLimit = (containerSize.height - bottomSafeInsetPx - edgeMarginPx - menuH).coerceAtLeast(topLimit)

  val aboveY = selectionBounds.top - selectionGapPx - menuH
  val belowY = selectionBounds.bottom + selectionGapPx + extraBelowSelectionGapPx

  val tightTopEdge = (topSafeInsetPx + tightGapPx).coerceAtLeast(tightGapPx)
  val tightBottomEdge = (containerSize.height - bottomSafeInsetPx - tightGapPx - menuH)
    .coerceAtLeast(tightTopEdge)
  val rawTopEdge = tightGapPx
  val rawBottomEdge = (containerSize.height - tightGapPx - menuH).coerceAtLeast(rawTopEdge)

  val targetY = when {
    // 1. Above selection with margin
    aboveY >= topLimit -> aboveY
    // 2. Below selection with margin
    belowY <= bottomLimit -> belowY
    // 3. Screen edge without overlapping selection
    tightTopEdge + menuH <= selectionBounds.top - tightGapPx -> tightTopEdge
    tightBottomEdge >= selectionBounds.bottom + extraBelowSelectionGapPx + tightGapPx -> tightBottomEdge
    rawTopEdge + menuH <= selectionBounds.top - tightGapPx -> rawTopEdge
    rawBottomEdge >= selectionBounds.bottom + extraBelowSelectionGapPx + tightGapPx -> rawBottomEdge
    // Selection covers entire viewport height: pin to edge farthest from vertical center
    else -> if (selectionBounds.center.y >= containerSize.height * 0.5f) topLimit else bottomLimit
  }

  val menuShape = CircleShape
  val maxAvailableWidthDp = with(density) { (containerSize.width.toFloat() - edgeMarginPx * 2f).coerceAtLeast(220f).toDp() }
  val isCompactScreen = maxAvailableWidthDp < 372.dp
  val pillHorizontalPaddingDp = if (isCompactScreen) 8 else 12
  val buttonHorizontalPaddingDp = if (isCompactScreen) 6 else 10
  val minLabeledWidthDp = if (isCompactScreen) 48 else 56
  val labelFontSize = if (isCompactScreen) 10.sp else 10.5.sp

  Surface(
    shape = menuShape,
    color = SelectionBarLightBackground,
    shadowElevation = 6.dp,
    modifier = Modifier
      .offset { IntOffset(clampedX.roundToInt(), targetY.roundToInt()) }
      .widthIn(max = maxAvailableWidthDp)
      .onSizeChanged { menuSize = it }
      .alpha(if (menuSize.width > 0 && menuSize.height > 0) 1f else 0f)
      .border(0.75.dp, SelectionBarBorder, menuShape)
      .testTag("selection_floating_menu")
  ) {
    Row(
      modifier = Modifier
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = pillHorizontalPaddingDp.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
      if (useEpubExtendedMenu) {
        SelectionCanvasMenuButton(
          label = "Copiar",
          iconType = SelectionCanvasIconType.COPY,
          enabled = copyEnabled,
          minButtonWidthDp = minLabeledWidthDp,
          horizontalPaddingDp = buttonHorizontalPaddingDp,
          labelFontSize = labelFontSize,
          testTag = "selection_action_copy",
          onClick = {
            moreMenuExpanded = false
            onCopyPlain()
          }
        )
        SelectionCanvasDivider(SelectionBarDivider)
        SelectionCanvasMenuButton(
          label = "Traducir",
          iconType = SelectionCanvasIconType.TRANSLATE,
          enabled = copyEnabled,
          minButtonWidthDp = minLabeledWidthDp,
          horizontalPaddingDp = buttonHorizontalPaddingDp,
          labelFontSize = labelFontSize,
          testTag = "selection_action_translate",
          onClick = {
            moreMenuExpanded = false
            onTranslate()
          }
        )
        SelectionCanvasDivider(SelectionBarDivider)
        SelectionCanvasMenuButton(
          label = "Resaltar",
          iconType = SelectionCanvasIconType.HIGHLIGHT,
          enabled = highlightEnabled,
          minButtonWidthDp = minLabeledWidthDp,
          horizontalPaddingDp = buttonHorizontalPaddingDp,
          labelFontSize = labelFontSize,
          testTag = "selection_action_highlight",
          onClick = {
            moreMenuExpanded = false
            onHighlight()
          }
        )
        SelectionCanvasDivider(SelectionBarDivider)
        SelectionCanvasMenuButton(
          label = "Diccionario",
          iconType = SelectionCanvasIconType.DICTIONARY,
          enabled = copyEnabled,
          minButtonWidthDp = minLabeledWidthDp,
          horizontalPaddingDp = buttonHorizontalPaddingDp,
          labelFontSize = labelFontSize,
          testTag = "selection_action_dictionary",
          onClick = {
            moreMenuExpanded = false
            onDictionary()
          }
        )
        SelectionCanvasDivider(SelectionBarDivider)
        Box {
          SelectionCanvasMenuButton(
            label = null,
            accessibilityLabel = "Más opciones",
            iconType = SelectionCanvasIconType.MORE_MENU,
            enabled = copyEnabled,
            minButtonWidthDp = 48,
            horizontalPaddingDp = 8,
            labelFontSize = labelFontSize,
            testTag = "selection_action_more",
            onClick = { moreMenuExpanded = !moreMenuExpanded }
          )
          DropdownMenu(
            expanded = moreMenuExpanded,
            onDismissRequest = { moreMenuExpanded = false },
            containerColor = SelectionBarLightBackground,
            modifier = Modifier.testTag("selection_more_dropdown")
          ) {
            DropdownMenuItem(
              text = { Text("Palabras", color = SelectionBarInk) },
              leadingIcon = {
                Icon(
                  imageVector = Icons.Default.TextFields,
                  contentDescription = null,
                  tint = SelectionBarInk
                )
              },
              colors = MenuDefaults.itemColors(textColor = SelectionBarInk),
              onClick = {
                moreMenuExpanded = false
                onSelectWord()
              },
              modifier = Modifier.testTag("selection_menu_item_words")
            )
            DropdownMenuItem(
              text = { Text("Compartir", color = SelectionBarInk) },
              leadingIcon = {
                Icon(
                  imageVector = Icons.Default.Share,
                  contentDescription = null,
                  tint = SelectionBarInk
                )
              },
              colors = MenuDefaults.itemColors(textColor = SelectionBarInk),
              onClick = {
                moreMenuExpanded = false
                onShare()
              },
              modifier = Modifier.testTag("selection_menu_item_share")
            )
            DropdownMenuItem(
              text = { Text("Párrafo", color = SelectionBarInk) },
              leadingIcon = {
                Icon(
                  imageVector = Icons.AutoMirrored.Filled.FormatAlignLeft,
                  contentDescription = null,
                  tint = SelectionBarInk
                )
              },
              colors = MenuDefaults.itemColors(textColor = SelectionBarInk),
              onClick = {
                moreMenuExpanded = false
                onSelectParagraph()
              },
              modifier = Modifier.testTag("selection_menu_item_paragraph")
            )
          }
        }
      } else {
        SelectionCanvasMenuButton(
          label = "Copiar",
          iconType = SelectionCanvasIconType.COPY,
          enabled = copyEnabled,
          minButtonWidthDp = minLabeledWidthDp,
          horizontalPaddingDp = buttonHorizontalPaddingDp,
          labelFontSize = labelFontSize,
          testTag = "selection_action_copy",
          onClick = onCopyPlain
        )
        SelectionCanvasDivider(SelectionBarDivider)
        SelectionCanvasMenuButton(
          label = "Párrafo",
          iconType = SelectionCanvasIconType.PARAGRAPH,
          enabled = copyEnabled,
          minButtonWidthDp = minLabeledWidthDp,
          horizontalPaddingDp = buttonHorizontalPaddingDp,
          labelFontSize = labelFontSize,
          testTag = "selection_action_paragraph",
          onClick = onSelectParagraph
        )
        SelectionCanvasDivider(SelectionBarDivider)
        SelectionCanvasMenuButton(
          label = "Resaltar",
          iconType = SelectionCanvasIconType.HIGHLIGHT,
          enabled = highlightEnabled,
          minButtonWidthDp = minLabeledWidthDp,
          horizontalPaddingDp = buttonHorizontalPaddingDp,
          labelFontSize = labelFontSize,
          testTag = "selection_action_highlight",
          onClick = onHighlight
        )
        SelectionCanvasDivider(SelectionBarDivider)
        SelectionCanvasMenuButton(
          label = "Compartir",
          iconType = SelectionCanvasIconType.SHARE,
          enabled = copyEnabled,
          minButtonWidthDp = minLabeledWidthDp,
          horizontalPaddingDp = buttonHorizontalPaddingDp,
          labelFontSize = labelFontSize,
          testTag = "selection_action_share",
          onClick = onShare
        )
      }
    }
  }
}

@Composable
private fun SelectionCanvasDivider(color: Color) {
  Box(
    modifier = Modifier
      .width(0.8.dp)
      .height(28.dp)
      .background(color)
  )
}

@Composable
private fun SelectionCanvasMenuButton(
  label: String?,
  iconType: SelectionCanvasIconType,
  enabled: Boolean,
  testTag: String,
  accessibilityLabel: String = label.orEmpty(),
  minButtonWidthDp: Int = 56,
  horizontalPaddingDp: Int = 10,
  labelFontSize: TextUnit = 10.5.sp,
  onClick: () -> Unit
) {
  val iconTint = SelectionBarInk

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
    modifier = Modifier
      .widthIn(min = minButtonWidthDp.dp)
      .height(50.dp)
      .clip(RoundedCornerShape(16.dp))
      .then(
        if (enabled) {
          Modifier.clickable(role = Role.Button, onClick = onClick)
        } else {
          Modifier
        }
      )
      .alpha(if (enabled) 1f else 0.34f)
      .padding(horizontal = horizontalPaddingDp.dp, vertical = 4.dp)
      .semantics {
        contentDescription = accessibilityLabel
        if (!enabled) disabled()
      }
      .testTag(testTag)
  ) {
    Canvas(modifier = Modifier.size(20.dp)) {
      val w = size.width
      val h = size.height
      val strokePx = 1.65.dp.toPx()
      val stroke = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)

      when (iconType) {
        SelectionCanvasIconType.COPY -> {
          // Two overlapping rounded sheets (back sheet peeking top-right + clean front sheet)
          val backSheetPath = Path().apply {
            moveTo(w * 0.34f, h * 0.27f)
            lineTo(w * 0.34f, h * 0.17f)
            quadraticTo(w * 0.34f, h * 0.11f, w * 0.40f, h * 0.11f)
            lineTo(w * 0.80f, h * 0.11f)
            quadraticTo(w * 0.86f, h * 0.11f, w * 0.86f, h * 0.17f)
            lineTo(w * 0.86f, h * 0.65f)
            quadraticTo(w * 0.86f, h * 0.71f, w * 0.80f, h * 0.71f)
            lineTo(w * 0.68f, h * 0.71f)
          }
          drawPath(backSheetPath, color = iconTint, style = stroke)

          drawRoundRect(
            color = iconTint,
            topLeft = Offset(w * 0.14f, h * 0.27f),
            size = Size(w * 0.54f, h * 0.62f),
            cornerRadius = CornerRadius(2.6.dp.toPx(), 2.6.dp.toPx()),
            style = stroke
          )
          drawLine(
            color = iconTint,
            start = Offset(w * 0.29f, h * 0.49f),
            end = Offset(w * 0.53f, h * 0.49f),
            strokeWidth = strokePx,
            cap = StrokeCap.Round
          )
          drawLine(
            color = iconTint,
            start = Offset(w * 0.29f, h * 0.66f),
            end = Offset(w * 0.48f, h * 0.66f),
            strokeWidth = strokePx,
            cap = StrokeCap.Round
          )
        }

        SelectionCanvasIconType.PARAGRAPH -> {
          val bowl = Path().apply {
            moveTo(w * 0.50f, h * 0.17f)
            lineTo(w * 0.36f, h * 0.17f)
            cubicTo(w * 0.15f, h * 0.17f, w * 0.15f, h * 0.53f, w * 0.36f, h * 0.53f)
            lineTo(w * 0.50f, h * 0.53f)
          }
          drawPath(bowl, color = iconTint, style = stroke)

          drawLine(
            color = iconTint,
            start = Offset(w * 0.42f, h * 0.17f),
            end = Offset(w * 0.80f, h * 0.17f),
            strokeWidth = strokePx,
            cap = StrokeCap.Round
          )
          drawLine(
            color = iconTint,
            start = Offset(w * 0.50f, h * 0.17f),
            end = Offset(w * 0.50f, h * 0.84f),
            strokeWidth = strokePx,
            cap = StrokeCap.Round
          )
          drawLine(
            color = iconTint,
            start = Offset(w * 0.70f, h * 0.17f),
            end = Offset(w * 0.70f, h * 0.84f),
            strokeWidth = strokePx,
            cap = StrokeCap.Round
          )
        }

        SelectionCanvasIconType.HIGHLIGHT -> {
          // Broad angled chisel-tip highlighter marker (flat beveled tip, not a pointed pencil)
          val markerBody = Path().apply {
            moveTo(w * 0.65f, h * 0.13f)
            lineTo(w * 0.83f, h * 0.31f)
            lineTo(w * 0.51f, h * 0.63f)
            lineTo(w * 0.38f, h * 0.73f)
            lineTo(w * 0.24f, h * 0.73f)
            lineTo(w * 0.22f, h * 0.66f)
            lineTo(w * 0.33f, h * 0.45f)
            close()
          }
          drawPath(markerBody, color = iconTint, style = stroke)
          // Grip divider across barrel
          drawLine(
            color = iconTint,
            start = Offset(w * 0.53f, h * 0.25f),
            end = Offset(w * 0.71f, h * 0.43f),
            strokeWidth = strokePx,
            cap = StrokeCap.Round
          )
          // Marker collar before the chisel tip
          drawLine(
            color = iconTint,
            start = Offset(w * 0.33f, h * 0.45f),
            end = Offset(w * 0.51f, h * 0.63f),
            strokeWidth = strokePx,
            cap = StrokeCap.Round
          )
          // Highlight stroke below the chisel tip
          drawLine(
            color = iconTint,
            start = Offset(w * 0.15f, h * 0.84f),
            end = Offset(w * 0.85f, h * 0.84f),
            strokeWidth = strokePx,
            cap = StrokeCap.Round
          )
        }

        SelectionCanvasIconType.SHARE -> {
          drawLine(
            color = iconTint,
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
          drawPath(arrowHead, color = iconTint, style = stroke)

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

        SelectionCanvasIconType.TRANSLATE -> {
          // Clean geometric Latin letter 'A' (upper-left) paired with language glyph '文' (lower-right)
          val letterA = Path().apply {
            moveTo(w * 0.13f, h * 0.56f)
            lineTo(w * 0.28f, h * 0.14f)
            lineTo(w * 0.43f, h * 0.56f)
          }
          drawPath(letterA, color = iconTint, style = stroke)
          drawLine(
            color = iconTint,
            start = Offset(w * 0.18f, h * 0.44f),
            end = Offset(w * 0.38f, h * 0.44f),
            strokeWidth = strokePx,
            cap = StrokeCap.Round
          )

          // Language symbol '文'
          drawLine(
            color = iconTint,
            start = Offset(w * 0.67f, h * 0.34f),
            end = Offset(w * 0.67f, h * 0.44f),
            strokeWidth = strokePx,
            cap = StrokeCap.Round
          )
          drawLine(
            color = iconTint,
            start = Offset(w * 0.47f, h * 0.44f),
            end = Offset(w * 0.87f, h * 0.44f),
            strokeWidth = strokePx,
            cap = StrokeCap.Round
          )
          val leftSweep = Path().apply {
            moveTo(w * 0.77f, h * 0.44f)
            quadraticTo(w * 0.71f, h * 0.68f, w * 0.49f, h * 0.85f)
          }
          drawPath(leftSweep, color = iconTint, style = stroke)
          val rightSweep = Path().apply {
            moveTo(w * 0.57f, h * 0.52f)
            quadraticTo(w * 0.67f, h * 0.70f, w * 0.85f, h * 0.85f)
          }
          drawPath(rightSweep, color = iconTint, style = stroke)
        }

        SelectionCanvasIconType.DICTIONARY -> {
          // Minimalist symmetric open book
          val leftPage = Path().apply {
            moveTo(w * 0.50f, h * 0.26f)
            quadraticTo(w * 0.32f, h * 0.17f, w * 0.13f, h * 0.23f)
            lineTo(w * 0.13f, h * 0.77f)
            quadraticTo(w * 0.32f, h * 0.71f, w * 0.50f, h * 0.80f)
            close()
          }
          val rightPage = Path().apply {
            moveTo(w * 0.50f, h * 0.26f)
            quadraticTo(w * 0.68f, h * 0.17f, w * 0.87f, h * 0.23f)
            lineTo(w * 0.87f, h * 0.77f)
            quadraticTo(w * 0.68f, h * 0.71f, w * 0.50f, h * 0.80f)
            close()
          }
          drawPath(leftPage, color = iconTint, style = stroke)
          drawPath(rightPage, color = iconTint, style = stroke)
          drawLine(
            color = iconTint,
            start = Offset(w * 0.50f, h * 0.26f),
            end = Offset(w * 0.50f, h * 0.80f),
            strokeWidth = strokePx,
            cap = StrokeCap.Round
          )
        }

        SelectionCanvasIconType.MORE_MENU -> {
          // Three vertical dots (⋮), icon only
          val dotRadius = 1.65.dp.toPx()
          drawCircle(
            color = iconTint,
            radius = dotRadius,
            center = Offset(w * 0.50f, h * 0.22f)
          )
          drawCircle(
            color = iconTint,
            radius = dotRadius,
            center = Offset(w * 0.50f, h * 0.50f)
          )
          drawCircle(
            color = iconTint,
            radius = dotRadius,
            center = Offset(w * 0.50f, h * 0.78f)
          )
        }
      }
    }

    if (!label.isNullOrBlank()) {
      Spacer(Modifier.height(4.dp))
      Text(
        text = label,
        fontSize = labelFontSize,
        lineHeight = 12.sp,
        fontWeight = FontWeight.Medium,
        color = SelectionBarInk,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip
      )
    }
  }
}
