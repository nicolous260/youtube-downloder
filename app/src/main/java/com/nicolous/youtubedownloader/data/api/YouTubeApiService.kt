package com.nicolous.youtubedownloader.data.api

import com.nicolous.youtubedownloader.data.model.SearchRequest
import com.nicolous.youtubedownloader.data.model.SearchResponse
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

interface YouTubeApiService {

    @POST("/api/search")
    suspend fun searchVideos(@Body request: SearchRequest): Response<SearchResponse>

    companion object {
        // Default base URL for Android Emulator pointing to host machine running Flask
        // (Use "http://10.0.2.16:5000/" or local server IP)
        private const val BASE_URL = "https://10.0.2.16:5000/"

        fun create(): YouTubeApiService {
            // For development with self-signed HTTPS certificate, we can use an unsafe OkHttpClient if needed,
            // or standard Retrofit client.
            val client = okhttp3.OkHttpClient.Builder()
                .hostnameVerifier { _, _ -> true }
                .build()

            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(YouTubeApiService::class.java)
        }
    }
}
