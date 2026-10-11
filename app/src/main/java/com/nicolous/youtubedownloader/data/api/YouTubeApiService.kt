package com.nicolous.youtubedownloader.data.api

import com.nicolous.youtubedownloader.data.model.SearchRequest
import com.nicolous.youtubedownloader.data.model.SearchResponse
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

interface YouTubeApiService {

    @POST("/api/search")
    suspend fun searchVideos(@Body request: SearchRequest): Response<SearchResponse>

    companion object {
        const val DEFAULT_EMULATOR_URL = "https://10.0.2.2:5000/"
        const val DEFAULT_ADB_URL = "https://127.0.0.1:5000/"
        const val DEFAULT_WIFI_URL = "https://192.168.0.100:5000/"

        fun sanitizeUrl(url: String): String {
            var formatted = url.trim()
            if (!formatted.startsWith("http://") && !formatted.startsWith("https://")) {
                formatted = "https://$formatted"
            }
            if (!formatted.endsWith("/")) {
                formatted = "$formatted/"
            }
            return formatted
        }

        fun create(baseUrl: String = DEFAULT_EMULATOR_URL): YouTubeApiService {
            val targetUrl = sanitizeUrl(baseUrl)
            return try {
                val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                })

                val sslContext = SSLContext.getInstance("SSL")
                sslContext.init(null, trustAllCerts, SecureRandom())
                val sslSocketFactory = sslContext.socketFactory

                val client = OkHttpClient.Builder()
                    .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
                    .hostnameVerifier { _, _ -> true }
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .build()

                Retrofit.Builder()
                    .baseUrl(targetUrl)
                    .client(client)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(YouTubeApiService::class.java)
            } catch (e: Exception) {
                Retrofit.Builder()
                    .baseUrl(targetUrl)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(YouTubeApiService::class.java)
            }
        }
    }
}
