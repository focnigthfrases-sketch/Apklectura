package com.readicop.lettor.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.launch

internal fun shouldHideImeInsteadOfDismissing(imeBottomPx: Int): Boolean = imeBottomPx > 0

/**
 * Shared ModalBottomSheet wrapper that ensures pressing the Android Back button while the soft
 * keyboard (IME) is visible hides only the keyboard and keeps the sheet open. A subsequent Back
 * press with the keyboard closed dismisses the sheet normally.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LettorModalBottomSheet(
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  sheetState: SheetState = rememberModalBottomSheetState(),
  containerColor: Color = BottomSheetDefaults.ContainerColor,
  contentColor: Color = contentColorFor(containerColor),
  content: @Composable ColumnScope.() -> Unit
) {
  val scope = rememberCoroutineScope()
  val density = LocalDensity.current
  val keyboardController = LocalSoftwareKeyboardController.current
  val focusManager = LocalFocusManager.current

  ModalBottomSheet(
    onDismissRequest = onDismissRequest,
    modifier = modifier,
    sheetState = sheetState,
    containerColor = containerColor,
    contentColor = contentColor,
    properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false)
  ) {
    val isImeVisible = shouldHideImeInsteadOfDismissing(WindowInsets.ime.getBottom(density))
    val latestIsImeVisible by rememberUpdatedState(isImeVisible)
    val latestOnDismiss by rememberUpdatedState(onDismissRequest)

    BackHandler(enabled = true) {
      if (latestIsImeVisible) {
        keyboardController?.hide()
        focusManager.clearFocus(force = true)
      } else {
        scope.launch {
          runCatching { sheetState.hide() }
          latestOnDismiss()
        }
      }
    }

    content()
  }
}

/**
 * Intercepts Back presses inside Compose Dialogs / AlertDialogs (used with
 * DialogProperties(dismissOnBackPress = false)) so Back closes an open soft keyboard first
 * instead of dismissing the dialog underneath it.
 */
@Composable
fun LettorImeBackDialogHandler(onDismiss: () -> Unit) {
  val density = LocalDensity.current
  val keyboardController = LocalSoftwareKeyboardController.current
  val focusManager = LocalFocusManager.current
  val isImeVisible = shouldHideImeInsteadOfDismissing(WindowInsets.ime.getBottom(density))
  val latestIsImeVisible by rememberUpdatedState(isImeVisible)
  val latestOnDismiss by rememberUpdatedState(onDismiss)

  BackHandler(enabled = true) {
    if (latestIsImeVisible) {
      keyboardController?.hide()
      focusManager.clearFocus(force = true)
    } else {
      latestOnDismiss()
    }
  }
}
