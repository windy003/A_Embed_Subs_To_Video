package com.example.subtitleapp

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.subtitleapp.adapter.SubtitleAdapter
import com.example.subtitleapp.databinding.ActivityEditorBinding
import com.example.subtitleapp.service.VideoExporter
import com.example.subtitleapp.util.OutlineTextView
import com.example.subtitleapp.util.SrtUtil
import kotlinx.coroutines.launch
import java.io.File

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private val app by lazy { application as SubtitleApp }

    private var player: ExoPlayer? = null
    private lateinit var adapter: SubtitleAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var subtitleTracker: Runnable? = null

    // 视频原始分辨率
    private var videoW = 0
    private var videoH = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadVideoResolution()
        setupToolbar()
        setupRecyclerView()
        setupPlayer()
        setupButtons()
        refreshList()
    }

    private fun loadVideoResolution() {
        val path = app.videoLocalPath ?: return
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(path)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            retriever.release()
            if (rot == 90 || rot == 270) { videoW = h; videoH = w } else { videoW = w; videoH = h }
        } catch (_: Exception) {}
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        adapter = SubtitleAdapter(
            onTextChanged = { index, newText ->
                if (index in app.subtitles.indices) {
                    app.subtitles[index] = app.subtitles[index].copy(text = newText)
                }
            },
            onClick = { _, entry ->
                player?.seekTo(entry.startMs)
            },
            onLongClick = { index ->
                if (index in app.subtitles.indices) {
                    AlertDialog.Builder(this)
                        .setTitle("删除字幕")
                        .setMessage("确定要删除这条字幕吗？")
                        .setPositiveButton("删除") { _, _ ->
                            app.subtitles.removeAt(index)
                            reindex()
                            refreshList()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        )
        binding.rvSubtitles.layoutManager = LinearLayoutManager(this)
        binding.rvSubtitles.adapter = adapter

        // 点击视频区域时退出字幕编辑状态
        binding.playerView.setOnClickListener {
            adapter.clearEditing(binding.rvSubtitles)
        }
    }

    private fun setupPlayer() {
        val videoPath = app.videoLocalPath ?: return
        val videoFile = File(videoPath)
        if (!videoFile.exists()) return

        player = ExoPlayer.Builder(this).build().also { exo ->
            binding.playerView.player = exo
            exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(videoFile)))
            exo.prepare()

            exo.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) startSubtitleTracking()
                    else {
                        stopSubtitleTracking()
                        updateSubtitleDisplay()
                    }
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        // 播放器准备好后计算视频显示区域，初始化字幕叠加
                        binding.playerView.post { setupSubtitleOverlay() }
                    }
                }
            })
        }
    }

    /**
     * 计算视频在 PlayerView 中的实际显示区域，
     * 让字幕预览的字号、位置与 ASS 导出完全一致。
     */
    private fun setupSubtitleOverlay() {
        if (videoW <= 0 || videoH <= 0) return

        val playerView = binding.playerView
        val viewW = playerView.width
        val viewH = playerView.height
        if (viewW <= 0 || viewH <= 0) return

        // 计算 fit-center 后视频的实际显示区域
        val videoAspect = videoW.toFloat() / videoH
        val viewAspect = viewW.toFloat() / viewH

        val displayW: Int
        val displayH: Int
        if (videoAspect > viewAspect) {
            // 视频更宽，上下留黑边
            displayW = viewW
            displayH = (viewW / videoAspect).toInt()
        } else {
            // 视频更高，左右留黑边
            displayH = viewH
            displayW = (viewH * videoAspect).toInt()
        }

        val offsetX = (viewW - displayW) / 2
        val offsetY = (viewH - displayH) / 2

        val overlay = binding.tvCurrentSubtitle as OutlineTextView

        // 告诉 OutlineTextView 视频的实际显示区域
        overlay.setVideoDisplayRect(offsetX, offsetY, offsetX + displayW, offsetY + displayH)

        // 基准字号：与 ASS 一致 = displayHeight / 22
        // ASS 中 FontSize 基于 PlayResY，预览中按 displayH 等比缩放
        val baseFontPx = displayH / 22f
        overlay.setBaseFontSize(baseFontPx)
        overlay.setScale(app.subtitleScale)

        // 设置宽度与视频显示区域一致，防止文字溢出视频两侧
        overlay.layoutParams = overlay.layoutParams.apply {
            width = displayW
        }
        overlay.x = offsetX.toFloat()

        // 恢复位置
        overlay.setPositionInVideo(app.subtitlePositionY)

        overlay.onPositionChanged = { yRatio -> app.subtitlePositionY = yRatio }
        overlay.onScaleChanged = { scale -> app.subtitleScale = scale }

        updateSubtitleDisplay()
    }

    private fun startSubtitleTracking() {
        subtitleTracker = object : Runnable {
            override fun run() {
                updateSubtitleDisplay()
                handler.postDelayed(this, 100)
            }
        }
        handler.post(subtitleTracker!!)
    }

    private fun stopSubtitleTracking() {
        subtitleTracker?.let { handler.removeCallbacks(it) }
    }

    private fun updateSubtitleDisplay() {
        val pos = player?.currentPosition ?: 0
        val current = app.subtitles.findLast { pos >= it.startMs && pos < it.endMs }
            ?: app.subtitles.findLast { pos >= it.startMs && pos <= it.endMs }
        binding.tvCurrentSubtitle.text = current?.text ?: ""
        binding.tvCurrentSubtitle.visibility =
            if (current != null) View.VISIBLE else View.INVISIBLE

        // 高亮并自动滚动到当前字幕
        val idx = if (current != null) app.subtitles.indexOf(current) else -1
        if (idx != adapter.highlightedPosition) {
            adapter.highlightedPosition = idx
            if (idx >= 0) {
                val layoutManager = binding.rvSubtitles.layoutManager as? LinearLayoutManager
                // 只在当前项不可见时才滚动，避免干扰用户编辑
                val first = layoutManager?.findFirstCompletelyVisibleItemPosition() ?: 0
                val last = layoutManager?.findLastCompletelyVisibleItemPosition() ?: 0
                if (idx !in first..last) {
                    binding.rvSubtitles.smoothScrollToPosition(idx)
                }
            }
        }
    }

    private fun setupButtons() {
        binding.btnExportVideo.setOnClickListener { exportVideo() }

    }


    private fun exportVideo() {
        val path = app.videoLocalPath ?: return
        if (app.subtitles.isEmpty()) {
            Toast.makeText(this, "没有字幕可导出", Toast.LENGTH_SHORT).show()
            return
        }

        binding.layoutExportProgress.visibility = View.VISIBLE
        binding.btnExportVideo.isEnabled = false
        binding.tvExportStatus.text = "正在导出..."
        binding.exportProgressBar.isIndeterminate = false
        binding.exportProgressBar.progress = 0
        binding.exportProgressBar.max = 100

        lifecycleScope.launch {
            val result = VideoExporter.exportWithSubtitles(
                context = this@EditorActivity,
                videoPath = path,
                subtitles = app.subtitles.toList(),
                outputName = "subtitled_${System.currentTimeMillis()}",
                positionY = app.subtitlePositionY,
                fontScale = app.subtitleScale,
                onProgress = { status ->
                    runOnUiThread { binding.tvExportStatus.text = status }
                },
                onPercent = { pct ->
                    runOnUiThread { binding.exportProgressBar.progress = pct }
                }
            )

            binding.layoutExportProgress.visibility = View.GONE
            binding.btnExportVideo.isEnabled = true

            if (result.success) {
                AlertDialog.Builder(this@EditorActivity)
                    .setTitle("导出完成")
                    .setMessage("视频已保存到:\n${result.outputPath}")
                    .setPositiveButton("确定", null)
                    .show()
            } else {
                Toast.makeText(this@EditorActivity,
                    "导出失败: ${result.error}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshList() {
        adapter.submitList(app.subtitles.toList())
        binding.tvSubtitleCount.text = "共 ${app.subtitles.size} 条字幕"
    }

    private fun reindex() {
        app.subtitles.forEachIndexed { i, entry ->
            app.subtitles[i] = entry.copy(index = i + 1)
        }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
        stopSubtitleTracking()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSubtitleTracking()
        player?.release()
        player = null
    }
}
