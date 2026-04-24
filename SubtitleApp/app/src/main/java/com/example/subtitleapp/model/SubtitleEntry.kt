package com.example.subtitleapp.model

/**
 * 一条字幕记录：序号、起止时间（毫秒）、文字内容、独立坐标
 */
data class SubtitleEntry(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** 水平位置比例 (0.0=最左, 1.0=最右)，默认居中 */
    val positionX: Float = 0.5f,
    /** 垂直位置比例 (0.0=顶部, 1.0=底部)，默认底部 */
    val positionY: Float = 0.85f,
    /** 字号缩放比例，默认 1.0 */
    val scale: Float = 1.0f
)
