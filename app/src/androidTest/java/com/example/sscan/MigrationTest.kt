package com.example.sscan

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.sscan.data.local.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room 3 -> 4 迁移测试。
 *
 * 这是全部改动里唯一「出错即静默清空用户数据」的地方：DatabaseModule 保留了
 * fallbackToDestructiveMigration() 作为异常兜底，一旦迁移写错就会走到那条路径、
 * 把用户历史全部清掉。
 *
 * 注意这里没有用标准的 MigrationTestHelper —— 它依赖导出的 schema JSON，
 * 而 v3 的 JSON 在加索引之前就不存在了。所以直接对迁移 SQL 本身做验证。
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private companion object {
        const val DB_NAME = "migration_test.db"
        const val INDEX_NAME = "index_scan_history_result"

        /** v3 建表语句：与加索引之前的 schema 完全一致。 */
        const val CREATE_V3_TABLE = """
            CREATE TABLE `scan_history` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `result` TEXT NOT NULL,
                `image_uri` TEXT NOT NULL,
                `custom_title` TEXT NOT NULL,
                `group_name` TEXT NOT NULL,
                `time` INTEGER NOT NULL,
                `deleted` INTEGER NOT NULL
            )
        """
    }

    private lateinit var helper: SupportSQLiteOpenHelper

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(DB_NAME)
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                })
                .build()
        )
    }

    @After
    fun tearDown() {
        helper.close()
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .deleteDatabase(DB_NAME)
    }

    private fun seedV3(rows: Int): SupportSQLiteDatabase {
        val db = helper.writableDatabase
        db.execSQL(CREATE_V3_TABLE)
        repeat(rows) { i ->
            db.execSQL(
                "INSERT INTO scan_history " +
                    "(result, image_uri, custom_title, group_name, time, deleted) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf("CODE-$i", "", "", if (i % 2 == 0) "组A" else "", 1_700_000_000_000L + i, 0)
            )
        }
        return db
    }

    private fun SupportSQLiteDatabase.rowCount(): Int =
        query("SELECT COUNT(*) FROM scan_history").use { it.moveToFirst(); it.getInt(0) }

    private fun SupportSQLiteDatabase.hasResultIndex(): Boolean =
        query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name=?",
            arrayOf(INDEX_NAME)
        ).use { it.moveToFirst(); it.getInt(0) > 0 }

    @Test
    fun migration3To4_createsResultIndex() {
        val db = seedV3(rows = 3)
        assertTrue("迁移前不应存在索引", !db.hasResultIndex())

        AppDatabase.MIGRATION_3_4.migrate(db)

        assertTrue("迁移后必须创建 $INDEX_NAME", db.hasResultIndex())
    }

    @Test
    fun migration3To4_preservesAllRows() {
        val db = seedV3(rows = 5)
        assertEquals(5, db.rowCount())

        AppDatabase.MIGRATION_3_4.migrate(db)

        assertEquals("迁移绝不能丢数据", 5, db.rowCount())
    }

    @Test
    fun migration3To4_preservesRowContent() {
        val db = seedV3(rows = 3)

        AppDatabase.MIGRATION_3_4.migrate(db)

        db.query("SELECT result, group_name, time FROM scan_history ORDER BY id").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("CODE-0", c.getString(0))
            assertEquals("组A", c.getString(1))
            assertEquals(1_700_000_000_000L, c.getLong(2))
        }
    }

    /** 迁移 SQL 用的是 CREATE INDEX IF NOT EXISTS，重复执行不应抛异常。 */
    @Test
    fun migration3To4_isIdempotent() {
        val db = seedV3(rows = 2)

        AppDatabase.MIGRATION_3_4.migrate(db)
        AppDatabase.MIGRATION_3_4.migrate(db)

        assertTrue(db.hasResultIndex())
        assertEquals(2, db.rowCount())
    }

    /** 空库迁移也不能出错（新装用户第一次升级的边界）。 */
    @Test
    fun migration3To4_onEmptyTable() {
        val db = seedV3(rows = 0)

        AppDatabase.MIGRATION_3_4.migrate(db)

        assertTrue(db.hasResultIndex())
        assertEquals(0, db.rowCount())
    }
}
