package com.example.sscan.data.repository

import android.content.Context
import com.example.sscan.core.settings.AppSettings
import com.example.sscan.data.local.dao.ScanHistoryDao
import com.example.sscan.data.local.entity.ScanHistoryEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScanRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanHistoryDao: ScanHistoryDao
) : ScanRepository {

    override fun observeHistory(): Flow<List<ScanHistoryEntity>> = scanHistoryDao.observeHistory()

    override fun observeHistoryFiltered(
        query: String,
        startTime: Long?
    ): Flow<List<ScanHistoryEntity>> = scanHistoryDao.observeHistoryFiltered(query, startTime)

    override fun observeHistoryById(id: Long): Flow<ScanHistoryEntity?> =
        scanHistoryDao.observeById(id)

    override fun observeTrash(): Flow<List<ScanHistoryEntity>> = scanHistoryDao.observeTrash()

    override suspend fun isDuplicate(result: String): Boolean {
        return scanHistoryDao.existsActiveByResult(result)
    }

    override suspend fun save(result: String, imageUri: String, groupName: String): Long {
        val trimmedGroupName = groupName.trim()
        if (trimmedGroupName.isNotBlank()) {
            AppSettings.addHistoryFolder(context, trimmedGroupName)
        }
        return scanHistoryDao.insert(
            ScanHistoryEntity(
                result = result,
                imageUri = imageUri,
                groupName = trimmedGroupName
            )
        )
    }

    override suspend fun updateHistoryTitle(id: Long, title: String) {
        scanHistoryDao.updateCustomTitle(id, title.trim())
    }

    override suspend fun updateHistoryResult(id: Long, result: String) {
        scanHistoryDao.updateResult(id, result.trim())
    }

    override suspend fun delete(id: Long) {
        scanHistoryDao.moveToTrash(id)
    }

    override suspend fun restore(id: Long) {
        scanHistoryDao.restore(id)
    }

    override suspend fun deletePermanently(id: Long) {
        scanHistoryDao.deletePermanently(id)
    }

    override suspend fun clearAll() {
        scanHistoryDao.clearAll()
    }

    override suspend fun createGroup(groupName: String) {
        AppSettings.addHistoryFolder(context, groupName)
    }

    override suspend fun deleteGroup(groupName: String, ids: List<Long>) {
        AppSettings.removeHistoryFolder(context, groupName)
        if (ids.isNotEmpty()) {
            scanHistoryDao.moveToTrashByIds(ids)
        }
    }

    override suspend fun moveToGroup(groupName: String, ids: List<Long>) {
        val trimmedGroupName = groupName.trim()
        AppSettings.addHistoryFolder(context, trimmedGroupName)
        scanHistoryDao.moveToGroup(trimmedGroupName, ids)
    }

    override suspend fun renameGroup(oldName: String, newName: String) {
        val trimmedNewName = newName.trim()
        AppSettings.renameHistoryFolder(context, oldName, trimmedNewName)
        scanHistoryDao.renameGroup(oldName, trimmedNewName)
    }

    override suspend fun deletePermanentlyByIds(ids: List<Long>) {
        scanHistoryDao.deletePermanentlyByIds(ids)
    }

    override suspend fun moveToTrashByIds(ids: List<Long>) {
        scanHistoryDao.moveToTrashByIds(ids)
    }

    override suspend fun getAllGroupNames(): List<String> {
        return (AppSettings.getHistoryFolders(context) + scanHistoryDao.getAllGroupNames())
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }
}
