package com.example.subtitleapp.service

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * 从视频文件中提取音频轨道，输出为压缩的 m4a 文件。
 * 不做解码/重编码，直接复制音频流，速度极快、无损。
 */
object AudioExtractor {

    fun extractAudio(videoPath: String, outputFile: File): Boolean {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(videoPath)

            // 找音频轨
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }
            if (audioTrackIndex == -1 || audioFormat == null) return false

            extractor.selectTrack(audioTrackIndex)

            // 用 MediaMuxer 直接复制音频流到 mp4/m4a 容器
            val muxer = MediaMuxer(
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            val outTrackIndex = muxer.addTrack(audioFormat)
            muxer.start()

            val buffer = ByteBuffer.allocate(1024 * 1024)
            val bufferInfo = android.media.MediaCodec.BufferInfo()

            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = if (extractor.sampleFlags and
                    MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                    android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME else 0

                muxer.writeSampleData(outTrackIndex, buffer, bufferInfo)
                extractor.advance()
            }

            muxer.stop()
            muxer.release()
            extractor.release()
            return true

        } catch (e: Exception) {
            e.printStackTrace()
            extractor.release()
            return false
        }
    }
}
