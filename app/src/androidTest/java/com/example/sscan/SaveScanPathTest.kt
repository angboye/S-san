package com.example.sscan

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.sscan.core.settings.AppSettings
import com.example.sscan.core.storage.MediaStoreImageSaver
import com.example.sscan.core.utils.ImageSharpnessDetector
import com.example.sscan.domain.usecase.SaveScanWithImageUseCase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 客户需求③「数据保存路径」的测试。
 *
 * 「独立文件夹」开关此前是死的——设置页能拨，但从未被存储逻辑读取。
 * 分组名会直接拼进 MediaStore 的 relativePath，因此必须做文件名安全化。
 */
@RunWith(AndroidJUnit4::class)
class SaveScanPathTest {

    private lateinit var context: Context
    private lateinit var useCase: SaveScanWithImageUseCase
    private val defaultPath get() = MediaStoreImageSaver.DEFAULT_RELATIVE_PATH

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearPrefs()
        useCase = SaveScanWithImageUseCase(
            context = context,
            scanRepository = FakeScanRepository(),
            mediaStoreImageSaver = MediaStoreImageSaver(context),
            imageSharpnessDetector = ImageSharpnessDetector()
        )
    }

    @After
    fun tearDown() = clearPrefs()

    private fun clearPrefs() {
        context.getSharedPreferences("s_scan_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    // ── 路径拼装 ────────────────────────────────────────

    @Test
    fun separateFolderOff_usesDefaultPath() {
        AppSettings.setSaveSeparateFolder(context, false)
        assertEquals(defaultPath, useCase.buildRelativePath("仓库A"))
    }

    @Test
    fun separateFolderOn_appendsGroupName() {
        AppSettings.setSaveSeparateFolder(context, true)
        assertEquals("$defaultPath/仓库A", useCase.buildRelativePath("仓库A"))
    }

    @Test
    fun separateFolderOn_blankGroup_fallsBackToDefault() {
        AppSettings.setSaveSeparateFolder(context, true)
        assertEquals(defaultPath, useCase.buildRelativePath(""))
        assertEquals(defaultPath, useCase.buildRelativePath("   "))
    }

    /** 分组名被清理后为空时必须退回默认路径，不能产生尾随斜杠。 */
    @Test
    fun separateFolderOn_groupOfOnlyIllegalChars_fallsBackToDefault() {
        AppSettings.setSaveSeparateFolder(context, true)
        val path = useCase.buildRelativePath("///")
        assertEquals(defaultPath, path)
        assertFalse("绝不能产生尾随斜杠", path.endsWith("/"))
    }

    @Test
    fun separateFolderOn_illegalCharsAreStripped() {
        AppSettings.setSaveSeparateFolder(context, true)
        assertEquals("$defaultPath/仓库A", useCase.buildRelativePath("仓/库\\A"))
    }

    // ── 分组名安全化 ────────────────────────────────────

    @Test
    fun sanitize_removesAllIllegalFilenameChars() {
        assertEquals("abc", useCase.sanitizeGroupFolderName("""a/b\c"""))
        assertEquals("abc", useCase.sanitizeGroupFolderName("""a:b*c"""))
        assertEquals("abc", useCase.sanitizeGroupFolderName("""a?b"c"""))
        assertEquals("abc", useCase.sanitizeGroupFolderName("""a<b>c"""))
        assertEquals("abc", useCase.sanitizeGroupFolderName("""a|bc"""))
    }

    @Test
    fun sanitize_trimsSurroundingWhitespace() {
        assertEquals("仓库A", useCase.sanitizeGroupFolderName("  仓库A  "))
    }

    /** 路径穿越形态的输入不能残留分隔符。 */
    @Test
    fun sanitize_defeatsPathTraversalShape() {
        val cleaned = useCase.sanitizeGroupFolderName("../../etc")
        assertFalse("清理后不应残留路径分隔符", cleaned.contains("/"))
        assertFalse(cleaned.contains("\\"))
    }

    @Test
    fun sanitize_keepsNormalNames() {
        assertEquals("仓库A-2026", useCase.sanitizeGroupFolderName("仓库A-2026"))
        assertEquals("Warehouse 1", useCase.sanitizeGroupFolderName("Warehouse 1"))
    }
}
