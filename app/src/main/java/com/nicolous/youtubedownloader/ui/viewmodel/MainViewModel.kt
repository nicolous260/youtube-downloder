package com.nicolous.youtubedownloader.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nicolous.youtubedownloader.data.api.YouTubeApiService
import com.nicolous.youtubedownloader.data.model.SearchRequest
import com.nicolous.youtubedownloader.data.model.VideoItem
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {
    private val apiService = YouTubeApiService.create()

    var searchQuery by mutableStateOf("")
    var videoList by mutableStateOf<List<VideoItem>>(emptyList())
    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)

    var selectedVideoForDownload by mutableStateOf<VideoItem?>(null)

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
}
