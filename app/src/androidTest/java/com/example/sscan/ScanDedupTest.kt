package com.example.sscan

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.sscan.core.settings.AppSettings
import com.example.sscan.core.storage.MediaStoreImageSaver
import com.example.sscan.core.utils.ImageSharpnessDetector
import com.example.sscan.domain.usecase.SaveScanWithImageUseCase
import com.example.sscan.feature.scan.presentation.ScanDispatchResult
import com.example.sscan.feature.scan.presentation.ScanViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 客户需求①「扫码数据重复不保存」的两级判定测试。
 *
 * 第一级：内存 5 秒窗口，挡住连击，不查库
 * 第二级：Room 历史永久查重，挡住跨会话重复
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ScanDedupTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var context: Context
    private lateinit var repo: FakeScanRepository
    private lateinit var viewModel: ScanViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        clearPrefs()
        repo = FakeScanRepository()
        viewModel = ScanViewModel(
            context = context,
            scanRepository = repo,
            saveScanWithImageUseCase = SaveScanWithImageUseCase(
                context = context,
                scanRepository = repo,
                mediaStoreImageSaver = MediaStoreImageSaver(context),
                imageSharpnessDetector = ImageSharpnessDetector()
            )
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearPrefs()
    }

    private fun clearPrefs() {
        context.getSharedPreferences("s_scan_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    /**
     * SaveScanWithImageUseCase 整体包在 withContext(Dispatchers.Default) 里，
     * 跑在真实后台线程上，协程测试的虚拟时间推不动它，只能有界轮询等待。
     */
    private fun awaitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
    }

    // ── 第一级：内存窗口 ────────────────────────────────

    @Test
    fun firstScan_isAccepted() {
        val result = viewModel.onScanRawResults(listOf("CODE-1"), null)
        awaitUntil { repo.savedResults.isNotEmpty() }

        assertEquals(ScanDispatchResult.ACCEPTED, result)
        assertEquals(listOf("CODE-1"), repo.savedResults)
    }

    /** 450ms 节流窗口内的第二次扫码应被丢弃。 */
    @Test
    fun rapidSecondScan_isThrottled() {
        viewModel.onScanRawResults(listOf("CODE-1"), null)
        val second = viewModel.onScanRawResults(listOf("CODE-2"), null)
        awaitUntil { repo.savedResults.isNotEmpty() }

        assertEquals(ScanDispatchResult.THROTTLED, second)
        assertFalse("被节流的内容不应入库", repo.savedResults.contains("CODE-2"))
    }

    @Test
    fun emptyInput_returnsEmpty() {
        assertEquals(ScanDispatchResult.EMPTY, viewModel.onScanRawResults(emptyList(), null))
        assertTrue(repo.savedResults.isEmpty())
    }

    // ── 第二级：历史永久去重 ────────────────────────────

    @Test
    fun historicalDuplicate_isNotSaved() {
        repo.duplicates += "OLD-CODE"

        val result = viewModel.onScanRawResults(listOf("OLD-CODE"), null)
        awaitUntil { repo.isDuplicateCallCount > 0 }

        // 内存窗口没见过它，所以先被接受，随后在保存阶段被历史查重拦下
        assertEquals(ScanDispatchResult.ACCEPTED, result)
        assertTrue("历史里已存在的内容绝不能再次入库", repo.savedResults.isEmpty())
    }

    @Test
    fun mixedBatch_savesOnlyFreshOnes() {
        repo.duplicates += "OLD-CODE"

        viewModel.onScanRawResults(listOf("OLD-CODE", "NEW-CODE"), null)
        awaitUntil { repo.savedResults.isNotEmpty() }

        assertEquals("只有新内容应入库", listOf("NEW-CODE"), repo.savedResults)
    }

    /** 全是历史重复时，抓拍的临时文件必须被删除，否则缓存目录会持续膨胀。 */
    @Test
    fun historicalDuplicate_deletesTempImageFile() {
        repo.duplicates += "OLD-CODE"
        val temp = File.createTempFile("scan_dedup_", ".jpg", context.cacheDir)
        temp.writeBytes(ByteArray(16))
        assertTrue(temp.exists())

        viewModel.onScanRawResults(listOf("OLD-CODE"), temp)
        awaitUntil { !temp.exists() }

        assertFalse("全是重复时临时抓拍文件应被清理，否则泄漏", temp.exists())
    }

    // ── 开关短路 ────────────────────────────────────────

    @Test
    fun allowDuplicateScan_bypassesHistoryCheck() {
        AppSettings.setAllowDuplicateScan(context, true)
        repo.duplicates += "OLD-CODE"

        viewModel.onScanRawResults(listOf("OLD-CODE"), null)
        awaitUntil { repo.savedResults.isNotEmpty() }

        assertEquals("开关打开时应照常保存", listOf("OLD-CODE"), repo.savedResults)
        assertEquals("开关打开时不该再查库", 0, repo.isDuplicateCallCount)
    }

    @Test
    fun allowDuplicateScanOff_queriesRepository() {
        AppSettings.setAllowDuplicateScan(context, false)

        viewModel.onScanRawResults(listOf("CODE-X"), null)
        awaitUntil { repo.savedResults.isNotEmpty() }

        assertTrue("关闭时必须查库", repo.isDuplicateCallCount > 0)
        assertEquals(listOf("CODE-X"), repo.savedResults)
    }
}
