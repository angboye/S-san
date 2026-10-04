package com.example.sscan.data.repository

import com.example.sscan.data.local.entity.ScanHistoryEntity
import kotlinx.coroutines.flow.Flow

interface ScanRepository {
    fun observeHistory(): Flow<List<ScanHistoryEntity>>
    fun observeHistoryFiltered(query: String, startTime: Long?): Flow<List<ScanHistoryEntity>>
    fun observeHistoryById(id: Long): Flow<ScanHistoryEntity?>
    fun observeTrash(): Flow<List<ScanHistoryEntity>>
    suspend fun isDuplicate(result: String): Boolean

    suspend fun save(result: String, imageUri: String = "", groupName: String = ""): Long
    suspend fun updateHistoryTitle(id: Long, title: String)
    suspend fun updateHistoryResult(id: Long, result: String)
    suspend fun delete(id: Long)
    suspend fun restore(id: Long)
    suspend fun deletePermanently(id: Long)
    suspend fun clearAll()
    suspend fun createGroup(groupName: String)
    suspend fun deleteGroup(groupName: String, ids: List<Long>)
    suspend fun moveToGroup(groupName: String, ids: List<Long>)
    suspend fun renameGroup(oldName: String, newName: String)
    suspend fun deletePermanentlyByIds(ids: List<Long>)
    suspend fun moveToTrashByIds(ids: List<Long>)
    suspend fun getAllGroupNames(): List<String>
}
