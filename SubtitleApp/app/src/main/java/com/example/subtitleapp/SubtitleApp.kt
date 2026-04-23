package com.example.subtitleapp

import android.app.Application
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.example.subtitleapp.model.SubtitleEntry

class SubtitleApp : Application() {
    var subtitles: MutableList<SubtitleEntry> = mutableListOf()
    var videoLocalPath: String? = null

    /** 字幕样式：垂直位置比例 (0.0=顶部, 1.0=底部)，默认底部 */
    var subtitlePositionY: Float = 0.85f
    /** 字幕字号缩放比例，默认 1.0 */
    var subtitleScale: Float = 1.0f

    override fun onCreate() {
        super.onCreate()
        // 初始化 FFmpegKit 字体目录，解决 ass/subtitles 滤镜找不到字体的问题
        FFmpegKitConfig.setFontDirectory(this, "/system/fonts", null)
    }
}
