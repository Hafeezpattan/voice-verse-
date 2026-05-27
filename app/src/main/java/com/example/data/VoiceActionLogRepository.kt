package com.example.data

import kotlinx.coroutines.flow.Flow

class VoiceActionLogRepository(private val dao: VoiceActionLogDao) {
    val allLogs: Flow<List<VoiceActionLog>> = dao.getAllLogs()

    suspend fun insertLog(log: VoiceActionLog) {
        dao.insertLog(log)
    }

    suspend fun clearLogs() {
        dao.clearLogs()
    }
}
