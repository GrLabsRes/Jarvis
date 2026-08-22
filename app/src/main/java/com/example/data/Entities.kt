package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "command_logs")
data class CommandLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val command: String,
    val actionType: String,
    val timestamp: Long = System.currentTimeMillis(),
    val success: Boolean,
    val feedback: String
)
