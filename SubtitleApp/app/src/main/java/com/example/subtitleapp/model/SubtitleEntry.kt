package com.example.subtitleapp.model

/**
 * 一条字幕记录：序号、起止时间（毫秒）、文字内容
 */
data class SubtitleEntry(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String
)
