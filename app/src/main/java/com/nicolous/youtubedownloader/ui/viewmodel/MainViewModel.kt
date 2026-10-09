package com.nicolous.youtubedownloader.ui.viewmodel

import android.app.Application
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
    private val apiService = YouTubeApiService.create()

    var searchQuery by mutableStateOf("")
    var videoList by mutableStateOf<List<VideoItem>>(emptyList())
    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)

    var selectedVideoForDownload by mutableStateOf<VideoItem?>(null)
    var isDownloading by mutableStateOf(false)
    var downloadProgress by mutableStateOf(0f)
    var downloadStatusText by mutableStateOf("")

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
                errorMessage = "Network error: ${e.localizedMessage ?: "Unknown error"}"
            } finally {
                isLoading = false
            }
        }
    }

    fun startDownload(video: VideoItem, type: String, quality: String) {
        val context = getApplication<Application>()
        isDownloading = true
        downloadProgress = 0f
        downloadStatusText = "Connecting to server..."

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val downloadUrl = "https://127.0.0.1:5000/api/download"
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
                                        val serveUrl = "https://127.0.0.1:5000" + json.optString("url")
                                        val fileName = json.optString("filename", "${video.title}.mp4")

                                        downloadFileFromUrl(serveUrl, fileName)
                                        break
                                    } else if (status == "error") {
                                        val err = json.optString("error", "Unknown error")
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "Error: $err", Toast.LENGTH_LONG).show()
                                            isDownloading = false
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
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    isDownloading = false
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
                    Toast.makeText(context, "Saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
                }
            } else {
                withContext(Dispatchers.Main) {
                    isDownloading = false
                    Toast.makeText(context, "Failed to fetch file from server", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                isDownloading = false
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
            .build()
    }
}
