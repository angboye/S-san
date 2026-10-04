package com.example.sscan.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "scan_history",
    indices = [Index(value = ["result"])]
)
data class ScanHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "result")
    val result: String,
    @ColumnInfo(name = "image_uri")
    val imageUri: String = "",
    @ColumnInfo(name = "custom_title")
    val customTitle: String = "",
    @ColumnInfo(name = "group_name")
    val groupName: String = "",
    @ColumnInfo(name = "time")
    val time: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "deleted")
    val deleted: Boolean = false
)
