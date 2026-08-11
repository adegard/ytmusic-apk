package com.example.ytmusics.net

import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.downloader.Downloader
import java.util.concurrent.TimeUnit

object DownloaderProvider {

    @Volatile
    private var client: OkHttpClient? = null

    @Volatile
    private var downloader: Downloader? = null

    fun okHttpClient(): OkHttpClient = client ?: synchronized(this) {
        client ?: OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
            .also { client = it }
    }

    fun downloader(): Downloader = downloader ?: synchronized(this) {
        downloader ?: OkHttpDownloaderImpl(okHttpClient()).also { downloader = it }
    }
}
