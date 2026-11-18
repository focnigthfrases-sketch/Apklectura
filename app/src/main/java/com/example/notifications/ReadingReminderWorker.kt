package com.example.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.MainActivity
import com.example.R
import com.example.data.local.LettorDatabase
import java.util.Calendar
import java.util.concurrent.TimeUnit

class ReadingReminderWorker(
  appContext: Context,
  workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

  override suspend fun doWork(): Result {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val granted = ContextCompat.checkSelfPermission(
        applicationContext,
        Manifest.permission.POST_NOTIFICATIONS
      ) == PackageManager.PERMISSION_GRANTED
      if (!granted) return Result.success()
    }

    createNotificationChannel(applicationContext)

    val books = LettorDatabase.getInstance(applicationContext).bookDao().getAllBooksOnce()
    val latestBook = books
      .filter { !it.isFinished }
      .maxByOrNull { maxOf(it.lastOpenedAt, it.addedAt) }

    val title = if (latestBook != null) {
      "Tu lectura te espera: ${latestBook.title}"
    } else {
      "Hora de leer en Lettor"
    }
    val text = if (latestBook != null) {
      val pct = (latestBook.progressFraction * 100).toInt().coerceIn(0, 100)
      "Llevas un $pct% completado. Dedica unos minutos a tu objetivo diario."
    } else {
      "Abre un libro y avanza en tu meta diaria de lectura."
    }

    val intent = Intent(applicationContext, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
      if (latestBook != null) {
        putExtra(EXTRA_WIDGET_BOOK_ID, latestBook.id)
      }
    }
    val pendingIntent = PendingIntent.getActivity(
      applicationContext,
      1001,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val largeToucanIcon = ToucanArtwork.getOrCreateBitmap(applicationContext)

    val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_notification_toucan)
      .setLargeIcon(largeToucanIcon)
      .setContentTitle(title)
      .setContentText(text)
      .setPriority(NotificationCompat.PRIORITY_DEFAULT)
      .setAutoCancel(true)
      .setContentIntent(pendingIntent)
      .build()

    NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
    return Result.success()
  }

  companion object {
    const val CHANNEL_ID = "lettor_reading_reminders"
    const val NOTIFICATION_ID = 7021
    const val WORK_NAME = "lettor_daily_reading_reminder"
    const val EXTRA_WIDGET_BOOK_ID = "extra_widget_book_id"

    fun createNotificationChannel(context: Context) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
          CHANNEL_ID,
          "Recordatorios de lectura",
          NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
          description = "Recordatorios diarios configurables para mantener tu hábito de lectura."
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
      }
    }

    fun scheduleDailyReminder(context: Context, enabled: Boolean, hour: Int, minute: Int) {
      val workManager = WorkManager.getInstance(context)
      if (!enabled) {
        workManager.cancelUniqueWork(WORK_NAME)
        return
      }

      val now = Calendar.getInstance()
      val target = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (!after(now)) {
          add(Calendar.DAY_OF_YEAR, 1)
        }
      }
      val delayMillis = (target.timeInMillis - now.timeInMillis).coerceAtLeast(60_000L)

      val request = PeriodicWorkRequestBuilder<ReadingReminderWorker>(24, TimeUnit.HOURS)
        .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
        .build()

      workManager.enqueueUniquePeriodicWork(
        WORK_NAME,
        ExistingPeriodicWorkPolicy.UPDATE,
        request
      )
    }
  }
}
