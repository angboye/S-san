package com.example.sscan.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.sscan.data.local.entity.ScanHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ScanHistoryEntity): Long

    @Update
    suspend fun update(entity: ScanHistoryEntity)

    @Query("SELECT * FROM scan_history WHERE deleted = 0 ORDER BY time DESC")
    fun observeHistory(): Flow<List<ScanHistoryEntity>>

    @Query(
        """
        SELECT * FROM scan_history
        WHERE deleted = 0
          AND (:startTime IS NULL OR time >= :startTime)
          AND (
              :query = '' OR
              result LIKE '%' || :query || '%' OR
              custom_title LIKE '%' || :query || '%' OR
              strftime('%Y-%m-%d %H:%M:%S', time / 1000, 'unixepoch', 'localtime') LIKE '%' || :query || '%'
          )
        ORDER BY time DESC
        """
    )
    fun observeHistoryFiltered(query: String, startTime: Long?): Flow<List<ScanHistoryEntity>>

    @Query("SELECT * FROM scan_history WHERE id = :id LIMIT 1")
    fun observeById(id: Long): Flow<ScanHistoryEntity?>

    @Query("SELECT * FROM scan_history WHERE deleted = 1 ORDER BY time DESC")
    fun observeTrash(): Flow<List<ScanHistoryEntity>>

    /** 回收站里的记录不算重复，允许用户删掉后重新扫入。 */
    @Query("SELECT EXISTS(SELECT 1 FROM scan_history WHERE result = :result AND deleted = 0)")
    suspend fun existsActiveByResult(result: String): Boolean

    @Query("UPDATE scan_history SET deleted = 1 WHERE id = :id")
    suspend fun moveToTrash(id: Long)

    @Query("UPDATE scan_history SET deleted = 0 WHERE id = :id")
    suspend fun restore(id: Long)

    @Query("UPDATE scan_history SET custom_title = :title WHERE id = :id")
    suspend fun updateCustomTitle(id: Long, title: String)

    @Query("UPDATE scan_history SET result = :result WHERE id = :id")
    suspend fun updateResult(id: Long, result: String)

    @Query("DELETE FROM scan_history WHERE id = :id")
    suspend fun deletePermanently(id: Long)

    @Query("DELETE FROM scan_history")
    suspend fun clearAll()

    /** 批量移动记录到指定文件夹（修改 groupName） */
    @Query("UPDATE scan_history SET group_name = :groupName WHERE id IN (:ids)")
    suspend fun moveToGroup(groupName: String, ids: List<Long>)

    /** 把某个文件夹下所有记录的 groupName 重命名为新名字 */
    @Query("UPDATE scan_history SET group_name = :newName WHERE group_name = :oldName AND deleted = 0")
    suspend fun renameGroup(oldName: String, newName: String)

    /** 批量永久删除 */
    @Query("DELETE FROM scan_history WHERE id IN (:ids)")
    suspend fun deletePermanentlyByIds(ids: List<Long>)

    /** 批量移到回收站 */
    @Query("UPDATE scan_history SET deleted = 1 WHERE id IN (:ids)")
    suspend fun moveToTrashByIds(ids: List<Long>)

    /** 查询所有 groupName 字符串（非空），用于编辑模式的文件夹列表 */
    @Query("SELECT DISTINCT group_name FROM scan_history WHERE group_name IS NOT NULL AND group_name != '' AND deleted = 0 ORDER BY time DESC")
    suspend fun getAllGroupNames(): List<String>
}
