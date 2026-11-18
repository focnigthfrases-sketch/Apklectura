package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.preferences.ReaderPreferences
import com.example.notifications.ReadingReminderWorker
import com.example.ui.navigation.LettorAppNavigation
import com.example.ui.theme.LettorTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

  private val pendingDeepLinkBookId = MutableStateFlow<Long?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    extractDeepLink(intent)

    val appContainer = (application as LettorApplication).container

    setContent {
      val prefs by appContainer.preferencesRepository.preferencesFlow
        .collectAsStateWithLifecycle(initialValue = ReaderPreferences())

      LettorTheme(appThemeMode = prefs.appThemeMode) {
        LettorAppNavigation(
          appContainer = appContainer,
          pendingDeepLinkBookId = pendingDeepLinkBookId,
          onConsumeDeepLink = { pendingDeepLinkBookId.value = null }
        )
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    extractDeepLink(intent)
  }

  private fun extractDeepLink(intent: Intent?) {
    val bookId = intent?.getLongExtra(ReadingReminderWorker.EXTRA_WIDGET_BOOK_ID, -1L) ?: -1L
    if (bookId > 0L) {
      pendingDeepLinkBookId.value = bookId
      intent?.removeExtra(ReadingReminderWorker.EXTRA_WIDGET_BOOK_ID)
    }
  }
}
