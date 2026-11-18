package com.readicop.lettor.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
  @Query("SELECT * FROM books ORDER BY CASE WHEN lastOpenedAt > 0 THEN lastOpenedAt ELSE addedAt END DESC")
  fun observeAllBooks(): Flow<List<BookEntity>>

  @Query("SELECT * FROM books")
  suspend fun getAllBooksOnce(): List<BookEntity>

  @Query("SELECT * FROM books WHERE id = :bookId")
  fun observeBookById(bookId: Long): Flow<BookEntity?>

  @Query("SELECT * FROM books WHERE id = :bookId")
  suspend fun getBookById(bookId: Long): BookEntity?

  @Query("SELECT * FROM books WHERE contentHash = :hash AND contentHash != '' LIMIT 1")
  suspend fun findByContentHash(hash: String): BookEntity?

  @Query("SELECT COUNT(*) FROM books")
  suspend fun getBookCount(): Int

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertBook(book: BookEntity): Long

  @Update
  suspend fun updateBook(book: BookEntity)

  @Query(
    """
    UPDATE books
    SET title = :title,
        author = :author,
        genre = :genre,
        description = :description,
        coverPath = :coverPath
    WHERE id = :bookId
    """
  )
  suspend fun updateMetadata(
    bookId: Long,
    title: String,
    author: String,
    genre: String,
    description: String,
    coverPath: String?
  )

  @Query(
    """
    UPDATE books
    SET currentUnit = :unit,
        currentSubOffset = :subOffset,
        progressFraction = :progress,
        isFinished = :isFinished,
        lastOpenedAt = :timestamp
    WHERE id = :bookId
    """
  )
  suspend fun updateReadingProgress(
    bookId: Long,
    unit: Int,
    subOffset: Int,
    progress: Float,
    isFinished: Boolean,
    timestamp: Long
  )

  @Query("UPDATE books SET isFavorite = :isFavorite WHERE id = :bookId")
  suspend fun updateFavorite(bookId: Long, isFavorite: Boolean)

  @Query("UPDATE books SET isFinished = :isFinished, progressFraction = CASE WHEN :isFinished THEN 1.0 ELSE progressFraction END WHERE id = :bookId")
  suspend fun updateFinished(bookId: Long, isFinished: Boolean)

  @Query("UPDATE books SET readingTimeSeconds = readingTimeSeconds + :deltaSeconds WHERE id = :bookId")
  suspend fun addReadingTime(bookId: Long, deltaSeconds: Long)

  @Query("DELETE FROM books WHERE id = :bookId")
  suspend fun deleteBookById(bookId: Long)
}
