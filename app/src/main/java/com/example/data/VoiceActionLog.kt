package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "voice_action_logs")
data class VoiceActionLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val spokenText: String,
    val confidence: Float,
    val detectedAction: String,
    val status: String // "SUCCESS", "BLOCKED_PASS", "BLOCKED_AUTH", "IGNORED"
)
