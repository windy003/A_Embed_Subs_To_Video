package com.example.subtitleapp

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.subtitleapp.databinding.ActivityMainBinding
import com.example.subtitleapp.service.AudioExtractor
import com.example.subtitleapp.service.WhisperService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val app by lazy { application as SubtitleApp }

    private val PREFS_NAME = "subtitle_app_prefs"
    private val KEY_API_KEY = "openai_api_key"
    private val KEY_LANGUAGE = "language"
    private val KEY_PROVIDER = "provider"
    private val KEY_CUSTOM_URL = "custom_url"

    private val providers = WhisperService.Provider.entries.toList()

    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            onVideoPicked(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupProviderSpinner()
        loadSavedSettings()

        binding.btnSelectVideo.setOnClickListener {
            pickVideo.launch(arrayOf("video/*"))
        }

        binding.btnStartRecognize.setOnClickListener {
            startRecognition()
        }

        // 默认选中"自动"
        if (binding.toggleLanguage.checkedButtonId == View.NO_ID) {
            binding.toggleLanguage.check(R.id.btnLangAuto)
        }
    }

    override fun onPause() {
        super.onPause()
        saveSettings()
    }

    // ---- 服务商选择 ----

    private fun setupProviderSpinner() {
        val names = providers.map { it.displayName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerProvider.adapter = adapter

        binding.spinnerProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val provider = providers[pos]
                // 自定义模式才显示 URL 输入框
                binding.layoutCustomUrl.visibility =
                    if (provider == WhisperService.Provider.CUSTOM) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun getSelectedProvider(): WhisperService.Provider {
        return providers[binding.spinnerProvider.selectedItemPosition]
    }

    private fun getApiBaseUrl(): String {
        val provider = getSelectedProvider()
        return if (provider == WhisperService.Provider.CUSTOM) {
            binding.etCustomUrl.text.toString().trim()
        } else {
            provider.baseUrl
        }
    }

    private fun getModelName(): String {
        val provider = getSelectedProvider()
        return if (provider == WhisperService.Provider.CUSTOM) {
            "whisper-1"  // 自定义默认用 whisper-1
        } else {
            provider.modelName
        }
    }

    // ---- 设置持久化 ----

    private fun loadSavedSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        binding.etApiKey.setText(prefs.getString(KEY_API_KEY, "") ?: "")

        val savedProvider = prefs.getString(KEY_PROVIDER, "OPENAI") ?: "OPENAI"
        val providerIdx = providers.indexOfFirst { it.name == savedProvider }.coerceAtLeast(0)
        binding.spinnerProvider.setSelection(providerIdx)

        binding.etCustomUrl.setText(prefs.getString(KEY_CUSTOM_URL, "https://") ?: "https://")

        val savedLang = prefs.getString(KEY_LANGUAGE, "auto") ?: "auto"
        when (savedLang) {
            "zh" -> binding.toggleLanguage.check(R.id.btnLangZh)
            "yue" -> binding.toggleLanguage.check(R.id.btnLangYue)
            "en" -> binding.toggleLanguage.check(R.id.btnLangEn)
            else -> binding.toggleLanguage.check(R.id.btnLangAuto)
        }
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_API_KEY, binding.etApiKey.text.toString().trim())
            .putString(KEY_PROVIDER, getSelectedProvider().name)
            .putString(KEY_CUSTOM_URL, binding.etCustomUrl.text.toString().trim())
            .putString(KEY_LANGUAGE, getSelectedLanguage() ?: "auto")
            .apply()
    }

    private fun getSelectedLanguage(): String? {
        return when (binding.toggleLanguage.checkedButtonId) {
            R.id.btnLangZh -> "zh"
            R.id.btnLangYue -> "yue"
            R.id.btnLangEn -> "en"
            else -> null
        }
    }

    // ---- 视频选择 ----

    private fun onVideoPicked(uri: Uri) {
        binding.cardVideoInfo.visibility = View.VISIBLE
        binding.tvVideoName.text = "正在加载视频..."
        binding.tvVideoSize.text = ""

        val fileName = getFileName(uri) ?: "video.mp4"
        val fileSize = getFileSize(uri)

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val cacheFile = File(cacheDir, "input_video.mp4")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(cacheFile).use { input.copyTo(it) }
                }
                app.videoLocalPath = cacheFile.absolutePath
            }
            binding.tvVideoName.text = fileName
            binding.tvVideoSize.text = formatFileSize(fileSize)
            binding.btnStartRecognize.isEnabled = true
        }
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = it.getString(idx)
            }
        }
        return name
    }

    private fun getFileSize(uri: Uri): Long {
        var size = 0L
        val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0) size = it.getLong(idx)
            }
        }
        return size
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        }
    }

    // ---- 语音识别 ----

    private fun startRecognition() {
        val videoPath = app.videoLocalPath
        if (videoPath == null) {
            showError("请先选择视频文件")
            return
        }

        val apiKey = binding.etApiKey.text.toString().trim()
        if (apiKey.isBlank()) {
            showError("请输入 API Key")
            return
        }

        val baseUrl = getApiBaseUrl()
        if (baseUrl.isBlank()) {
            showError("请输入 API 地址")
            return
        }

        saveSettings()

        binding.layoutProgress.visibility = View.VISIBLE
        binding.tvProgress.text = "正在提取音频..."
        binding.btnStartRecognize.isEnabled = false
        binding.btnSelectVideo.isEnabled = false
        binding.tvError.visibility = View.GONE

        lifecycleScope.launch {
            try {
                // 1) 提取音频
                val audioFile = File(cacheDir, "extracted_audio.m4a")
                binding.tvProgress.text = "正在提取音频..."

                val extracted = withContext(Dispatchers.IO) {
                    AudioExtractor.extractAudio(videoPath, audioFile)
                }
                if (!extracted) {
                    showError("音频提取失败，请检查视频文件是否包含音频")
                    resetButtons()
                    return@launch
                }

                val sizeMB = audioFile.length() / (1024.0 * 1024.0)
                if (sizeMB > 25) {
                    showError("音频文件 %.1f MB 超过 25MB 限制，请使用较短的视频".format(sizeMB))
                    audioFile.delete()
                    resetButtons()
                    return@launch
                }

                // 2) 调用 Whisper API
                val providerName = getSelectedProvider().displayName
                binding.tvProgress.text = "正在通过 $providerName 识别 (%.1f MB)...".format(sizeMB)

                val language = getSelectedLanguage()
                val modelName = getModelName()

                val result = WhisperService.recognize(
                    audioFile, apiKey, baseUrl, modelName, language
                )

                audioFile.delete()

                if (!result.success) {
                    showError(result.error)
                    resetButtons()
                    return@launch
                }

                if (result.subtitles.isEmpty()) {
                    showError("未识别到语音内容")
                    resetButtons()
                    return@launch
                }

                // 3) 跳转编辑页
                app.subtitles = result.subtitles.toMutableList()
                binding.layoutProgress.visibility = View.GONE

                Toast.makeText(this@MainActivity,
                    "识别完成，共 ${result.subtitles.size} 条字幕", Toast.LENGTH_SHORT).show()

                startActivity(Intent(this@MainActivity, EditorActivity::class.java))
                resetButtons()

            } catch (e: Exception) {
                showError("识别出错: ${e.message}")
                resetButtons()
            }
        }
    }

    private fun showError(msg: String) {
        binding.tvError.text = msg
        binding.tvError.visibility = View.VISIBLE
        binding.layoutProgress.visibility = View.GONE
    }

    private fun resetButtons() {
        binding.btnStartRecognize.isEnabled = true
        binding.btnSelectVideo.isEnabled = true
    }
}
