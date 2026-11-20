package com.readicop.lettor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

private fun snapValueToRange(
  rawFraction: Float,
  valueRange: ClosedFloatingPointRange<Float>,
  steps: Int
): Float {
  val clampedFraction = rawFraction.coerceIn(0f, 1f)
  val snappedFraction = if (steps > 0) {
    val segments = steps + 1
    (clampedFraction * segments).roundToInt().toFloat() / segments.toFloat()
  } else {
    clampedFraction
  }
  val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-5f)
  return (valueRange.start + snappedFraction * span).coerceIn(valueRange.start, valueRange.endInclusive)
}

/**
 * Thin editorial slider: 3.dp track bar and a compact circular accent thumb.
 */
@Composable
fun LettorThinSlider(
  value: Float,
  onValueChange: (Float) -> Unit,
  valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
  steps: Int = 0,
  onValueChangeFinished: (() -> Unit)? = null,
  accentColor: Color = MaterialTheme.colorScheme.primary,
  trackColor: Color = MaterialTheme.colorScheme.outlineVariant,
  enabled: Boolean = true,
  modifier: Modifier = Modifier
) {
  val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-5f)
  val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)

  val latestOnValueChange by rememberUpdatedState(onValueChange)
  val latestOnFinished by rememberUpdatedState(onValueChangeFinished)
  val latestRange by rememberUpdatedState(valueRange)
  val latestSteps by rememberUpdatedState(steps)
  var isDragging by remember { mutableStateOf(false) }

  val density = LocalDensity.current
  val thumbPadPx = with(density) { 8.dp.toPx() }

  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(48.dp)
      .semantics {
        progressBarRangeInfo = ProgressBarRangeInfo(
          current = value.coerceIn(valueRange.start, valueRange.endInclusive),
          range = valueRange,
          steps = steps
        )
        if (enabled) {
          setProgress { target ->
            val f = ((target - valueRange.start) / span).coerceIn(0f, 1f)
            latestOnValueChange(snapValueToRange(f, latestRange, latestSteps))
            latestOnFinished?.invoke()
            true
          }
        }
      }
      .then(
        if (enabled) {
          Modifier
            .pointerInput(valueRange, steps) {
              detectTapGestures { offset ->
                val usableW = (size.width - thumbPadPx * 2f).coerceAtLeast(1f)
                val f = (offset.x - thumbPadPx) / usableW
                latestOnValueChange(snapValueToRange(f, latestRange, latestSteps))
                latestOnFinished?.invoke()
              }
            }
            .pointerInput(valueRange, steps) {
              detectHorizontalDragGestures(
                onDragStart = { offset ->
                  isDragging = true
                  val usableW = (size.width - thumbPadPx * 2f).coerceAtLeast(1f)
                  val f = (offset.x - thumbPadPx) / usableW
                  latestOnValueChange(snapValueToRange(f, latestRange, latestSteps))
                },
                onDragEnd = {
                  isDragging = false
                  latestOnFinished?.invoke()
                },
                onDragCancel = {
                  isDragging = false
                  latestOnFinished?.invoke()
                },
                onHorizontalDrag = { change, _ ->
                  change.consume()
                  val usableW = (size.width - thumbPadPx * 2f).coerceAtLeast(1f)
                  val f = (change.position.x - thumbPadPx) / usableW
                  latestOnValueChange(snapValueToRange(f, latestRange, latestSteps))
                }
              )
            }
        } else {
          Modifier
        }
      )
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      val trackHeightPx = 3.dp.toPx()
      val dotRadiusPx = 6.dp.toPx() // small 12.dp circular accent dot
      val pad = thumbPadPx.coerceAtMost(size.width * 0.25f)
      val startX = pad
      val endX = (size.width - pad).coerceAtLeast(startX + 1f)
      val centerY = size.height * 0.5f
      val thumbX = startX + (endX - startX) * fraction

      val activeTint = if (enabled) accentColor else accentColor.copy(alpha = 0.4f)
      val inactiveTint = if (enabled) trackColor else trackColor.copy(alpha = 0.45f)

      // Full 3.dp background track
      drawLine(
        color = inactiveTint,
        start = Offset(startX, centerY),
        end = Offset(endX, centerY),
        strokeWidth = trackHeightPx,
        cap = StrokeCap.Round
      )

      // Active 3.dp progress bar
      if (thumbX > startX) {
        drawLine(
          color = activeTint,
          start = Offset(startX, centerY),
          end = Offset(thumbX, centerY),
          strokeWidth = trackHeightPx,
          cap = StrokeCap.Round
        )
      }

      // Soft halo when dragging
      if (isDragging && enabled) {
        drawCircle(
          color = activeTint.copy(alpha = 0.20f),
          radius = dotRadiusPx + 5.dp.toPx(),
          center = Offset(thumbX, centerY)
        )
      }

      // Small circular accent thumb
      drawCircle(
        color = activeTint,
        radius = dotRadiusPx,
        center = Offset(thumbX, centerY)
      )
    }
  }
}

/**
 * Ruler-style brightness slider:
 * horizontal baseline with fine vertical ticks, a top accent triangle indicator,
 * and a highlighted accent mark at the active position.
 */
@Composable
fun LettorRulerBrightnessSlider(
  value: Float,
  onValueChange: (Float) -> Unit,
  valueRange: ClosedFloatingPointRange<Float> = 0.01f..1.0f,
  isAuto: Boolean = false,
  accentColor: Color = MaterialTheme.colorScheme.primary,
  tickColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f),
  baselineColor: Color = MaterialTheme.colorScheme.outlineVariant,
  modifier: Modifier = Modifier
) {
  var isDragging by remember { mutableStateOf(false) }
  var localDragValue by remember { mutableStateOf(value) }
  val effectiveValue = if (isDragging) localDragValue else value

  val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-5f)
  val fraction = ((effectiveValue - valueRange.start) / span).coerceIn(0f, 1f)

  val latestOnValueChange by rememberUpdatedState(onValueChange)
  val latestRange by rememberUpdatedState(valueRange)
  val density = LocalDensity.current
  val edgePadPx = with(density) { 8.dp.toPx() }

  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(48.dp)
      .semantics {
        progressBarRangeInfo = ProgressBarRangeInfo(
          current = effectiveValue.coerceIn(valueRange.start, valueRange.endInclusive),
          range = valueRange,
          steps = 0
        )
        setProgress { target ->
          val f = ((target - valueRange.start) / span).coerceIn(0f, 1f)
          latestOnValueChange(snapValueToRange(f, latestRange, 0))
          true
        }
      }
      .pointerInput(valueRange) {
        val touchSlop = viewConfiguration.touchSlop
        awaitEachGesture {
          val down = awaitFirstDown(requireUnconsumed = false)
          val startPos = down.position
          var horizontalDragStarted = false
          var verticalScrollDetected = false

          fun emitAtX(x: Float) {
            val usableW = (size.width - edgePadPx * 2f).coerceAtLeast(1f)
            val f = (x - edgePadPx) / usableW
            val next = snapValueToRange(f, latestRange, 0)
            localDragValue = next
            latestOnValueChange(next)
          }

          while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
              if (!horizontalDragStarted && !verticalScrollDetected && !change.isConsumed) {
                emitAtX(change.position.x)
              }
              isDragging = false
              break
            }
            val dx = change.position.x - startPos.x
            val dy = change.position.y - startPos.y
            if (!horizontalDragStarted && !verticalScrollDetected) {
              if (abs(dx) > touchSlop && abs(dx) >= abs(dy)) {
                horizontalDragStarted = true
                isDragging = true
                change.consume()
                emitAtX(change.position.x)
              } else if (abs(dy) > touchSlop && abs(dy) > abs(dx)) {
                verticalScrollDetected = true
                isDragging = false
                break
              }
            } else if (horizontalDragStarted) {
              change.consume()
              emitAtX(change.position.x)
            }
          }
          isDragging = false
        }
      }
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      val pad = edgePadPx.coerceAtMost(size.width * 0.25f)
      val startX = pad
      val endX = (size.width - pad).coerceAtLeast(startX + 1f)
      val usableW = endX - startX

      val baselineY = size.height * 0.78f
      val currentX = startX + usableW * fraction
      val indicatorAlpha = if (isAuto && !isDragging) 0.55f else 1f
      val activeAccent = accentColor.copy(alpha = indicatorAlpha)

      // Horizontal ruler baseline bar
      drawLine(
        color = baselineColor,
        start = Offset(startX, baselineY),
        end = Offset(endX, baselineY),
        strokeWidth = 1.5.dp.toPx(),
        cap = StrokeCap.Round
      )

      // Many fine vertical ticks across the ruler
      val tickCount = 32
      val fineStrokePx = 1.dp.toPx()
      val minorHeightPx = 7.dp.toPx()
      val majorHeightPx = 12.dp.toPx()

      for (i in 0..tickCount) {
        val tickX = startX + (usableW * i.toFloat() / tickCount.toFloat())
        val isMajor = i % 4 == 0
        val tickH = if (isMajor) majorHeightPx else minorHeightPx
        val distToCurrent = abs(tickX - currentX)
        val nearActive = distToCurrent < (usableW / tickCount.toFloat()) * 0.45f

        drawLine(
          color = if (nearActive) activeAccent else tickColor,
          start = Offset(tickX, baselineY),
          end = Offset(tickX, baselineY - tickH),
          strokeWidth = if (nearActive) 1.7.dp.toPx() else fineStrokePx,
          cap = StrokeCap.Round
        )
      }

      // Highlighted vertical accent mark right at the exact current position
      drawLine(
        color = activeAccent,
        start = Offset(currentX, baselineY + 1.dp.toPx()),
        end = Offset(currentX, baselineY - 16.dp.toPx()),
        strokeWidth = 2.2.dp.toPx(),
        cap = StrokeCap.Round
      )

      // Downward-pointing accent triangle indicator above the current position
      val triTopY = baselineY - 25.dp.toPx()
      val triBottomY = baselineY - 19.dp.toPx()
      val triHalfW = 4.5.dp.toPx()
      val triangle = Path().apply {
        moveTo(currentX, triBottomY)
        lineTo(currentX - triHalfW, triTopY)
        lineTo(currentX + triHalfW, triTopY)
        close()
      }
      drawPath(
        path = triangle,
        color = activeAccent,
        style = Fill
      )
    }
  }
}
