package com.example.sscan

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.sscan.data.local.AppDatabase
import com.example.sscan.data.local.dao.ScanHistoryDao
import com.example.sscan.data.local.entity.ScanHistoryEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 去重查询 existsActiveByResult 的语义测试。
 *
 * 最关键的一条是 deleted = 0：回收站里的记录不算「已存在」，
 * 用户主动删掉后必须能重新扫入。写错会让用户删了也扫不进来。
 */
@RunWith(AndroidJUnit4::class)
class ScanHistoryDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ScanHistoryDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.scanHistoryDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insert(result: String, deleted: Boolean = false, group: String = "") {
        dao.insert(
            ScanHistoryEntity(
                result = result,
                imageUri = "",
                customTitle = "",
                groupName = group,
                time = System.currentTimeMillis(),
                deleted = deleted
            )
        )
    }

    @Test
    fun existsActiveByResult_returnsTrue_forActiveRecord() = runTest {
        insert("ABC-123")
        assertTrue(dao.existsActiveByResult("ABC-123"))
    }

    @Test
    fun existsActiveByResult_returnsFalse_forUnknownResult() = runTest {
        insert("ABC-123")
        assertFalse(dao.existsActiveByResult("NOT-THERE"))
    }

    /** 核心语义：回收站里的记录不算已存在，允许重新扫入。 */
    @Test
    fun existsActiveByResult_returnsFalse_forTrashedRecord() = runTest {
        insert("TRASHED-1", deleted = true)
        assertFalse(
            "回收站里的记录不应算作重复，否则用户删掉后就再也扫不进来",
            dao.existsActiveByResult("TRASHED-1")
        )
    }

    /** 同一内容既在回收站又有活跃记录时，应判定为已存在。 */
    @Test
    fun existsActiveByResult_returnsTrue_whenActiveAndTrashedCoexist() = runTest {
        insert("DUP-1", deleted = true)
        insert("DUP-1", deleted = false)
        assertTrue(dao.existsActiveByResult("DUP-1"))
    }

    @Test
    fun existsActiveByResult_isExactMatch_notPrefix() = runTest {
        insert("ABC-123456")
        assertFalse("不应做前缀匹配", dao.existsActiveByResult("ABC-123"))
    }

    @Test
    fun existsActiveByResult_handlesSpecialCharacters() = runTest {
        val tricky = "WIFI:T:WPA;S:O'ffice;P:12%_34\";;"
        insert(tricky)
        assertTrue("含引号/百分号/下划线的内容不应被当作通配符", dao.existsActiveByResult(tricky))
        assertFalse(dao.existsActiveByResult("WIFI:T:WPA;S:O'ffice;P:99999;;"))
    }

    @Test
    fun existsActiveByResult_handlesEmptyAndLongContent() = runTest {
        val long = "X".repeat(4000)
        insert("")
        insert(long)
        assertTrue(dao.existsActiveByResult(""))
        assertTrue(dao.existsActiveByResult(long))
    }
}
