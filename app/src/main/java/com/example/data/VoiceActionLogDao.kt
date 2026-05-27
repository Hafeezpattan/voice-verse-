package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VoiceActionLogDao {
    @Query("SELECT * FROM voice_action_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<VoiceActionLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: VoiceActionLog)

    @Query("DELETE FROM voice_action_logs")
    suspend fun clearLogs()
}
