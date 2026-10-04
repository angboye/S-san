package com.example.sscan

import com.example.sscan.data.local.entity.ScanHistoryEntity
import com.example.sscan.data.repository.ScanRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 首页统计卡「较昨日 +x%」的计算。纯函数，放 JVM 侧。
 *
 * 关键约定：昨日基数为 0 时百分比没有数学意义，必须返回 null 让 UI 显示
 * 占位破折号，而不是编造一个 100% 或 0%。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainStatsTest {

    private lateinit var viewModel: MainViewModel

    // MainViewModel 的 init 里有 viewModelScope.launch，JVM 环境需要先装 Main dispatcher
    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        viewModel = MainViewModel(NoopScanRepository())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun previousZero_returnsNull_insteadOfFabricatingPercent() {
        assertNull("昨日为 0 时不能编造百分比", viewModel.percentChange(current = 10, previous = 0))
        assertNull(viewModel.percentChange(current = 0, previous = 0))
    }

    @Test
    fun negativePrevious_returnsNull() {
        assertNull(viewModel.percentChange(current = 5, previous = -1))
    }

    @Test
    fun growth_isPositive() {
        assertEquals(100.0, viewModel.percentChange(current = 20, previous = 10)!!, 0.001)
        assertEquals(12.5, viewModel.percentChange(current = 9, previous = 8)!!, 0.001)
    }

    @Test
    fun decline_isNegative() {
        assertEquals(-50.0, viewModel.percentChange(current = 5, previous = 10)!!, 0.001)
    }

    @Test
    fun unchanged_isZero() {
        assertEquals(0.0, viewModel.percentChange(current = 7, previous = 7)!!, 0.001)
    }

    @Test
    fun droppedToZero_isMinus100() {
        assertEquals(-100.0, viewModel.percentChange(current = 0, previous = 4)!!, 0.001)
    }

    /** 构造 MainViewModel 只需要一个仓库；本测试只验证纯函数，仓库不被使用。 */
    private class NoopScanRepository : ScanRepository {
        override fun observeHistory(): Flow<List<ScanHistoryEntity>> =
            MutableStateFlow(emptyList())

        override fun observeHistoryFiltered(query: String, startTime: Long?) = notUsed()
        override fun observeHistoryById(id: Long) = notUsed()
        override fun observeTrash() = notUsed()
        override suspend fun isDuplicate(result: String) = notUsed()
        override suspend fun save(result: String, imageUri: String, groupName: String) = notUsed()
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
            throw UnsupportedOperationException("本测试不应调用仓库")
    }
}
