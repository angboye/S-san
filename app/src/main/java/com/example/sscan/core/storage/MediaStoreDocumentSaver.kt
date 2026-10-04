package com.example.sscan.core.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File

/**
 * 把导出文件发布到系统「下载」目录。
 *
 * 与 [MediaStoreImageSaver] 的取向一致（不申请 WRITE_EXTERNAL_STORAGE），但**行为必须不同**：
 * 图片在 API < 29 上是静默不保存，而导出不能静默失败——老机器仍要产出文件、仍能分享与
 * 用 WPS 打开，只是落点在 app 私有目录、卸载会丢。
 *
 * 做成 object 而不是 Hilt 单例，与 [com.example.sscan.core.settings.AppSettings] 一致，
 * 避免为一处调用改动 DI 图。
 */
object MediaStoreDocumentSaver {

    /** 导出结果。[savedToDownloads] 为 false 时说明落在了 app 私有目录（API < 29 回退）。 */
    data class SavedDocument(
        val uri: Uri,
        val displayName: String,
        val mimeType: String,
        val savedToDownloads: Boolean
    )

    /**
     * 把 [source] 发布出去，成功后删除 [source]（它只是中转用的缓存文件）。
     *
     * API 29+ 走 MediaStore.Downloads：文件管理器可见、卸载不丢、无需任何存储权限。
     * API 24~28 回退到 `getExternalFilesDir(DIRECTORY_DOCUMENTS)` 并用 FileProvider 包装，
     * 保证返回的 Uri 在两条路径上可以被同一套分享/打开逻辑消费。
     */
    fun publish(context: Context, source: File, displayName: String, mimeType: String): SavedDocument {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishToDownloads(context, source, displayName, mimeType)?.let { uri ->
                source.delete()
                return SavedDocument(uri, displayName, mimeType, savedToDownloads = true)
            }
        }
        val fallback = copyToPrivateDocuments(context, source, displayName)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            fallback
        )
        return SavedDocument(uri, displayName, mimeType, savedToDownloads = false)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishToDownloads(
        context: Context,
        source: File,
        displayName: String,
        mimeType: String
    ): Uri? = runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            // IS_PENDING 期间其它 App 看不到这个文件，写完再放出来，避免读到半截文件
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching null
        resolver.openOutputStream(uri)?.use { output ->
            source.inputStream().use { it.copyTo(output) }
        } ?: run {
            resolver.delete(uri, null, null)
            return@runCatching null
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        uri
    }.getOrNull()

    private fun copyToPrivateDocuments(context: Context, source: File, displayName: String): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, displayName)
        if (source.absolutePath != target.absolutePath) {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
        return target
    }

    fun mimeTypeOf(displayName: String): String {
        return when (displayName.substringAfterLast('.', "").lowercase()) {
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "csv" -> "text/csv"
            "html" -> "text/html"
            else -> "text/plain"
        }
    }
}
