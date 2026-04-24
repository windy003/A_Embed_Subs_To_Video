package com.example.subtitleapp.service

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.ReturnCode
import com.antonkarpenko.ffmpegkit.StatisticsCallback
import com.example.subtitleapp.model.SubtitleEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CountDownLatch

object VideoExporter {

    private const val TAG = "VideoExporter"

    data class ExportResult(
        val success: Boolean,
        val outputPath: String = "",
        val error: String = ""
    )

    /**
     * @param positionY  字幕垂直位置比例 (0.0=顶部, 1.0=底部)
     * @param fontScale  字体缩放比例
     */
    suspend fun exportWithSubtitles(
        context: Context,
        videoPath: String,
        subtitles: List<SubtitleEntry>,
        outputName: String = "output_subtitled",
        positionY: Float = 0.85f,
        fontScale: Float = 1.0f,
        onProgress: ((String) -> Unit)? = null,
        onPercent: ((Int) -> Unit)? = null
    ): ExportResult = withContext(Dispatchers.IO) {

        val outputFile = File(context.cacheDir, "${outputName}.mp4")
        if (outputFile.exists()) outputFile.delete()

        try {
            val durationMs = getVideoDurationMs(videoPath)

            onProgress?.invoke("正在烧录字幕 0%...")
            onPercent?.invoke(0)

            // 获取视频分辨率
            val resolution = getVideoResolution(videoPath)
            val videoW = resolution.first
            val videoH = resolution.second

            // 生成 ASS 字幕文件
            val assFile = File(context.cacheDir, "sub.ass")
            writeAssFile(subtitles, assFile, videoW, videoH, positionY, fontScale)

            Log.d(TAG, "ASS path: ${assFile.absolutePath}, size=${assFile.length()}")

            // 构建 FFmpeg 命令
            // 用 ass 滤镜烧录字幕
            val cmd = "-i ${quote(videoPath)} " +
                "-vf ass=${quote(assFile.absolutePath)} " +
                "-c:v libx264 -preset fast -crf 23 " +
                "-c:a copy -y " +
                quote(outputFile.absolutePath)

            Log.d(TAG, "FFmpeg cmd: $cmd")

            val latch = CountDownLatch(1)
            var lastPct = -1

            val session = FFmpegKit.executeAsync(
                cmd,
                { latch.countDown() },
                { log -> Log.d("FFmpeg", log.message ?: "") },
                StatisticsCallback { statistics ->
                    if (durationMs > 0) {
                        val timeMs = statistics.time
                        val pct = ((timeMs.toFloat() / durationMs) * 100).toInt().coerceIn(0, 99)
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress?.invoke("正在烧录字幕 $pct%...")
                            onPercent?.invoke(pct)
                        }
                    }
                }
            )

            latch.await()

            val returnCode = session.returnCode
            Log.d(TAG, "FFmpeg return: ${returnCode.value}")

            if (!ReturnCode.isSuccess(returnCode)) {
                val logs = session.allLogsAsString ?: ""
                Log.e(TAG, "FFmpeg failed:\n$logs")
                assFile.delete()
                return@withContext ExportResult(false,
                    error = "FFmpeg 错误 (${returnCode.value}): ${logs.takeLast(500)}")
            }

            assFile.delete()

            onProgress?.invoke("正在保存到相册...")
            onPercent?.invoke(100)
            val savedUri = saveToMediaStore(context, outputFile, outputName)
            onProgress?.invoke("导出完成！")

            if (savedUri != null) {
                ExportResult(true, outputPath = savedUri.toString())
            } else {
                ExportResult(true, outputPath = outputFile.absolutePath)
            }

        } catch (e: Exception) {
            e.printStackTrace()
            outputFile.delete()
            ExportResult(false, error = "导出失败: ${e.message}")
        }
    }

    /** 给路径加单引号，内部单引号转义 */
    private fun quote(path: String): String {
        val escaped = path.replace("'", "'\\''")
        return "'$escaped'"
    }

    private fun writeAssFile(
        subtitles: List<SubtitleEntry>,
        file: File,
        videoW: Int,
        videoH: Int,
        positionY: Float,
        fontScale: Float
    ) {
        val playResX = if (videoW > 0) videoW else 1920
        val playResY = if (videoH > 0) videoH else 1080

        // 基础字号：与预览一致 = playResY / 22 * fontScale
        val fontSize = ((playResY / 22f) * fontScale).toInt()

        // 描边宽度：与预览 OutlineTextView 一致 = fontSize * 6%
        val outline = (fontSize * 0.06f).coerceAtLeast(1f).toInt()

        // positionY 是字幕中心点在视频中的 Y 比例 (0=顶, 1=底)
        // ASS 用 \pos 覆盖标记来精确定位，避免 Alignment+MarginV 的换算偏差
        // Alignment=2(底部居中) 仅作为 fallback，实际位置由 \pos 控制
        val posX = playResX / 2
        val posY = (positionY * playResY).toInt().coerceIn(fontSize, playResY - 10)

        val sb = StringBuilder()
        sb.appendLine("[Script Info]")
        sb.appendLine("ScriptType: v4.00+")
        sb.appendLine("PlayResX: $playResX")
        sb.appendLine("PlayResY: $playResY")
        sb.appendLine()
        sb.appendLine("[V4+ Styles]")
        sb.appendLine("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding")
        sb.appendLine("Style: Default,Noto Sans CJK SC,$fontSize,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,-1,0,0,0,100,100,0,0,1,$outline,1,2,20,20,20,1")
        sb.appendLine()
        sb.appendLine("[Events]")
        sb.appendLine("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")
        for (entry in subtitles) {
            val start = msToAssTime(entry.startMs)
            val end = msToAssTime(entry.endMs)
            val text = entry.text.replace("\n", "\\N")
            // 用 \pos(x,y) 精确控制位置，与预览完全对应
            sb.appendLine("Dialogue: 0,$start,$end,Default,,0,0,0,,{\\pos($posX,$posY)}$text")
        }
        file.writeText(sb.toString(), Charsets.UTF_8)
    }

    private fun msToAssTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val centiseconds = (ms % 1000) / 10
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return "%d:%02d:%02d.%02d".format(hours, minutes, seconds, centiseconds)
    }

    private fun getVideoDurationMs(videoPath: String): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(videoPath)
            val duration = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            retriever.release()
            duration
        } catch (_: Exception) { 0L }
    }

    private fun getVideoResolution(videoPath: String): Pair<Int, Int> {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(videoPath)
            val w = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull() ?: 0
            retriever.release()
            if (rotation == 90 || rotation == 270) Pair(h, w) else Pair(w, h)
        } catch (_: Exception) { Pair(0, 0) }
    }
    private fun saveToMediaStore(context: Context, file: File, name: String): Uri? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "${name}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + "/SubtitleApp")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { os ->
                file.inputStream().use { it.copyTo(os) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            }
            file.delete()
            uri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
