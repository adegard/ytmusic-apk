package com.example.ytmusics.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.VideoStream

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

    suspend fun searchChannels(query: String): List<ChannelResult> = withContext(Dispatchers.IO) {
        val service = ServiceList.YouTube
        val searchExtractor = service.getSearchExtractor(
            query,
            listOf(YoutubeSearchQueryHandlerFactory.CHANNELS),
            ""
        )
        searchExtractor.fetchPage()
        searchExtractor.initialPage.items
            .filterIsInstance<ChannelInfoItem>()
            .map { item ->
                ChannelResult(
                    name = item.name,
                    url = item.url,
                    thumbUrl = item.thumbnails.firstOrNull()?.url ?: "",
                    subscriberCount = item.subscriberCount,
                    verified = item.isVerified
                )
            }
    }

    suspend fun getChannelVideos(channelUrl: String): List<SongResult> = withContext(Dispatchers.IO) {
        val service = ServiceList.YouTube
        val channelExtractor = service.getChannelExtractor(channelUrl)
        channelExtractor.fetchPage()
        val channelId = channelExtractor.id
        val tabExtractor = service.getChannelTabExtractorFromId(channelId, ChannelTabs.VIDEOS)
        tabExtractor.fetchPage()
        tabExtractor.initialPage.items
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
        val streams = streamInfo.audioStreams.filterNot { it.url?.contains(".m3u8") == true }
        val mp4 = streams
            .filter { it.getFormat()?.mimeType == "audio/mp4" }
            .maxByOrNull { it.bitrate }
        return mp4 ?: streams.maxByOrNull { it.bitrate }
    }

    fun pickVideoStream(streamInfo: StreamInfo): VideoStream? {
        val muxed = streamInfo.videoStreams
            .filterNot { it.url?.contains(".m3u8") == true }
        return muxed.maxByOrNull { it.height * 10000L + it.getBitrate() }
    }

    fun pickVideoOnlyStream(streamInfo: StreamInfo): VideoStream? {
        val videoOnly = streamInfo.videoOnlyStreams
            .filterNot { it.url?.contains(".m3u8") == true }
        return videoOnly.maxByOrNull { it.height * 10000L + it.getBitrate() }
    }
}