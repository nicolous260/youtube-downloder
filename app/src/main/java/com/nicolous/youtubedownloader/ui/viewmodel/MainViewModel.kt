package com.nicolous.youtubedownloader.ui.viewmodel

import android.app.Application
import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nicolous.youtubedownloader.data.api.YouTubeApiService
import com.nicolous.youtubedownloader.data.model.SearchRequest
import com.nicolous.youtubedownloader.data.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("ytdl_prefs", Context.MODE_PRIVATE)

    var serverUrl by mutableStateOf(getSavedServerUrl())
        private set

    private var apiService = YouTubeApiService.create(serverUrl)

    var searchQuery by mutableStateOf("")
    var videoList by mutableStateOf<List<VideoItem>>(emptyList())
    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)

    var selectedVideoForDownload by mutableStateOf<VideoItem?>(null)
    var isDownloading by mutableStateOf(false)
    var downloadProgress by mutableStateOf(0f)
    var downloadStatusText by mutableStateOf("")

    var showServerSettingsDialog by mutableStateOf(false)

    private fun getSavedServerUrl(): String {
        return prefs.getString("server_url", YouTubeApiService.DEFAULT_EMULATOR_URL)
            ?: YouTubeApiService.DEFAULT_EMULATOR_URL
    }

    fun updateServerUrl(newUrl: String) {
        val sanitized = YouTubeApiService.sanitizeUrl(newUrl)
        serverUrl = sanitized
        prefs.edit().putString("server_url", sanitized).apply()
        apiService = YouTubeApiService.create(sanitized)
    }

    fun search() {
        if (searchQuery.isBlank()) return
        isLoading = true
        errorMessage = null
        viewModelScope.launch {
            try {
                val response = apiService.searchVideos(SearchRequest(query = searchQuery))
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    if (body.success) {
                        videoList = body.results ?: emptyList()
                    } else {
                        errorMessage = body.error ?: "Search failed"
                    }
                } else {
                    errorMessage = "Server error: ${response.code()}"
                }
            } catch (e: Exception) {
                // Try automatic fallback host if default failed
                val fallbackUrl = getFallbackUrl(serverUrl)
                if (fallbackUrl != null) {
                    try {
                        val fallbackService = YouTubeApiService.create(fallbackUrl)
                        val response = fallbackService.searchVideos(SearchRequest(query = searchQuery))
                        if (response.isSuccessful && response.body() != null) {
                            val body = response.body()!!
                            if (body.success) {
                                videoList = body.results ?: emptyList()
                                updateServerUrl(fallbackUrl)
                                return@launch
                            }
                        }
                    } catch (_: Exception) {
                        // Fallback also failed
                    }
                }

                errorMessage = buildConnectionErrorMessage(e.localizedMessage ?: "Unknown network error")
            } finally {
                isLoading = false
            }
        }
    }

    private fun getFallbackUrl(currentUrl: String): String? {
        return when {
            currentUrl.contains("127.0.0.1") -> currentUrl.replace("127.0.0.1", "10.0.2.2")
            currentUrl.contains("10.0.2.2") -> currentUrl.replace("10.0.2.2", "127.0.0.1")
            else -> null
        }
    }

    private fun buildConnectionErrorMessage(details: String): String {
        val host = serverUrl.removeSuffix("/")
        return "Failed to connect to $host\n\n" +
                "Troubleshooting steps:\n" +
                "1. Ensure Python backend is running: `python App.py`\n" +
                "2. If using Android Emulator, server should be at `https://10.0.2.2:5000`\n" +
                "3. If using `127.0.0.1`, run `adb reverse tcp:5000 tcp:5000` in terminal\n" +
                "4. Tap the Settings icon above to configure your server IP address.\n\n" +
                "Details: $details"
    }

    fun startDownload(video: VideoItem, type: String, quality: String) {
        val context = getApplication<Application>()
        isDownloading = true
        downloadProgress = 0f
        downloadStatusText = "Connecting to server..."

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val baseUrlClean = serverUrl.removeSuffix("/")
                val downloadUrl = "$baseUrlClean/api/download"
                val client = getUnsafeOkHttpClient()

                val jsonPayload = "{\"id\":\"${video.id}\",\"type\":\"$type\",\"quality\":\"$quality\"}"
                val body = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = okhttp3.Request.Builder()
                    .url(downloadUrl)
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val source = response.body?.source()
                    if (source != null) {
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: continue
                            if (line.startsWith("{")) {
                                try {
                                    val json = JSONObject(line)
                                    val status = json.optString("status")
                                    if (status == "downloading") {
                                        val percent = json.optDouble("percent", 0.0).toFloat()
                                        val speed = json.optString("speed", "")
                                        withContext(Dispatchers.Main) {
                                            downloadProgress = percent / 100f
                                            downloadStatusText = "Downloading... ${percent.toInt()}% $speed"
                                        }
                                    } else if (status == "processing") {
                                        withContext(Dispatchers.Main) {
                                            downloadStatusText = "Processing & merging with ffmpeg..."
                                        }
                                    } else if (status == "finished") {
                                        val serveUrl = baseUrlClean + json.optString("url")
                                        val fileName = json.optString("filename", "${video.title}.mp4")

                                        downloadFileFromUrl(serveUrl, fileName)
                                        break
                                    } else if (status == "error") {
                                        val err = json.optString("error", "Unknown error")
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "Error: $err", Toast.LENGTH_LONG).show()
                                            isDownloading = false
                                            selectedVideoForDownload = null
                                        }
                                        break
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Download failed: Server response ${response.code}", Toast.LENGTH_LONG).show()
                        isDownloading = false
                        selectedVideoForDownload = null
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    isDownloading = false
                    selectedVideoForDownload = null
                }
            }
        }
    }

    private suspend fun downloadFileFromUrl(fileUrl: String, fileName: String) {
        val context = getApplication<Application>()
        try {
            val client = getUnsafeOkHttpClient()
            val request = okhttp3.Request.Builder().url(fileUrl).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val inputStream = response.body?.byteStream()
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val file = File(downloadsDir, fileName)

                inputStream?.use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                withContext(Dispatchers.Main) {
                    isDownloading = false
                    selectedVideoForDownload = null
                    Toast.makeText(context, "Saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
                }
            } else {
                withContext(Dispatchers.Main) {
                    isDownloading = false
                    selectedVideoForDownload = null
                    Toast.makeText(context, "Failed to fetch file from server", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                isDownloading = false
                selectedVideoForDownload = null
                Toast.makeText(context, "Download failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun getUnsafeOkHttpClient(): OkHttpClient {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, SecureRandom())
        val sslSocketFactory = sslContext.socketFactory

        return OkHttpClient.Builder()
            .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }
}
