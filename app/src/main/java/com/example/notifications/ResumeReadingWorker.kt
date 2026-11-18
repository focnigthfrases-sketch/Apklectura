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
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.MainActivity
import com.example.R
import com.example.data.local.BookEntity
import com.example.data.local.BookFormat
import com.example.data.local.LettorDatabase
import com.example.data.preferences.ReaderPreferencesRepository
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first

object ResumeReadingScheduler {

  private const val TAG_RESUME_WORK = "lettor_resume_reading_all"
  private const val WORK_PREFIX = "lettor_resume_book_"
  const val KEY_BOOK_ID = "key_book_id"

  const val QUIET_MORNING_HOUR = 9
  const val QUIET_MORNING_MINUTE = 30
  const val QUIET_NIGHT_HOUR = 22
  const val MIN_REOPEN_SILENCE_MILLIS = 12L * 3_600_000L

  fun uniqueWorkName(bookId: Long): String = "$WORK_PREFIX$bookId"

  fun isQuietHour(
    timeMillis: Long = System.currentTimeMillis(),
    timeZone: TimeZone = TimeZone.getDefault()
  ): Boolean {
    val cal = Calendar.getInstance(timeZone).apply { timeInMillis = timeMillis }
    val hour = cal.get(Calendar.HOUR_OF_DAY)
    return hour < QUIET_MORNING_HOUR || hour >= QUIET_NIGHT_HOUR
  }

  /**
   * Computes a respectful trigger timestamp at least [delayHours] ahead and at least
   * [cooldownMillis] after [lastNotifiedAtMillis], strictly outside quiet hours (22:00 - 08:59).
   */
  fun calculateNextAllowedTriggerMillis(
    nowMillis: Long,
    delayHours: Int,
    lastNotifiedAtMillis: Long = 0L,
    cooldownMillis: Long = delayHours.coerceIn(24, 48) * 3_600_000L,
    timeZone: TimeZone = TimeZone.getDefault()
  ): Long {
    val safeDelayMillis = delayHours.coerceIn(24, 48) * 3_600_000L
    var candidateMillis = nowMillis + safeDelayMillis
    if (lastNotifiedAtMillis > 0L) {
      candidateMillis = maxOf(candidateMillis, lastNotifiedAtMillis + cooldownMillis)
    }

    val cal = Calendar.getInstance(timeZone).apply { timeInMillis = candidateMillis }
    val hour = cal.get(Calendar.HOUR_OF_DAY)
    when {
      hour < QUIET_MORNING_HOUR -> {
        cal.set(Calendar.HOUR_OF_DAY, QUIET_MORNING_HOUR)
        cal.set(Calendar.MINUTE, QUIET_MORNING_MINUTE)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
      }
      hour >= QUIET_NIGHT_HOUR -> {
        cal.add(Calendar.DAY_OF_YEAR, 1)
        cal.set(Calendar.HOUR_OF_DAY, QUIET_MORNING_HOUR)
        cal.set(Calendar.MINUTE, QUIET_MORNING_MINUTE)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
      }
    }
    return cal.timeInMillis
  }

  fun formatProgressSummary(book: BookEntity): String {
    val pct = (book.progressFraction * 100f).roundToInt().coerceIn(0, 100)
    val total = book.totalUnits.coerceAtLeast(1)
    val current = (book.currentUnit + 1).coerceIn(1, total)
    val unitNoun = if (book.format == BookFormat.EPUB) "Capítulo" else "Página"
    return "$unitNoun $current de $total · $pct %"
  }

  suspend fun scheduleForBook(context: Context, bookId: Long) {
    if (bookId <= 0L) return
    val appContext = context.applicationContext
    val prefsRepo = ReaderPreferencesRepository(appContext)
    val prefs = prefsRepo.preferencesFlow.first()
    if (!prefs.resumeReminderEnabled) {
      cancelForBook(appContext, bookId)
      return
    }

    val book = LettorDatabase.getInstance(appContext).bookDao().getBookById(bookId)
    if (book == null || book.isFinished) {
      cancelForBook(appContext, bookId)
      return
    }

    val now = System.currentTimeMillis()
    val delayHours = prefs.resumeReminderDelayHours.coerceIn(24, 48)
    val cooldownMillis = delayHours * 3_600_000L
    val lastSent = prefsRepo.getLastResumeNotifiedAt(bookId)
    val triggerAt = calculateNextAllowedTriggerMillis(
      nowMillis = now,
      delayHours = delayHours,
      lastNotifiedAtMillis = lastSent,
      cooldownMillis = cooldownMillis
    )
    val delayMillis = (triggerAt - now).coerceAtLeast(60_000L)

    enqueueWork(appContext, bookId, delayMillis)
  }

  internal fun enqueueWork(context: Context, bookId: Long, delayMillis: Long) {
    val request = OneTimeWorkRequestBuilder<ResumeReadingWorker>()
      .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
      .setInputData(workDataOf(KEY_BOOK_ID to bookId))
      .addTag(TAG_RESUME_WORK)
      .build()

    WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
      uniqueWorkName(bookId),
      ExistingWorkPolicy.REPLACE,
      request
    )
  }

  fun cancelForBook(context: Context, bookId: Long) {
    if (bookId <= 0L) return
    WorkManager.getInstance(context.applicationContext).cancelUniqueWork(uniqueWorkName(bookId))
  }

  fun cancelAll(context: Context) {
    WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(TAG_RESUME_WORK)
  }
}

class ResumeReadingWorker(
  appContext: Context,
  workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

  override suspend fun doWork(): Result {
    val bookId = inputData.getLong(ResumeReadingScheduler.KEY_BOOK_ID, -1L)
    if (bookId <= 0L) return Result.success()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val granted = ContextCompat.checkSelfPermission(
        applicationContext,
        Manifest.permission.POST_NOTIFICATIONS
      ) == PackageManager.PERMISSION_GRANTED
      if (!granted) return Result.success()
    }

    val prefsRepo = ReaderPreferencesRepository(applicationContext)
    val prefs = prefsRepo.preferencesFlow.first()
    if (!prefs.resumeReminderEnabled) return Result.success()

    val now = System.currentTimeMillis()
    val delayHours = prefs.resumeReminderDelayHours.coerceIn(24, 48)
    val cooldownMillis = delayHours * 3_600_000L

    // Guard against OS/Doze waking worker in the middle of the night ("madrugada")
    if (ResumeReadingScheduler.isQuietHour(now)) {
      val nextAllowed = ResumeReadingScheduler.calculateNextAllowedTriggerMillis(
        nowMillis = now,
        delayHours = 24,
        lastNotifiedAtMillis = prefsRepo.getLastResumeNotifiedAt(bookId),
        cooldownMillis = cooldownMillis
      )
      // Re-schedule to the upcoming morning window (e.g., 09:30 today if <09:00)
      val cal = Calendar.getInstance().apply {
        timeInMillis = now
        if (get(Calendar.HOUR_OF_DAY) >= ResumeReadingScheduler.QUIET_NIGHT_HOUR) {
          add(Calendar.DAY_OF_YEAR, 1)
        }
        set(Calendar.HOUR_OF_DAY, ResumeReadingScheduler.QUIET_MORNING_HOUR)
        set(Calendar.MINUTE, ResumeReadingScheduler.QUIET_MORNING_MINUTE)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
      }
      val morningDelay = (maxOf(cal.timeInMillis, nextAllowed.coerceAtMost(cal.timeInMillis)) - now)
        .coerceAtLeast(60_000L)
      ResumeReadingScheduler.enqueueWork(applicationContext, bookId, morningDelay)
      return Result.success()
    }

    // Strict per-book & per-period limit
    val lastSent = prefsRepo.getLastResumeNotifiedAt(bookId)
    if (lastSent > 0L && (now - lastSent) < cooldownMillis) {
      return Result.success()
    }

    val book = LettorDatabase.getInstance(applicationContext).bookDao().getBookById(bookId)
      ?: return Result.success()
    if (book.isFinished) return Result.success()
    if (book.lastOpenedAt > 0L && (now - book.lastOpenedAt) < ResumeReadingScheduler.MIN_REOPEN_SILENCE_MILLIS) {
      return Result.success()
    }

    createNotificationChannel(applicationContext)

    val progressLine = ResumeReadingScheduler.formatProgressSummary(book)
    val contentTitle = "Te quedaste leyendo: ${book.title}"
    val contentBody = "$progressLine · Toca para volver a la última página."

    val intent = Intent(applicationContext, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
      putExtra(ReadingReminderWorker.EXTRA_WIDGET_BOOK_ID, book.id)
    }
    val pendingIntent = PendingIntent.getActivity(
      applicationContext,
      20_000 + (book.id % 10_000).toInt(),
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val largeToucanIcon = ToucanArtwork.getOrCreateBitmap(applicationContext)

    val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_notification_toucan)
      .setLargeIcon(largeToucanIcon)
      .setContentTitle(contentTitle)
      .setContentText(contentBody)
      .setStyle(NotificationCompat.BigTextStyle().bigText(contentBody))
      .setPriority(NotificationCompat.PRIORITY_DEFAULT)
      .setCategory(NotificationCompat.CATEGORY_REMINDER)
      .setAutoCancel(true)
      .setContentIntent(pendingIntent)
      .build()

    NotificationManagerCompat.from(applicationContext)
      .notify(NOTIFICATION_ID_BASE + (book.id % 10_000).toInt(), notification)

    prefsRepo.markResumeNotified(book.id, now)
    return Result.success()
  }

  companion object {
    const val CHANNEL_ID = "lettor_resume_reading"
    const val NOTIFICATION_ID_BASE = 8100

    fun createNotificationChannel(context: Context) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
          CHANNEL_ID,
          "Continuar leyendo",
          NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
          description = "Aviso amable con tu progreso cuando dejas un libro a medias."
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
      }
    }
  }
}
