package com.readicop.lettor.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class BookFormat {
  EPUB,
  PDF
}

@Entity(tableName = "books")
data class BookEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val title: String,
  val author: String,
  val genre: String = "General",
  val description: String = "",
  val format: BookFormat,
  val filePath: String,
  val coverPath: String? = null,
  val contentHash: String = "",
  val totalUnits: Int = 1,
  val totalWordCount: Int = 0,
  val currentUnit: Int = 0,
  val currentSubOffset: Int = 0,
  val progressFraction: Float = 0f,
  val isFavorite: Boolean = false,
  val isFinished: Boolean = false,
  val fileSizeBytes: Long = 0L,
  val addedAt: Long = System.currentTimeMillis(),
  val lastOpenedAt: Long = 0L,
  val readingTimeSeconds: Long = 0L
)
