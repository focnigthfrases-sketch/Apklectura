package com.readicop.lettor.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class LettorTypeConverters {
  @TypeConverter
  fun fromBookFormat(format: BookFormat): String = format.name

  @TypeConverter
  fun toBookFormat(value: String): BookFormat =
    runCatching { BookFormat.valueOf(value) }.getOrDefault(BookFormat.EPUB)

  @TypeConverter
  fun fromHighlightColor(color: HighlightColor): String = color.name

  @TypeConverter
  fun toHighlightColor(value: String): HighlightColor =
    runCatching { HighlightColor.valueOf(value) }.getOrDefault(HighlightColor.AMBER)
}

@Database(
  entities = [
    BookEntity::class,
    BookmarkEntity::class,
    HighlightEntity::class,
    VocabularyEntity::class
  ],
  version = 2,
  exportSchema = false
)
@TypeConverters(LettorTypeConverters::class)
abstract class LettorDatabase : RoomDatabase() {
  abstract fun bookDao(): BookDao
  abstract fun bookmarkDao(): BookmarkDao
  abstract fun highlightDao(): HighlightDao
  abstract fun vocabularyDao(): VocabularyDao

  companion object {
    @Volatile
    private var INSTANCE: LettorDatabase? = null

    private val MIGRATION_1_2 = object : Migration(1, 2) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN genre TEXT NOT NULL DEFAULT 'General'")
        db.execSQL("ALTER TABLE books ADD COLUMN contentHash TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE books ADD COLUMN totalWordCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS highlights (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            bookId INTEGER NOT NULL,
            unitIndex INTEGER NOT NULL,
            blockIndex INTEGER NOT NULL DEFAULT -1,
            excerpt TEXT NOT NULL,
            stickyNote TEXT NOT NULL DEFAULT '',
            color TEXT NOT NULL DEFAULT 'AMBER',
            isStickyNoteOnly INTEGER NOT NULL DEFAULT 0,
            createdAt INTEGER NOT NULL,
            FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE
          )
          """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_highlights_bookId ON highlights(bookId)")
        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS vocabulary (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            word TEXT NOT NULL,
            normalizedWord TEXT NOT NULL,
            partOfSpeech TEXT NOT NULL DEFAULT '',
            definition TEXT NOT NULL,
            translation TEXT NOT NULL DEFAULT '',
            bookId INTEGER,
            bookTitle TEXT NOT NULL DEFAULT '',
            contextSnippet TEXT NOT NULL DEFAULT '',
            isMastered INTEGER NOT NULL DEFAULT 0,
            createdAt INTEGER NOT NULL
          )
          """.trimIndent()
        )
      }
    }

    fun getInstance(context: Context): LettorDatabase {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: Room.databaseBuilder(
          context.applicationContext,
          LettorDatabase::class.java,
          "lettor_library.db"
        )
          .addMigrations(MIGRATION_1_2)
          .build()
          .also { INSTANCE = it }
      }
    }
  }
}
