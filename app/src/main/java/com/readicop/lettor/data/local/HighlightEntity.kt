package com.readicop.lettor.data.local

import androidx.compose.ui.graphics.Color
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class HighlightColor(val label: String, val washLight: Color, val washDark: Color, val dotColor: Color) {
  AMBER("Ámbar", Color(0x55F4C542), Color(0x44F4C542), Color(0xFFD99B16)),
  SAGE("Salvia", Color(0x5575A478), Color(0x4475A478), Color(0xFF4F8254)),
  TERRACOTTA("Terracota", Color(0x55D96B43), Color(0x44E57F64), Color(0xFFB5482D)),
  SKY("Cielo", Color(0x555E9ED6), Color(0x445E9ED6), Color(0xFF3474AD)),
  PLUM("Ciruela", Color(0x55A774B5), Color(0x44B584C4), Color(0xFF814C91))
}

@Entity(
  tableName = "highlights",
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
data class HighlightEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val bookId: Long,
  val unitIndex: Int,
  val blockIndex: Int = -1,
  val excerpt: String,
  val stickyNote: String = "",
  val color: HighlightColor = HighlightColor.AMBER,
  val isStickyNoteOnly: Boolean = false,
  val createdAt: Long = System.currentTimeMillis()
)

data class HighlightWithBook(
  val highlight: HighlightEntity,
  val bookTitle: String,
  val bookAuthor: String,
  val bookFormat: BookFormat
)
