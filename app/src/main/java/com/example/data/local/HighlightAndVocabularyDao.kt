package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HighlightDao {
  @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY unitIndex ASC, blockIndex ASC, createdAt DESC")
  fun observeHighlightsForBook(bookId: Long): Flow<List<HighlightEntity>>

  @Query("SELECT * FROM highlights ORDER BY createdAt DESC")
  fun observeAllHighlights(): Flow<List<HighlightEntity>>

  @Query("SELECT * FROM highlights")
  suspend fun getAllHighlightsOnce(): List<HighlightEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertHighlight(highlight: HighlightEntity): Long

  @Query("UPDATE highlights SET stickyNote = :note, color = :color WHERE id = :id")
  suspend fun updateHighlight(id: Long, note: String, color: HighlightColor)

  @Query("DELETE FROM highlights WHERE id = :id")
  suspend fun deleteHighlightById(id: Long)
}

@Dao
interface VocabularyDao {
  @Query("SELECT * FROM vocabulary ORDER BY isMastered ASC, createdAt DESC")
  fun observeAllVocabulary(): Flow<List<VocabularyEntity>>

  @Query("SELECT * FROM vocabulary")
  suspend fun getAllVocabularyOnce(): List<VocabularyEntity>

  @Query("SELECT * FROM vocabulary WHERE normalizedWord = :normalized LIMIT 1")
  suspend fun findByNormalizedWord(normalized: String): VocabularyEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertWord(word: VocabularyEntity): Long

  @Query("UPDATE vocabulary SET isMastered = :mastered WHERE id = :id")
  suspend fun setMastered(id: Long, mastered: Boolean)

  @Query("DELETE FROM vocabulary WHERE id = :id")
  suspend fun deleteWordById(id: Long)
}
