package com.example.sscan.shared

/**
 * 平台信息 - 跨平台共享接口
 */
expect class Platform() {
    val name: String
}

/**
 * 扫码结果数据模型 - 跨平台共享
 */
data class ScanResult(
    val content: String,
    val format: String,
    val timestamp: Long
)

/**
 * 扫码统计 - 跨平台共享
 */
data class ScanStats(
    val totalCount: Int,
    val todayCount: Int,
    val weekCount: Int
)

/**
 * 共享工具函数
 */
object ScanUtils {
    fun formatTimestamp(timestamp: Long): String {
        val seconds = timestamp / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        return when {
            days > 0 -> "${days}天前"
            hours > 0 -> "${hours}小时前"
            minutes > 0 -> "${minutes}分钟前"
            else -> "刚刚"
        }
    }

    fun isHttpUrl(content: String): Boolean {
        return content.startsWith("http://") || content.startsWith("https://")
    }
}
