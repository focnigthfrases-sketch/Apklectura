package com.readicop.lettor.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lettor_preferences")

enum class AppThemeMode(val label: String) {
  SYSTEM("Automático"),
  LIGHT("Claro"),
  DARK("Oscuro")
}

enum class ReaderTheme(val label: String) {
  PAPER("Papel"),
  SEPIA("Sepia"),
  NIGHT("Noche"),
  EBONY("Ébano")
}

enum class ReaderFont(val label: String) {
  LITERATA("Literata"),
  PLAYFAIR("Playfair"),
  SANS("Jakarta Sans"),
  MONO("JetBrains Mono")
}

enum class OrientationLockMode(val label: String) {
  AUTO("Libre"),
  PORTRAIT("Vertical"),
  LANDSCAPE("Horizontal")
}

const val MIN_READER_FONT_SIZE_SP = 8f
const val MAX_READER_FONT_SIZE_SP = 28f
const val MIN_READER_BRIGHTNESS = 0.01f
const val MAX_READER_BRIGHTNESS = 1.0f

fun normalizeReaderBrightness(brightness: Float): Float =
  if (brightness < 0f) -1f else brightness.coerceIn(MIN_READER_BRIGHTNESS, MAX_READER_BRIGHTNESS)

data class ReaderPreferences(
  val appThemeMode: AppThemeMode = AppThemeMode.SYSTEM,
  val readerTheme: ReaderTheme = ReaderTheme.PAPER,
  val readerFont: ReaderFont = ReaderFont.LITERATA,
  val fontSizeSp: Float = 18f,
  val lineHeightMultiplier: Float = 1.55f,
  val horizontalPaddingDp: Int = 22,
  val epubScrollMode: Boolean = false,
  val pdfScrollMode: Boolean = false,
  val pdfNightFilter: Boolean = true,
  val keepScreenOn: Boolean = true,
  val samplesSeeded: Boolean = false,
  val orientationLock: OrientationLockMode = OrientationLockMode.AUTO,
  val readerBrightness: Float = -1f, // -1f = system automatic
  val ttsSpeechRate: Float = 1.0f,
  val autoScrollSpeed: Float = 1.2f,
  val dailyGoalMinutes: Int = 20,
  val todayDate: String = currentDayKey(),
  val todayReadingSeconds: Long = 0L,
  val readingStreakDays: Int = 0,
  val estimatedWpm: Int = 210,
  val reminderEnabled: Boolean = false,
  val reminderHour: Int = 20,
  val reminderMinute: Int = 30,
  val lastActiveReadingBookId: Long = -1L,
  val adCounterDate: String = currentDayKey(),
  val dailyImportInterstitialCount: Int = 0,
  val extraToolsUnlockedToday: Boolean = false,
  val resumeReminderEnabled: Boolean = true,
  val resumeReminderDelayHours: Int = 24,
  val hasOpenedAnyBook: Boolean = false,
  val notificationRationaleShown: Boolean = false
)

fun currentDayKey(timeMillis: Long = System.currentTimeMillis()): String {
  return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timeMillis))
}

class ReaderPreferencesRepository(private val context: Context) {
  private object Keys {
    val APP_THEME_MODE = stringPreferencesKey("app_theme_mode")
    val READER_THEME = stringPreferencesKey("reader_theme")
    val READER_FONT = stringPreferencesKey("reader_font")
    val FONT_SIZE_SP = floatPreferencesKey("font_size_sp")
    val LINE_HEIGHT_MULTIPLIER = floatPreferencesKey("line_height_multiplier")
    val HORIZONTAL_PADDING_DP = intPreferencesKey("horizontal_padding_dp")
    val EPUB_SCROLL_MODE = booleanPreferencesKey("epub_scroll_mode")
    val PDF_SCROLL_MODE = booleanPreferencesKey("pdf_scroll_mode")
    val PDF_NIGHT_FILTER = booleanPreferencesKey("pdf_night_filter")
    val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
    val SAMPLES_SEEDED = booleanPreferencesKey("samples_seeded")
    val ORIENTATION_LOCK = stringPreferencesKey("orientation_lock")
    val READER_BRIGHTNESS = floatPreferencesKey("reader_brightness")
    val TTS_SPEECH_RATE = floatPreferencesKey("tts_speech_rate")
    val AUTO_SCROLL_SPEED = floatPreferencesKey("auto_scroll_speed")
    val DAILY_GOAL_MINUTES = intPreferencesKey("daily_goal_minutes")
    val TODAY_DATE = stringPreferencesKey("today_date")
    val TODAY_READING_SECONDS = longPreferencesKey("today_reading_seconds")
    val READING_STREAK_DAYS = intPreferencesKey("reading_streak_days")
    val ESTIMATED_WPM = intPreferencesKey("estimated_wpm")
    val REMINDER_ENABLED = booleanPreferencesKey("reminder_enabled")
    val REMINDER_HOUR = intPreferencesKey("reminder_hour")
    val REMINDER_MINUTE = intPreferencesKey("reminder_minute")
    val LAST_ACTIVE_READING_BOOK_ID = longPreferencesKey("last_active_reading_book_id")
    val AD_COUNTER_DATE = stringPreferencesKey("ad_counter_date")
    val DAILY_IMPORT_INTERSTITIAL_COUNT = intPreferencesKey("daily_import_interstitial_count")
    val EXTRA_TOOLS_UNLOCKED_DATE = stringPreferencesKey("extra_tools_unlocked_date")
    val RESUME_REMINDER_ENABLED = booleanPreferencesKey("resume_reminder_enabled")
    val RESUME_REMINDER_DELAY_HOURS = intPreferencesKey("resume_reminder_delay_hours")
    val HAS_OPENED_ANY_BOOK = booleanPreferencesKey("has_opened_any_book")
    val NOTIFICATION_RATIONALE_SHOWN = booleanPreferencesKey("notification_rationale_shown")
    val RESUME_NOTIF_LAST_SENT_MAP = stringPreferencesKey("resume_notif_last_sent_map")
  }

  val preferencesFlow: Flow<ReaderPreferences> = context.dataStore.data.map { prefs ->
    val today = currentDayKey()
    val storedDate = prefs[Keys.TODAY_DATE] ?: today
    val todaySeconds = if (storedDate == today) prefs[Keys.TODAY_READING_SECONDS] ?: 0L else 0L

    val storedAdDate = prefs[Keys.AD_COUNTER_DATE] ?: today
    val importAdsToday = if (storedAdDate == today) prefs[Keys.DAILY_IMPORT_INTERSTITIAL_COUNT] ?: 0 else 0
    val unlockedDate = prefs[Keys.EXTRA_TOOLS_UNLOCKED_DATE] ?: ""

    ReaderPreferences(
      appThemeMode = prefs[Keys.APP_THEME_MODE]?.let { runCatching { AppThemeMode.valueOf(it) }.getOrNull() }
        ?: AppThemeMode.SYSTEM,
      readerTheme = prefs[Keys.READER_THEME]?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() }
        ?: ReaderTheme.PAPER,
      readerFont = prefs[Keys.READER_FONT]?.let { runCatching { ReaderFont.valueOf(it) }.getOrNull() }
        ?: ReaderFont.LITERATA,
      fontSizeSp = (prefs[Keys.FONT_SIZE_SP] ?: 18f).coerceIn(MIN_READER_FONT_SIZE_SP, MAX_READER_FONT_SIZE_SP),
      lineHeightMultiplier = (prefs[Keys.LINE_HEIGHT_MULTIPLIER] ?: 1.55f).coerceIn(1.25f, 2.0f),
      horizontalPaddingDp = (prefs[Keys.HORIZONTAL_PADDING_DP] ?: 22).coerceIn(12, 36),
      epubScrollMode = prefs[Keys.EPUB_SCROLL_MODE] ?: false,
      pdfScrollMode = prefs[Keys.PDF_SCROLL_MODE] ?: false,
      pdfNightFilter = prefs[Keys.PDF_NIGHT_FILTER] ?: true,
      keepScreenOn = prefs[Keys.KEEP_SCREEN_ON] ?: true,
      samplesSeeded = prefs[Keys.SAMPLES_SEEDED] ?: false,
      orientationLock = prefs[Keys.ORIENTATION_LOCK]?.let { runCatching { OrientationLockMode.valueOf(it) }.getOrNull() }
        ?: OrientationLockMode.AUTO,
      readerBrightness = prefs[Keys.READER_BRIGHTNESS]?.let(::normalizeReaderBrightness) ?: -1f,
      ttsSpeechRate = (prefs[Keys.TTS_SPEECH_RATE] ?: 1.0f).coerceIn(0.6f, 2.0f),
      autoScrollSpeed = (prefs[Keys.AUTO_SCROLL_SPEED] ?: 1.2f).coerceIn(0.5f, 3.0f),
      dailyGoalMinutes = (prefs[Keys.DAILY_GOAL_MINUTES] ?: 20).coerceIn(5, 180),
      todayDate = today,
      todayReadingSeconds = todaySeconds,
      readingStreakDays = prefs[Keys.READING_STREAK_DAYS] ?: 0,
      estimatedWpm = (prefs[Keys.ESTIMATED_WPM] ?: 210).coerceIn(100, 450),
      reminderEnabled = prefs[Keys.REMINDER_ENABLED] ?: false,
      reminderHour = (prefs[Keys.REMINDER_HOUR] ?: 20).coerceIn(0, 23),
      reminderMinute = (prefs[Keys.REMINDER_MINUTE] ?: 30).coerceIn(0, 59),
      lastActiveReadingBookId = prefs[Keys.LAST_ACTIVE_READING_BOOK_ID] ?: -1L,
      adCounterDate = today,
      dailyImportInterstitialCount = importAdsToday,
      extraToolsUnlockedToday = unlockedDate == today,
      resumeReminderEnabled = prefs[Keys.RESUME_REMINDER_ENABLED] ?: true,
      resumeReminderDelayHours = (prefs[Keys.RESUME_REMINDER_DELAY_HOURS] ?: 24).coerceIn(24, 48),
      hasOpenedAnyBook = prefs[Keys.HAS_OPENED_ANY_BOOK] ?: false,
      notificationRationaleShown = prefs[Keys.NOTIFICATION_RATIONALE_SHOWN] ?: false
    )
  }

  suspend fun setAppThemeMode(mode: AppThemeMode) {
    context.dataStore.edit { it[Keys.APP_THEME_MODE] = mode.name }
  }

  suspend fun setReaderTheme(theme: ReaderTheme) {
    context.dataStore.edit { it[Keys.READER_THEME] = theme.name }
  }

  suspend fun setReaderFont(font: ReaderFont) {
    context.dataStore.edit { it[Keys.READER_FONT] = font.name }
  }

  suspend fun setFontSizeSp(size: Float) {
    context.dataStore.edit {
      it[Keys.FONT_SIZE_SP] = size.coerceIn(MIN_READER_FONT_SIZE_SP, MAX_READER_FONT_SIZE_SP)
    }
  }

  suspend fun setLineHeightMultiplier(multiplier: Float) {
    context.dataStore.edit { it[Keys.LINE_HEIGHT_MULTIPLIER] = multiplier.coerceIn(1.25f, 2.0f) }
  }

  suspend fun setHorizontalPaddingDp(paddingDp: Int) {
    context.dataStore.edit { it[Keys.HORIZONTAL_PADDING_DP] = paddingDp.coerceIn(12, 36) }
  }

  suspend fun setEpubScrollMode(scroll: Boolean) {
    context.dataStore.edit { it[Keys.EPUB_SCROLL_MODE] = scroll }
  }

  suspend fun setPdfScrollMode(scroll: Boolean) {
    context.dataStore.edit { it[Keys.PDF_SCROLL_MODE] = scroll }
  }

  suspend fun setPdfNightFilter(enabled: Boolean) {
    context.dataStore.edit { it[Keys.PDF_NIGHT_FILTER] = enabled }
  }

  suspend fun setKeepScreenOn(keepOn: Boolean) {
    context.dataStore.edit { it[Keys.KEEP_SCREEN_ON] = keepOn }
  }

  suspend fun markSamplesSeeded() {
    context.dataStore.edit { it[Keys.SAMPLES_SEEDED] = true }
  }

  suspend fun setOrientationLock(mode: OrientationLockMode) {
    context.dataStore.edit { it[Keys.ORIENTATION_LOCK] = mode.name }
  }

  suspend fun setReaderBrightness(brightness: Float) {
    context.dataStore.edit { it[Keys.READER_BRIGHTNESS] = normalizeReaderBrightness(brightness) }
  }

  suspend fun setTtsSpeechRate(rate: Float) {
    context.dataStore.edit { it[Keys.TTS_SPEECH_RATE] = rate.coerceIn(0.6f, 2.0f) }
  }

  suspend fun setAutoScrollSpeed(speed: Float) {
    context.dataStore.edit { it[Keys.AUTO_SCROLL_SPEED] = speed.coerceIn(0.5f, 3.0f) }
  }

  suspend fun setDailyGoalMinutes(minutes: Int) {
    context.dataStore.edit { it[Keys.DAILY_GOAL_MINUTES] = minutes.coerceIn(5, 180) }
  }

  suspend fun addTodayReadingSeconds(deltaSeconds: Long) {
    if (deltaSeconds <= 0) return
    val today = currentDayKey()
    context.dataStore.edit { prefs ->
      val storedDate = prefs[Keys.TODAY_DATE] ?: ""
      val current = if (storedDate == today) prefs[Keys.TODAY_READING_SECONDS] ?: 0L else 0L
      val updated = current + deltaSeconds
      prefs[Keys.TODAY_DATE] = today
      prefs[Keys.TODAY_READING_SECONDS] = updated

      val goalSeconds = ((prefs[Keys.DAILY_GOAL_MINUTES] ?: 20) * 60L)
      if (current < goalSeconds && updated >= goalSeconds) {
        val streak = prefs[Keys.READING_STREAK_DAYS] ?: 0
        prefs[Keys.READING_STREAK_DAYS] = streak + 1
      }
    }
  }

  suspend fun updateEstimatedWpm(wpm: Int) {
    context.dataStore.edit { it[Keys.ESTIMATED_WPM] = wpm.coerceIn(100, 450) }
  }

  suspend fun setReminderSettings(enabled: Boolean, hour: Int, minute: Int) {
    context.dataStore.edit {
      it[Keys.REMINDER_ENABLED] = enabled
      it[Keys.REMINDER_HOUR] = hour.coerceIn(0, 23)
      it[Keys.REMINDER_MINUTE] = minute.coerceIn(0, 59)
    }
  }

  suspend fun setActiveReadingBookId(bookId: Long) {
    context.dataStore.edit { it[Keys.LAST_ACTIVE_READING_BOOK_ID] = bookId }
  }

  /**
   * Returns true if an import interstitial should be shown (max 3/day, resets daily).
   * Increments the daily counter atomically when allowed.
   */
  suspend fun consumeDailyImportAdSlotIfAllowed(): Boolean {
    val today = currentDayKey()
    var allowed = false
    context.dataStore.edit { prefs ->
      val storedDate = prefs[Keys.AD_COUNTER_DATE] ?: ""
      val count = if (storedDate == today) prefs[Keys.DAILY_IMPORT_INTERSTITIAL_COUNT] ?: 0 else 0
      if (count < 3) {
        prefs[Keys.AD_COUNTER_DATE] = today
        prefs[Keys.DAILY_IMPORT_INTERSTITIAL_COUNT] = count + 1
        allowed = true
      } else {
        prefs[Keys.AD_COUNTER_DATE] = today
        prefs[Keys.DAILY_IMPORT_INTERSTITIAL_COUNT] = count
        allowed = false
      }
    }
    return allowed
  }

  suspend fun markRewardedToolsUnlockedToday() {
    context.dataStore.edit { it[Keys.EXTRA_TOOLS_UNLOCKED_DATE] = currentDayKey() }
  }

  suspend fun setResumeReminderEnabled(enabled: Boolean) {
    context.dataStore.edit { it[Keys.RESUME_REMINDER_ENABLED] = enabled }
  }

  suspend fun setResumeReminderDelayHours(hours: Int) {
    context.dataStore.edit { it[Keys.RESUME_REMINDER_DELAY_HOURS] = hours.coerceIn(24, 48) }
  }

  suspend fun markHasOpenedAnyBook() {
    context.dataStore.edit { it[Keys.HAS_OPENED_ANY_BOOK] = true }
  }

  suspend fun markNotificationRationaleShown() {
    context.dataStore.edit { it[Keys.NOTIFICATION_RATIONALE_SHOWN] = true }
  }

  suspend fun getLastResumeNotifiedAt(bookId: Long): Long {
    val raw = context.dataStore.data.first()[Keys.RESUME_NOTIF_LAST_SENT_MAP].orEmpty()
    return parseSentMap(raw)[bookId] ?: 0L
  }

  suspend fun markResumeNotified(bookId: Long, timestamp: Long = System.currentTimeMillis()) {
    context.dataStore.edit { prefs ->
      val current = parseSentMap(prefs[Keys.RESUME_NOTIF_LAST_SENT_MAP].orEmpty()).toMutableMap()
      current[bookId] = timestamp
      // Retain at most 50 most recent book timestamps
      val trimmed = current.entries
        .sortedByDescending { it.value }
        .take(50)
        .joinToString(",") { "${it.key}:${it.value}" }
      prefs[Keys.RESUME_NOTIF_LAST_SENT_MAP] = trimmed
    }
  }

  private fun parseSentMap(raw: String): Map<Long, Long> {
    if (raw.isBlank()) return emptyMap()
    return raw.split(",").mapNotNull { entry ->
      val parts = entry.split(":")
      if (parts.size == 2) {
        val id = parts[0].toLongOrNull()
        val ts = parts[1].toLongOrNull()
        if (id != null && ts != null) id to ts else null
      } else null
    }.toMap()
  }
}
