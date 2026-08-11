package com.example.ytmusics.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

object YouTubeApi {

    private const val TAG = "YouTubeApi"

    fun init(downloader: Downloader) {
        NewPipe.init(downloader)
    }

    suspend fun search(query: String): List<SongResult> = withContext(Dispatchers.IO) {
        val service = ServiceList.YouTube
        val searchExtractor = service.getSearchExtractor(query)
        searchExtractor.fetchPage()
        searchExtractor.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .map { item ->
                SongResult(
                    title = item.name,
                    uploader = item.uploaderName ?: "",
                    duration = item.duration,
                    thumbUrl = item.thumbnails.firstOrNull()?.url ?: "",
                    url = item.url
                )
            }
    }

    suspend fun resolveStreamInfo(url: String): StreamInfo = withContext(Dispatchers.IO) {
        try {
            StreamInfo.getInfo(ServiceList.YouTube, url)
        } catch (e: Exception) {
            Log.e(TAG, "resolveStreamInfo failed", e)
            throw e
        }
    }

    fun pickAudioStream(streamInfo: StreamInfo): AudioStream? {
        val streams = streamInfo.audioStreams
        val progressive = streams
            .filter { !it.id.contains("manifest") }
            .maxByOrNull { it.bitrate }
        val dash = streams
            .filter { it.id.contains("manifest") }
            .maxByOrNull { it.bitrate }
        return progressive ?: dash
    }
}
