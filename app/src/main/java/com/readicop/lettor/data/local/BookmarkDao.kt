package com.readicop.lettor.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
  @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY unitIndex ASC, subOffset ASC, createdAt DESC")
  fun observeBookmarksForBook(bookId: Long): Flow<List<BookmarkEntity>>

  @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
  fun observeAllBookmarks(): Flow<List<BookmarkEntity>>

  @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
  suspend fun getAllBookmarksOnce(): List<BookmarkEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertBookmark(bookmark: BookmarkEntity): Long

  @Query("DELETE FROM bookmarks WHERE id = :id")
  suspend fun deleteBookmarkById(id: Long)

  @Query("DELETE FROM bookmarks WHERE bookId = :bookId AND unitIndex = :unitIndex AND subOffset = :subOffset")
  suspend fun deleteBookmarkAt(bookId: Long, unitIndex: Int, subOffset: Int)
}
