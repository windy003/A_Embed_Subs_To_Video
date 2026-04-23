package com.example.subtitleapp.util

import com.example.subtitleapp.model.SubtitleEntry
import java.io.File

object SrtUtil {

    /**
     * 毫秒 -> SRT 时间格式  00:01:23,456
     */
    fun msToSrtTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val millis = ms % 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, millis)
    }

    /**
     * 将字幕列表写入 SRT 文件
     */
    fun writeSrt(entries: List<SubtitleEntry>, file: File) {
        val sb = StringBuilder()
        entries.forEachIndexed { i, entry ->
            sb.appendLine(i + 1)
            sb.appendLine("${msToSrtTime(entry.startMs)} --> ${msToSrtTime(entry.endMs)}")
            sb.appendLine(entry.text)
            sb.appendLine()
        }
        file.writeText(sb.toString(), Charsets.UTF_8)
    }

    /**
     * 解析 SRT 文件为字幕列表
     */
    fun parseSrt(content: String): List<SubtitleEntry> {
        val entries = mutableListOf<SubtitleEntry>()
        val blocks = content.trim().split(Regex("\\n\\s*\\n"))
        for (block in blocks) {
            val lines = block.trim().lines()
            if (lines.size < 3) continue
            val index = lines[0].trim().toIntOrNull() ?: continue
            val timeParts = lines[1].split("-->")
            if (timeParts.size != 2) continue
            val startMs = parseSrtTime(timeParts[0].trim())
            val endMs = parseSrtTime(timeParts[1].trim())
            val text = lines.drop(2).joinToString("\n")
            entries.add(SubtitleEntry(index, startMs, endMs, text))
        }
        return entries
    }

    private fun parseSrtTime(time: String): Long {
        // 00:01:23,456
        val parts = time.replace(",", ":").split(":")
        if (parts.size != 4) return 0
        val h = parts[0].toLongOrNull() ?: 0
        val m = parts[1].toLongOrNull() ?: 0
        val s = parts[2].toLongOrNull() ?: 0
        val ms = parts[3].toLongOrNull() ?: 0
        return h * 3600000 + m * 60000 + s * 1000 + ms
    }
}
