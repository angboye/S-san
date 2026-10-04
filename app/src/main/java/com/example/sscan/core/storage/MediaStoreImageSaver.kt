package com.example.sscan.core.storage

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaStoreImageSaver @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        val DEFAULT_RELATIVE_PATH: String = "${Environment.DIRECTORY_PICTURES}/MultiQR"
    }

    data class SavedImageItem(
        val uri: String,
        val displayName: String,
        val relativePath: String,
        val dateTaken: Long,
        val sizeBytes: Long,
        val isFromAppAlbum: Boolean
    )

    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun saveBitmap(
        bitmap: Bitmap,
        fileName: String = defaultFileName(),
        quality: Int = 90,
        relativePath: String = DEFAULT_RELATIVE_PATH
    ): Uri = withContext(Dispatchers.IO) {
        require(quality in 0..100) { "JPEG quality must be between 0 and 100." }

        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
        }

        val uri = resolver.insert(collection, values)
            ?: throw IOException("Failed to create MediaStore record.")

        try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                val compressed = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
                if (!compressed) {
                    throw IOException("Bitmap compression failed.")
                }
            } ?: throw IOException("Failed to open MediaStore output stream.")

            val publishValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, publishValues, null, null)

            uri
        } catch (throwable: Throwable) {
            resolver.delete(uri, null, null)
            throw throwable
        }
    }

    private fun defaultFileName(): String {
        return "QR_${System.currentTimeMillis()}.jpg"
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun saveJpegFile(
        sourceFile: File,
        fileName: String = defaultFileName(),
        relativePath: String = DEFAULT_RELATIVE_PATH
    ): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Images.Media.SIZE, sourceFile.length())
        }

        val uri = resolver.insert(collection, values)
            ?: throw IOException("Failed to create MediaStore record.")

        try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                sourceFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: throw IOException("Failed to open MediaStore output stream.")

            val publishValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, publishValues, null, null)
            uri
        } catch (throwable: Throwable) {
            resolver.delete(uri, null, null)
            throw throwable
        }
    }

    suspend fun loadSavedImages(
        limit: Int = 100,
        relativePathPrefix: String = DEFAULT_RELATIVE_PATH
    ): List<SavedImageItem> = withContext(Dispatchers.IO) {
        queryImages(
            limit = limit,
            selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            } else {
                "${MediaStore.Images.Media.DATA} LIKE ?"
            },
            selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf("$relativePathPrefix%")
            } else {
                arrayOf("%\\MultiQR\\%")
            }
        )
    }

    suspend fun renameImage(uri: String, newDisplayName: String) = withContext(Dispatchers.IO) {
        val normalizedName = ensureJpegSuffix(newDisplayName.trim())
        require(normalizedName.isNotBlank()) { "Display name cannot be blank." }
        val targetUri = uri.toUri()
        val updated = context.contentResolver.update(
            targetUri,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, normalizedName)
            },
            null,
            null
        )
        if (updated <= 0) {
            throw IOException("Failed to rename image.")
        }
    }

    suspend fun deleteImage(uri: String) = withContext(Dispatchers.IO) {
        val deleted = context.contentResolver.delete(uri.toUri(), null, null)
        if (deleted <= 0) {
            throw IOException("Failed to delete image.")
        }
    }

    private fun queryImages(
        limit: Int,
        selection: String? = null,
        selectionArgs: Array<String>? = null
    ): List<SavedImageItem> {
        val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.SIZE
            )
        } else {
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.SIZE
            )
        }

        val result = mutableListOf<SavedImageItem>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.RELATIVE_PATH
            } else {
                MediaStore.Images.Media.DATA
            }
            val pathIndex = cursor.getColumnIndexOrThrow(pathColumn)
            val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

            while (cursor.moveToNext() && result.size < limit) {
                val id = cursor.getLong(idIndex)
                val relativePath = cursor.getString(pathIndex).orEmpty()
                val contentUri = Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id.toString()
                )
                result += SavedImageItem(
                    uri = contentUri.toString(),
                    displayName = cursor.getString(nameIndex).orEmpty(),
                    relativePath = relativePath,
                    dateTaken = cursor.getLong(dateIndex),
                    sizeBytes = cursor.getLong(sizeIndex),
                    isFromAppAlbum = isAppManagedPath(relativePath)
                )
            }
        }
        return result
    }

    private fun isAppManagedPath(path: String): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            path.startsWith(DEFAULT_RELATIVE_PATH)
        } else {
            path.contains("\\MultiQR\\")
        }
    }

    private fun ensureJpegSuffix(name: String): String {
        if (name.isBlank()) return name
        return if (name.endsWith(".jpg", ignoreCase = true) || name.endsWith(
                ".jpeg",
                ignoreCase = true
            )
        ) {
            name
        } else {
            "$name.jpg"
        }
    }
}
