package com.readicop.lettor.ui.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToDown

/**
 * Single-finger tap, optional double-tap, and long-press detector that strictly suppresses all
 * callbacks whenever two or more fingers touch the screen (and keeps suppression active until
 * every pointer lifts).
 */
suspend fun PointerInputScope.detectSingleFingerTapAndLongPress(
  shouldSkipDoubleTap: () -> Boolean = { false },
  onTap: ((Offset) -> Unit)? = null,
  onDoubleTap: ((Offset) -> Unit)? = null,
  onLongPress: ((Offset) -> Unit)? = null
) {
  awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    val touchSlop = viewConfiguration.touchSlop
    val longPressTimeout = viewConfiguration.longPressTimeoutMillis
    val downPos = down.position
    val deadlineUptime = down.uptimeMillis + longPressTimeout
    var lastUptime = down.uptimeMillis

    var multiTouchSeen = currentEvent.changes.count { it.pressed } > 1 || currentEvent.changes.size > 1
    var movedOrConsumed = down.isConsumed
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
        if (event.changes.any { it.isConsumed }) {
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
    if (onDoubleTap == null || shouldSkipDoubleTap()) {
      upChange.consume()
      onTap?.invoke(upChange.position)
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
        val newlyPressed = event.changes.firstOrNull { it.changedToDown() && !it.isConsumed }
        if (newlyPressed != null) {
          found = newlyPressed
        }
      }
      found
    }

    if (secondDown == null) {
      if (!doubleTapMultiTouch &&
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
      if (event.changes.any { it.isConsumed }) {
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
    } else {
      onTap?.invoke(upChange.position)
    }
  }
}
