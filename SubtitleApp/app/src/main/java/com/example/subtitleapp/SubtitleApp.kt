package com.example.subtitleapp

import android.app.Application
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.example.subtitleapp.model.SubtitleEntry

class SubtitleApp : Application() {
    var subtitles: MutableList<SubtitleEntry> = mutableListOf()
    var videoLocalPath: String? = null

    override fun onCreate() {
        super.onCreate()
        // 初始化 FFmpegKit 字体目录，解决 ass/subtitles 滤镜找不到字体的问题
        FFmpegKitConfig.setFontDirectory(this, "/system/fonts", null)
    }
}
