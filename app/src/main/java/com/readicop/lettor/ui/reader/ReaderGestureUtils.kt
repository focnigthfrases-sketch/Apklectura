package com.readicop.lettor.ui.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.unit.dp

/**
 * Single configurable hold duration (in milliseconds) required before text selection starts
 * in the PDF reader (target range: 400-500 ms) so brief touches never trigger accidental selection.
 */
const val SELECTION_LONG_PRESS_DELAY_MS = 480L

/**
 * Maximum press duration (in milliseconds) for a single-finger touch in the PDF reader to count
 * as a deliberate tap (prevents brief stationary rests from toggling controls).
 */
const val PDF_DELIBERATE_TAP_MAX_DURATION_MS = 260L

/**
 * Safe insets for immersive reading content (EPUB & PDF): extends under hidden system bars
 * while keeping text, page edges, and footers clear of top/side display cutouts (notches/cameras)
 * and the bottom system gesture area.
 */
@Composable
fun rememberReaderContentInsets(): WindowInsets {
  val minEdgeMargin = WindowInsets(top = 10.dp, bottom = 14.dp)
  val cutout = WindowInsets.displayCutout
  val bottomGestures = WindowInsets.mandatorySystemGestures.only(WindowInsetsSides.Bottom)
  return minEdgeMargin.union(cutout).union(bottomGestures)
}

/**
 * Safe top + horizontal insets for reader control bars and selection overlays so buttons and
 * floating menus never collide with notches, punch-holes, or transient status bars.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun rememberReaderControlsTopInsets(): WindowInsets {
  return WindowInsets.statusBarsIgnoringVisibility
    .union(WindowInsets.displayCutout)
    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
}

/**
 * Safe bottom + horizontal insets for reader bottom bars (Auto-scroll, Sticky Note, Timer,
 * page scrubber, TTS bar, floating menu) so interactive buttons always stay fully visible above
 * the navigation / gesture zone even while system bars are hidden in immersive mode.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun rememberReaderControlsBottomInsets(): WindowInsets {
  return WindowInsets.navigationBarsIgnoringVisibility
    .union(WindowInsets.mandatorySystemGestures)
    .union(WindowInsets.displayCutout)
    .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
}

/**
 * Single-finger tap, optional double-tap, and long-press detector that strictly suppresses all
 * callbacks whenever two or more fingers touch the screen (and keeps suppression active until
 * every pointer lifts), and optionally enforces custom press/tap timeouts and scroll/claim guards.
 */
suspend fun PointerInputScope.detectSingleFingerTapAndLongPress(
  shouldSkipDoubleTap: () -> Boolean = { false },
  longPressTimeoutMs: Long? = null,
  maxTapDurationMs: Long? = null,
  shouldCancelGesture: () -> Boolean = { false },
  claimPointerDown: ((pointerId: Long) -> Boolean)? = null,
  onGestureEnded: ((pointerId: Long) -> Unit)? = null,
  onTap: ((Offset) -> Unit)? = null,
  onDoubleTap: ((Offset) -> Unit)? = null,
  onLongPress: ((Offset) -> Unit)? = null
) {
  awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    val pointerId = down.id.value
    try {
      if (claimPointerDown != null && !claimPointerDown(pointerId)) {
        while (currentEvent.changes.any { it.pressed }) {
          awaitPointerEvent()
        }
        return@awaitEachGesture
      }

      val touchSlop = viewConfiguration.touchSlop
      val longPressTimeout = longPressTimeoutMs ?: viewConfiguration.longPressTimeoutMillis
      val downPos = down.position
      val deadlineUptime = down.uptimeMillis + longPressTimeout
      var lastUptime = down.uptimeMillis

      var multiTouchSeen = currentEvent.changes.count { it.pressed } > 1 || currentEvent.changes.size > 1
      var movedOrConsumed = down.isConsumed || shouldCancelGesture()
      var longPressTriggered = false
      var firstUp: PointerInputChange? = null

      if (!multiTouchSeen && !movedOrConsumed) {
        while (true) {
          val remainingMs = (deadlineUptime - lastUptime).coerceAtLeast(1L)
          val event = withTimeoutOrNull(remainingMs) {
            awaitPointerEvent()
          }
          if (event == null) {
            val stillSingle = !multiTouchSeen &&
              !movedOrConsumed &&
              !shouldCancelGesture() &&
              currentEvent.changes.count { it.pressed } == 1 &&
              currentEvent.changes.size == 1
            val tracked = currentEvent.changes.firstOrNull { it.id == down.id }
            if (stillSingle && tracked != null && tracked.pressed && !tracked.isConsumed && onLongPress != null) {
              longPressTriggered = true
              onLongPress.invoke(tracked.position)
            }
            break
          }

          if (event.changes.count { it.pressed } > 1 || event.changes.size > 1) {
            multiTouchSeen = true
            break
          }
          if (event.changes.any { it.isConsumed } || shouldCancelGesture()) {
            movedOrConsumed = true
            break
          }

          val tracked = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
          if (tracked == null) {
            movedOrConsumed = true
            break
          }
          lastUptime = maxOf(lastUptime, tracked.uptimeMillis)

          if (!tracked.pressed) {
            if ((tracked.position - downPos).getDistance() <= touchSlop) {
              firstUp = tracked
            } else {
              movedOrConsumed = true
            }
            break
          }

          if ((tracked.position - downPos).getDistance() > touchSlop) {
            movedOrConsumed = true
            break
          }
        }
      }

      if (longPressTriggered || multiTouchSeen || movedOrConsumed) {
        while (currentEvent.changes.any { it.pressed }) {
          val event = awaitPointerEvent()
          if (longPressTriggered) {
            event.changes.forEach { it.consume() }
          }
        }
        return@awaitEachGesture
      }

      val upChange = firstUp ?: return@awaitEachGesture
      val pressDurationMs = (upChange.uptimeMillis - down.uptimeMillis).coerceAtLeast(0L)
      val withinTapDuration = maxTapDurationMs == null || pressDurationMs <= maxTapDurationMs

      if (onDoubleTap == null || shouldSkipDoubleTap()) {
        if (withinTapDuration && !shouldCancelGesture()) {
          upChange.consume()
          onTap?.invoke(upChange.position)
        }
        return@awaitEachGesture
      }

      // Wait briefly for a second single-finger tap for double-tap zoom
      val doubleTapWaitMs = viewConfiguration.doubleTapTimeoutMillis
      var doubleTapMultiTouch = false
      val secondDown: PointerInputChange? = withTimeoutOrNull(doubleTapWaitMs) {
        var found: PointerInputChange? = null
        while (found == null) {
          val event = awaitPointerEvent()
          if (event.changes.count { it.pressed } > 1 || event.changes.size > 1) {
            doubleTapMultiTouch = true
            break
          }
          if (event.changes.any { it.isConsumed } || shouldCancelGesture()) {
            doubleTapMultiTouch = true
            break
          }
          val newlyPressed = event.changes.firstOrNull { it.changedToDown() && !it.isConsumed }
          if (newlyPressed != null) {
            found = newlyPressed
          }
        }
        found
      }

      if (secondDown == null) {
        if (!doubleTapMultiTouch &&
          withinTapDuration &&
          !shouldCancelGesture() &&
          currentEvent.changes.count { it.pressed } <= 1 &&
          currentEvent.changes.size <= 1
        ) {
          onTap?.invoke(upChange.position)
        } else {
          while (currentEvent.changes.any { it.pressed }) {
            awaitPointerEvent()
          }
        }
        return@awaitEachGesture
      }

      val secondDownPos = secondDown.position
      val secondDeadline = secondDown.uptimeMillis + longPressTimeout
      var secondLastUptime = secondDown.uptimeMillis
      var secondUp: PointerInputChange? = null
      var secondInvalid = false

      while (true) {
        val rem = (secondDeadline - secondLastUptime).coerceAtLeast(1L)
        val event = withTimeoutOrNull(rem) { awaitPointerEvent() }
        if (event == null) {
          secondInvalid = true
          break
        }
        if (event.changes.count { it.pressed } > 1 || event.changes.size > 1) {
          secondInvalid = true
          break
        }
        if (event.changes.any { it.isConsumed } || shouldCancelGesture()) {
          secondInvalid = true
          break
        }
        val tracked = event.changes.firstOrNull { it.id == secondDown.id } ?: event.changes.firstOrNull()
        if (tracked == null) {
          secondInvalid = true
          break
        }
        secondLastUptime = maxOf(secondLastUptime, tracked.uptimeMillis)
        if (!tracked.pressed) {
          if ((tracked.position - secondDownPos).getDistance() <= touchSlop) {
            secondUp = tracked
          } else {
            secondInvalid = true
          }
          break
        }
        if ((tracked.position - secondDownPos).getDistance() > touchSlop) {
          secondInvalid = true
          break
        }
      }

      if (secondInvalid) {
        while (currentEvent.changes.any { it.pressed }) {
          awaitPointerEvent()
        }
        return@awaitEachGesture
      }

      if (secondUp != null) {
        secondUp.consume()
        onDoubleTap.invoke(secondUp.position)
      } else if (withinTapDuration && !shouldCancelGesture()) {
        onTap?.invoke(upChange.position)
      }
    } finally {
      onGestureEnded?.invoke(pointerId)
    }
  }
}
