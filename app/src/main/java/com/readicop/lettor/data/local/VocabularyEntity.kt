package com.readicop.lettor.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vocabulary")
data class VocabularyEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val word: String,
  val normalizedWord: String,
  val partOfSpeech: String = "",
  val definition: String,
  val translation: String = "",
  val bookId: Long? = null,
  val bookTitle: String = "",
  val contextSnippet: String = "",
  val isMastered: Boolean = false,
  val createdAt: Long = System.currentTimeMillis()
)
