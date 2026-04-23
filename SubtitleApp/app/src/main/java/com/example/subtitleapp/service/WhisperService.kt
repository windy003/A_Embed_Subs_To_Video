package com.example.subtitleapp.service

import com.example.subtitleapp.model.SubtitleEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 调用 Whisper 兼容 API 进行语音识别（支持 OpenAI / Groq / 自定义）。
 */
object WhisperService {

    /** 预设的 API 服务商 */
    enum class Provider(
        val displayName: String,
        val baseUrl: String,
        val modelName: String
    ) {
        OPENAI("OpenAI", "https://api.openai.com/v1", "whisper-1"),
        GROQ("Groq (免费)", "https://api.groq.com/openai/v1", "whisper-large-v3"),
        CUSTOM("自定义", "", "whisper-1");
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    data class RecognizeResult(
        val success: Boolean,
        val subtitles: List<SubtitleEntry> = emptyList(),
        val error: String = ""
    )

    /**
     * @param audioFile   音频文件（支持 mp3, mp4, m4a, wav 等，最大 25MB）
     * @param apiKey      API Key
     * @param baseUrl     API 基础地址，如 https://api.openai.com/v1
     * @param modelName   模型名称，如 whisper-1 或 whisper-large-v3
     * @param language    语言代码（可选，如 "zh", "yue", "en"），为空则自动检测
     */
    suspend fun recognize(
        audioFile: File,
        apiKey: String,
        baseUrl: String,
        modelName: String,
        language: String? = null
    ): RecognizeResult = withContext(Dispatchers.IO) {

        if (!audioFile.exists()) {
            return@withContext RecognizeResult(false, error = "音频文件不存在")
        }

        if (audioFile.length() > 25 * 1024 * 1024) {
            return@withContext RecognizeResult(false, error = "文件超过 25MB 限制，请使用较短的视频")
        }

        try {
            val bodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    audioFile.name,
                    audioFile.asRequestBody("audio/mpeg".toMediaType())
                )
                .addFormDataPart("model", modelName)
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart("timestamp_granularities[]", "segment")

            if (!language.isNullOrBlank()) {
                bodyBuilder.addFormDataPart("language", language)
            }

            val url = "${baseUrl.trimEnd('/')}/audio/transcriptions"

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .post(bodyBuilder.build())
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorMsg = try {
                    val json = JSONObject(responseBody)
                    json.optJSONObject("error")?.optString("message") ?: responseBody
                } catch (_: Exception) {
                    responseBody
                }
                return@withContext RecognizeResult(false,
                    error = "API 错误 (${response.code}): $errorMsg")
            }

            // 解析返回的 JSON
            val json = JSONObject(responseBody)
            val segments = json.optJSONArray("segments")

            if (segments == null || segments.length() == 0) {
                val text = json.optString("text", "")
                if (text.isBlank()) {
                    return@withContext RecognizeResult(false, error = "未识别到语音内容")
                }
                return@withContext RecognizeResult(true, listOf(
                    SubtitleEntry(1, 0, 5000, text)
                ))
            }

            val subtitles = mutableListOf<SubtitleEntry>()
            for (i in 0 until segments.length()) {
                val seg = segments.getJSONObject(i)
                val startSec = seg.getDouble("start")
                val endSec = seg.getDouble("end")
                val text = seg.getString("text").trim()
                if (text.isNotBlank()) {
                    subtitles.add(SubtitleEntry(
                        index = subtitles.size + 1,
                        startMs = (startSec * 1000).toLong(),
                        endMs = (endSec * 1000).toLong(),
                        text = text
                    ))
                }
            }

            RecognizeResult(true, subtitles)

        } catch (e: Exception) {
            e.printStackTrace()
            RecognizeResult(false, error = "网络错误: ${e.message}")
        }
    }
}
