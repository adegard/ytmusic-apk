package com.example.ytmusics

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.ytmusics.net.DownloaderProvider

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "playback",
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val dataSourceFactory = OkHttpDataSource.Factory(DownloaderProvider.okHttpClient())
            .setDefaultRequestProperties(mapOf("Referer" to "https://www.youtube.com/"))

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory)
            )
            .build()

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()

        Companion.activeService = this
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        if (Companion.activeService === this) {
            Companion.activeService = null
        }
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var activeService: PlaybackService? = null
            private set

        fun buildMediaItem(streamUrl: String, title: String, artist: String): MediaItem =
            MediaItem.Builder()
                .setUri(streamUrl)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artist)
                        .build()
                )
                .build()

        fun playMergedVideo(videoUrl: String, audioUrl: String, title: String, artist: String): Boolean {
            val player = activeService?.mediaSession?.player as? ExoPlayer ?: return false
            val dataSourceFactory = OkHttpDataSource.Factory(DownloaderProvider.okHttpClient())
                .setDefaultRequestProperties(mapOf("Referer" to "https://www.youtube.com/"))
            val videoItem = MediaItem.Builder()
                .setUri(videoUrl)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artist)
                        .build()
                )
                .build()
            val video = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(videoItem)
            val audio = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(audioUrl))
            val merged = MergingMediaSource(video, audio)
            player.setMediaSource(merged)
            player.prepare()
            player.playWhenReady = true
            return true
        }
    }
}