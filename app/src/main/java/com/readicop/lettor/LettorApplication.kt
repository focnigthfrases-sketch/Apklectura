package com.readicop.lettor

import android.app.Application
import com.readicop.lettor.data.local.LettorDatabase
import com.readicop.lettor.data.preferences.ReaderPreferencesRepository
import com.readicop.lettor.data.repository.LibraryRepository
import com.readicop.lettor.domain.engine.BackupManager
import com.readicop.lettor.domain.engine.BookImporter
import com.readicop.lettor.monetization.AdMobManager
import com.readicop.lettor.notifications.ReadingReminderWorker
import com.readicop.lettor.notifications.ResumeReadingWorker

class LettorAppContainer(application: Application) {
  private val database by lazy { LettorDatabase.getInstance(application) }
  val preferencesRepository by lazy { ReaderPreferencesRepository(application) }
  private val bookImporter by lazy { BookImporter(application) }
  private val backupManager by lazy {
    BackupManager(
      context = application,
      bookDao = database.bookDao(),
      bookmarkDao = database.bookmarkDao(),
      highlightDao = database.highlightDao(),
      vocabularyDao = database.vocabularyDao()
    )
  }
  val adMobManager by lazy {
    AdMobManager(
      context = application,
      preferencesRepository = preferencesRepository
    )
  }
  val libraryRepository by lazy {
    LibraryRepository(
      appContext = application,
      bookDao = database.bookDao(),
      bookmarkDao = database.bookmarkDao(),
      highlightDao = database.highlightDao(),
      vocabularyDao = database.vocabularyDao(),
      importer = bookImporter,
      backupManager = backupManager,
      preferencesRepository = preferencesRepository
    )
  }
}

class LettorApplication : Application() {
  lateinit var container: LettorAppContainer
    private set

  override fun onCreate() {
    super.onCreate()
    container = LettorAppContainer(this)
    container.adMobManager.initialize()
    ReadingReminderWorker.createNotificationChannel(this)
    ResumeReadingWorker.createNotificationChannel(this)
  }
}
