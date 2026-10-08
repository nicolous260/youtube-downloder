package com.nicolous.youtubedownloader.data.model

import com.google.gson.annotations.SerializedName

data class SearchRequest(
    @SerializedName("query") val query: String,
    @SerializedName("page") val page: Int = 1
)

data class SearchResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("results") val results: List<VideoItem>?,
    @SerializedName("page") val page: Int,
    @SerializedName("has_more") val hasMore: Boolean,
    @SerializedName("total") val total: Int,
    @SerializedName("error") val error: String?
)

data class VideoItem(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("uploader") val uploader: String,
    @SerializedName("thumbnail") val thumbnail: String,
    @SerializedName("duration") val duration: String,
    @SerializedName("view_count") val viewCount: Long?
)

data class DownloadRequest(
    @SerializedName("id") val id: String,
    @SerializedName("type") val type: String, // "video" or "audio"
    @SerializedName("quality") val quality: String // "360", "720", "1080"
)
