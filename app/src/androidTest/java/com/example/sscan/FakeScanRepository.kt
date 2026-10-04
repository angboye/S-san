package com.example.sscan

import com.example.sscan.data.local.entity.ScanHistoryEntity
import com.example.sscan.data.local.model.DailyScanStat
import com.example.sscan.data.repository.ScanRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 手写的仓库测试替身。ScanRepository 是 interface，用 Fake 即可，
 * 不必为此引入 Mockito / MockK。
 *
 * 只实现测试真正用到的方法，其余抛异常——一旦被意外调用会立刻暴露，
 * 比返回默认值更容易发现问题。
 */
class FakeScanRepository : ScanRepository {

    /** 命中这些内容时 isDuplicate 返回 true。 */
    val duplicates = mutableSetOf<String>()

    /** 记录所有 save 调用，用于断言「重复的没有被保存」。 */
    val savedResults = mutableListOf<String>()

    val history = MutableStateFlow<List<ScanHistoryEntity>>(emptyList())

    var isDuplicateCallCount = 0
        private set

    override suspend fun isDuplicate(result: String): Boolean {
        isDuplicateCallCount++
        return duplicates.contains(result)
    }

    override suspend fun save(result: String, imageUri: String, groupName: String): Long {
        savedResults += result
        return savedResults.size.toLong()
    }

    override fun observeHistory(): Flow<List<ScanHistoryEntity>> = history

    // ── 以下方法本轮测试未使用 ──────────────────────────────
    override fun observeHistoryFiltered(query: String, startTime: Long?) = notUsed()
    override fun observeHistoryById(id: Long) = notUsed()
    override fun observeTrash() = notUsed()
    override fun observeDailyStats(limit: Int): Flow<List<DailyScanStat>> = notUsed()
    override suspend fun updateHistoryTitle(id: Long, title: String) = notUsed()
    override suspend fun updateHistoryResult(id: Long, result: String) = notUsed()
    override suspend fun delete(id: Long) = notUsed()
    override suspend fun restore(id: Long) = notUsed()
    override suspend fun deletePermanently(id: Long) = notUsed()
    override suspend fun clearAll() = notUsed()
    override suspend fun createGroup(groupName: String) = notUsed()
    override suspend fun deleteGroup(groupName: String, ids: List<Long>) = notUsed()
    override suspend fun moveToGroup(groupName: String, ids: List<Long>) = notUsed()
    override suspend fun renameGroup(oldName: String, newName: String) = notUsed()
    override suspend fun deletePermanentlyByIds(ids: List<Long>) = notUsed()
    override suspend fun moveToTrashByIds(ids: List<Long>) = notUsed()
    override suspend fun getAllGroupNames(): List<String> = notUsed()

    private fun notUsed(): Nothing =
        throw UnsupportedOperationException("FakeScanRepository: 本测试不应调用该方法")
}
