package com.example.data

import kotlinx.coroutines.flow.Flow

class JarvisRepository(private val database: AppDatabase) {
    val allNotes: Flow<List<Note>> = database.noteDao().getAllNotes()
    val recentLogs: Flow<List<CommandLog>> = database.commandLogDao().getRecentLogs()

    suspend fun insertNote(note: Note): Long {
        return database.noteDao().insertNote(note)
    }

    suspend fun deleteNote(note: Note) {
        database.noteDao().deleteNote(note)
    }

    suspend fun deleteNoteById(id: Int) {
        database.noteDao().deleteNoteById(id)
    }

    suspend fun insertLog(log: CommandLog): Long {
        return database.commandLogDao().insertLog(log)
    }

    suspend fun clearLogs() {
        database.commandLogDao().clearLogs()
    }
}
