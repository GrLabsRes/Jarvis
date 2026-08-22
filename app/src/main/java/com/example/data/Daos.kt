package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY timestamp DESC")
    fun getAllNotes(): Flow<List<Note>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: Note): Long

    @Delete
    suspend fun deleteNote(note: Note)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNoteById(id: Int)
}

@Dao
interface CommandLogDao {
    @Query("SELECT * FROM command_logs ORDER BY timestamp DESC LIMIT 50")
    fun getRecentLogs(): Flow<List<CommandLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: CommandLog): Long

    @Query("DELETE FROM command_logs")
    suspend fun clearLogs()
}
