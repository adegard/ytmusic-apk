package com.example.ytmusics.net

import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.downloader.Downloader
import java.util.concurrent.TimeUnit

object DownloaderProvider {

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    @Volatile
    private var client: OkHttpClient? = null

    @Volatile
    private var downloader: Downloader? = null

    fun okHttpClient(): OkHttpClient = client ?: synchronized(this) {
        client ?: OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder().header("User-Agent", USER_AGENT).build()
                )
            }
            .build()
            .also { client = it }
    }

    fun downloader(): Downloader = downloader ?: synchronized(this) {
        downloader ?: OkHttpDownloaderImpl(okHttpClient()).also { downloader = it }
    }
}
