package com.example

import android.app.Application
import com.example.data.local.LettorDatabase
import com.example.data.preferences.ReaderPreferencesRepository
import com.example.data.repository.LibraryRepository
import com.example.domain.engine.BackupManager
import com.example.domain.engine.BookImporter
import com.example.monetization.AdMobManager
import com.example.notifications.ReadingReminderWorker
import com.example.notifications.ResumeReadingWorker

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
