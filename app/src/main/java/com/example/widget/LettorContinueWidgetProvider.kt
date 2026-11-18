package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.local.LettorDatabase
import com.example.notifications.ReadingReminderWorker
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LettorContinueWidgetProvider : AppWidgetProvider() {

  override fun onUpdate(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetIds: IntArray
  ) {
    val pendingResult = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
      try {
        updateWidgetsInternal(context, appWidgetManager, appWidgetIds)
      } finally {
        pendingResult.finish()
      }
    }
  }

  companion object {
    fun requestRefresh(context: Context) {
      CoroutineScope(Dispatchers.IO).launch {
        runCatching {
          val manager = AppWidgetManager.getInstance(context)
          val ids = manager.getAppWidgetIds(ComponentName(context, LettorContinueWidgetProvider::class.java))
          if (ids.isNotEmpty()) {
            updateWidgetsInternal(context, manager, ids)
          }
        }
      }
    }

    private suspend fun updateWidgetsInternal(
      context: Context,
      manager: AppWidgetManager,
      appWidgetIds: IntArray
    ) {
      val books = LettorDatabase.getInstance(context).bookDao().getAllBooksOnce()
      val activeBook = books
        .filter { !it.isFinished }
        .maxByOrNull { maxOf(it.lastOpenedAt, it.addedAt) }
        ?: books.firstOrNull()

      for (widgetId in appWidgetIds) {
        val views = RemoteViews(context.packageName, R.layout.widget_continue_reading)

        if (activeBook != null) {
          val pct = (activeBook.progressFraction * 100f).roundToInt().coerceIn(0, 100)
          views.setTextViewText(R.id.widget_book_title, activeBook.title)
          views.setTextViewText(R.id.widget_book_author, "${activeBook.author} · ${activeBook.format.name}")
          views.setTextViewText(R.id.widget_progress_label, "$pct% leído")
          views.setProgressBar(R.id.widget_progress_bar, 100, pct, false)

          val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ReadingReminderWorker.EXTRA_WIDGET_BOOK_ID, activeBook.id)
          }
          val pi = PendingIntent.getActivity(
            context,
            widgetId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
          )
          views.setOnClickPendingIntent(R.id.widget_root, pi)
          views.setOnClickPendingIntent(R.id.widget_continue_button, pi)
        } else {
          views.setTextViewText(R.id.widget_book_title, "Tu biblioteca Lettor")
          views.setTextViewText(R.id.widget_book_author, "Toca para importar un EPUB o PDF")
          views.setTextViewText(R.id.widget_progress_label, "0%")
          views.setProgressBar(R.id.widget_progress_bar, 100, 0, false)

          val launchIntent = Intent(context, MainActivity::class.java)
          val pi = PendingIntent.getActivity(
            context,
            widgetId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
          )
          views.setOnClickPendingIntent(R.id.widget_root, pi)
          views.setOnClickPendingIntent(R.id.widget_continue_button, pi)
        }

        manager.updateAppWidget(widgetId, views)
      }
    }
  }
}
