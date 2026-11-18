package com.readicop.lettor.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  tableName = "bookmarks",
  foreignKeys = [
    ForeignKey(
      entity = BookEntity::class,
      parentColumns = ["id"],
      childColumns = ["bookId"],
      onDelete = ForeignKey.CASCADE
    )
  ],
  indices = [Index(value = ["bookId"])]
)
data class BookmarkEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val bookId: Long,
  val unitIndex: Int,
  val subOffset: Int = 0,
  val label: String,
  val excerpt: String = "",
  val note: String = "",
  val createdAt: Long = System.currentTimeMillis()
)

data class BookmarkWithBook(
  val bookmark: BookmarkEntity,
  val bookTitle: String,
  val bookAuthor: String,
  val bookFormat: BookFormat,
  val bookCoverPath: String?
)
