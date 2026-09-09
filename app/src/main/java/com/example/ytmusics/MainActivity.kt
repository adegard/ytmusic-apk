package com.example.ytmusics

import android.Manifest
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.ytmusics.data.ChannelPrefs
import com.example.ytmusics.data.ChannelResult
import com.example.ytmusics.data.ChannelSubscription
import com.example.ytmusics.data.SongResult
import com.example.ytmusics.data.YouTubeApi
import com.example.ytmusics.databinding.ActivityMainBinding
import com.example.ytmusics.net.DownloaderProvider
import com.example.ytmusics.ui.ChannelAdapter
import com.example.ytmusics.ui.SongAdapter
import com.example.ytmusics.ui.SubscriptionAdapter
import com.example.ytmusics.ui.SubscriptionEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.schabi.newpipe.extractor.stream.StreamInfo

class MainActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "YTMusic"
        const val PERMISSION_REQUEST_NOTIFICATIONS = 100
    }

    private enum class SearchMode { SONGS, CHANNELS }

    private lateinit var binding: ActivityMainBinding
    private lateinit var songAdapter: SongAdapter
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var subscriptionAdapter: SubscriptionAdapter
    private lateinit var channelVideosAdapter: SongAdapter

    private var mediaController: MediaController? = null
    private var pendingSong: SongResult? = null
    private var currentSong: SongResult? = null
    private var searchMode = SearchMode.SONGS
    private var videoMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        DebugLog.init(this)
        DebugLog.log("onCreate: auto_search=${intent.getStringExtra("auto_search")}")

        applyWindowInsets()

        requestNotificationPermission()

        YouTubeApi.init(DownloaderProvider.downloader())

        songAdapter = SongAdapter { song -> playSong(song) }
        channelVideosAdapter = SongAdapter { song -> playSong(song) }
        channelAdapter = ChannelAdapter(
            isSubscribed = { channel -> ChannelPrefs.isSubscribed(this, channel.url) },
            onSubscribe = { channel -> subscribeChannel(channel) },
            onUnsubscribe = { channel -> unsubscribeChannel(channel) }
        )
        subscriptionAdapter = SubscriptionAdapter { entry -> openChannelVideos(entry.sub) }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = songAdapter
        binding.subsRecycler.layoutManager = LinearLayoutManager(this)
        binding.subsRecycler.adapter = subscriptionAdapter
        binding.channelVideosRecycler.layoutManager = LinearLayoutManager(this)
        binding.channelVideosRecycler.adapter = channelVideosAdapter

        binding.searchButton.setOnClickListener { doSearch() }
        binding.searchInput.setOnEditorActionListener { _, _, _ ->
            doSearch()
            true
        }

        binding.chipSongs.setOnClickListener {
            setSearchMode(SearchMode.SONGS)
            doSearch()
        }
        binding.chipChannels.setOnClickListener {
            setSearchMode(SearchMode.CHANNELS)
            doSearch()
        }

        binding.videoToggle.setOnClickListener { toggleVideoMode() }
        binding.backButton.setOnClickListener { showSubsListView() }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_search -> {
                    binding.searchTab.isVisible = true
                    binding.subscriptionsTab.isVisible = false
                    true
                }
                R.id.nav_subscriptions -> {
                    binding.searchTab.isVisible = false
                    binding.subscriptionsTab.isVisible = true
                    showSubsListView()
                    refreshSubscriptions()
                    true
                }
                else -> false
            }
        }

        onBackPressedDispatcher.addCallback(this) {
            if (binding.subscriptionsTab.isVisible && binding.channelVideosView.isVisible) {
                showSubsListView()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        connectToPlaybackService()

        if (isDebuggable() && intent.getStringExtra("auto_search") != null) {
            binding.searchInput.setText(intent.getStringExtra("auto_search"))
            doSearch()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                PERMISSION_REQUEST_NOTIFICATIONS
            )
        }
    }

    private fun connectToPlaybackService() {
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({
            val controller = controllerFuture.get()
            mediaController = controller
            binding.playerView.player = controller
            binding.playerView.setShowNextButton(false)
            binding.playerView.setShowPreviousButton(false)

            controller.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (mediaItem != null && mediaItem.mediaMetadata.title != null) {
                        binding.nowPlaying.text =
                            mediaItem.mediaMetadata.title?.toString() ?: getString(R.string.app_name)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    DebugLog.logException(
                        "PLAYBACK ERROR code=${error.errorCodeName} msg=${error.message}",
                        error
                    )
                    Toast.makeText(
                        this@MainActivity,
                        "Playback error: ${error.errorCodeName}\n${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    binding.nowPlaying.text = "Playback error: ${error.errorCodeName}"
                }
            })

            pendingSong?.let {
                pendingSong = null
                playNow(it)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun isDebuggable(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun applyWindowInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun setSearchMode(mode: SearchMode) {
        searchMode = mode
        binding.searchInput.hint = getString(
            if (mode == SearchMode.SONGS) R.string.search_hint else R.string.search_hint_channels
        )
        binding.emptyText.setText(
            if (mode == SearchMode.SONGS) R.string.initial_hint else R.string.initial_hint_channels
        )
        binding.recycler.adapter = if (mode == SearchMode.SONGS) songAdapter else channelAdapter
    }

    private fun doSearch() {
        if (searchMode == SearchMode.CHANNELS) {
            doChannelSearch()
        } else {
            doSongSearch()
        }
    }

    private fun doSongSearch() {
        val query = binding.searchInput.text.toString().trim()
        if (query.isEmpty()) return

        DebugLog.log("Song search requested: '$query'")

        hideKeyboard()

        binding.progress.isVisible = true
        binding.emptyText.isVisible = true
        binding.emptyText.setText(getString(R.string.searching, query))

        lifecycleScope.launch {
            try {
                val results = withTimeout(60_000) { YouTubeApi.search(query) }
                DebugLog.log("Search returned ${results.size} results for '$query'")
                songAdapter.submitList(results)
                if (results.isEmpty()) {
                    binding.emptyText.setText(R.string.no_results)
                } else {
                    binding.emptyText.setText(getString(R.string.found_count, results.size))
                }
            } catch (e: TimeoutCancellationException) {
                DebugLog.logException("Search TIMEOUT for '$query'", e)
                binding.emptyText.setText(R.string.search_timeout)
            } catch (e: Exception) {
                DebugLog.logException("Search FAILED for '$query'", e)
                binding.emptyText.text = "Search failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                binding.progress.isVisible = false
            }
        }
    }

    private fun doChannelSearch() {
        val query = binding.searchInput.text.toString().trim()
        if (query.isEmpty()) return

        DebugLog.log("Channel search requested: '$query'")

        hideKeyboard()

        binding.progress.isVisible = true
        binding.emptyText.isVisible = true
        binding.emptyText.setText(getString(R.string.searching, query))

        lifecycleScope.launch {
            try {
                val results = withTimeout(60_000) { YouTubeApi.searchChannels(query) }
                DebugLog.log("Channel search returned ${results.size} results for '$query'")
                channelAdapter.submitList(results)
                if (results.isEmpty()) {
                    binding.emptyText.setText(R.string.no_results)
                } else {
                    binding.emptyText.setText(getString(R.string.found_channels_count, results.size))
                }
            } catch (e: TimeoutCancellationException) {
                DebugLog.logException("Channel search TIMEOUT for '$query'", e)
                binding.emptyText.setText(R.string.search_timeout)
            } catch (e: Exception) {
                DebugLog.logException("Channel search FAILED for '$query'", e)
                binding.emptyText.text = "Channel search failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                binding.progress.isVisible = false
            }
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }

    private fun subscribeChannel(channel: ChannelResult) {
        val channelId = channel.url.substringAfterLast("/").ifEmpty { channel.url }
        ChannelPrefs.subscribe(
            this,
            ChannelSubscription(channelId, channel.name, channel.thumbUrl, channel.url)
        )
        Toast.makeText(this, getString(R.string.subscribed, channel.name), Toast.LENGTH_SHORT).show()
        channelAdapter.notifyDataSetChanged()
    }

    private fun unsubscribeChannel(channel: ChannelResult) {
        ChannelPrefs.unsubscribe(this, channel.url)
        Toast.makeText(this, getString(R.string.unsubscribed, channel.name), Toast.LENGTH_SHORT).show()
        channelAdapter.notifyDataSetChanged()
    }

    private fun refreshSubscriptions() {
        val subs = ChannelPrefs.getSubscriptions(this)
        binding.subsEmptyText.isVisible = subs.isEmpty()
        binding.subsRecycler.isVisible = subs.isNotEmpty()

        lifecycleScope.launch {
            if (subs.isEmpty()) {
                subscriptionAdapter.submitList(emptyList())
                return@launch
            }
            try {
                val entries = withTimeout(120_000) {
                    coroutineScope {
                        subs.map { sub ->
                            async(Dispatchers.IO) {
                                val latest = try {
                                    YouTubeApi.getChannelVideos(sub.url).firstOrNull()
                                } catch (e: Exception) {
                                    null
                                }
                                SubscriptionEntry(sub, latest)
                            }
                        }.awaitAll()
                    }
                }
                if (isFinishing) return@launch
                subscriptionAdapter.submitList(entries)
            } catch (e: Exception) {
                DebugLog.logException("Loading subscriptions failed", e)
                subscriptionAdapter.submitList(entriesOfFallback(subs))
            }
        }
    }

    private fun entriesOfFallback(subs: List<ChannelSubscription>): List<SubscriptionEntry> =
        subs.map { SubscriptionEntry(it, null) }

    private fun openChannelVideos(sub: ChannelSubscription) {
        binding.channelTitle.text = sub.name
        binding.channelVideosView.isVisible = true
        binding.subsListView.isVisible = false
        binding.channelProgress.isVisible = true
        channelVideosAdapter.submitList(emptyList())

        lifecycleScope.launch {
            try {
                val videos = withTimeout(90_000) { YouTubeApi.getChannelVideos(sub.url) }
                if (isFinishing) return@launch
                binding.channelProgress.isVisible = false
                channelVideosAdapter.submitList(videos)
            } catch (e: TimeoutCancellationException) {
                DebugLog.logException("Channel videos TIMEOUT for '${sub.name}'", e)
                binding.channelProgress.isVisible = false
                Toast.makeText(this@MainActivity, R.string.search_timeout, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                DebugLog.logException("Channel videos FAILED for '${sub.name}'", e)
                binding.channelProgress.isVisible = false
                Toast.makeText(
                    this@MainActivity,
                    "Failed to load videos: ${e.message ?: e.javaClass.simpleName}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showSubsListView() {
        binding.channelVideosView.isVisible = false
        binding.subsListView.isVisible = true
    }

    private fun toggleVideoMode() {
        videoMode = !videoMode
        updateVideoModeUi()
        currentSong?.let { playNow(it) }
    }

    private fun updateVideoModeUi() {
        val params = binding.playerView.layoutParams
        if (videoMode) {
            val widthPx = resources.displayMetrics.widthPixels
            params.height = (widthPx * 9f / 16f).toInt()
            binding.videoToggle.setImageResource(R.drawable.ic_audio)
            binding.videoToggle.contentDescription = getString(R.string.toggle_audio)
        } else {
            params.height = dp(144)
            binding.videoToggle.setImageResource(R.drawable.ic_video)
            binding.videoToggle.contentDescription = getString(R.string.toggle_video)
        }
        binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        binding.playerView.layoutParams = params
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun playSong(song: SongResult) {
        DebugLog.log("Play requested: ${song.title} :: ${song.url}")
        if (mediaController != null) {
            playNow(song)
        } else {
            pendingSong = song
        }
    }

    private fun playNow(song: SongResult) {
        currentSong = song
        lifecycleScope.launch {
            try {
                binding.nowPlaying.text = getString(R.string.loading_stream)
                val info = withTimeout(60_000) { YouTubeApi.resolveStreamInfo(song.url) }
                if (videoMode) {
                    playVideo(info, song)
                } else {
                    playAudio(info)
                }
            } catch (e: TimeoutCancellationException) {
                DebugLog.logException("Stream resolve TIMEOUT for '${song.title}'", e)
                binding.nowPlaying.text = getString(R.string.stream_timeout)
            } catch (e: Exception) {
                DebugLog.logException("Play FAILED for '${song.title}'", e)
                Toast.makeText(
                    this@MainActivity,
                    "Failed to play: ${e.message ?: e.javaClass.simpleName}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun playAudio(info: StreamInfo) {
        val stream = YouTubeApi.pickAudioStream(info)
        val streamUrl = stream?.url ?: run {
            DebugLog.log("No playable audio stream for '${info.name}'")
            Toast.makeText(this@MainActivity, "No playable audio stream found", Toast.LENGTH_SHORT).show()
            binding.nowPlaying.text = info.name
            return
        }
        DebugLog.log("Playing '${info.name}' stream: $streamUrl")
        binding.nowPlaying.text = info.name
        val mediaItem = PlaybackService.buildMediaItem(streamUrl, info.name, info.uploaderName ?: "")
        mediaController?.setMediaItem(mediaItem)
        mediaController?.prepare()
        mediaController?.playWhenReady = true
    }

    private fun playVideo(info: StreamInfo, song: SongResult) {
        val muxed = YouTubeApi.pickVideoStream(info)
        val artist = info.uploaderName ?: song.uploader

        if (muxed != null) {
            DebugLog.log("Playing muxed video '${info.name}' stream: ${muxed.url}")
            binding.nowPlaying.text = info.name
            val mediaItem = PlaybackService.buildMediaItem(muxed.url.orEmpty(), info.name, artist)
            mediaController?.setMediaItem(mediaItem)
            mediaController?.prepare()
            mediaController?.playWhenReady = true
            return
        }

        val videoOnly = YouTubeApi.pickVideoOnlyStream(info)
        val audio = YouTubeApi.pickAudioStream(info)
        if (videoOnly != null && audio != null) {
            DebugLog.log("Playing merged video+audio '${info.name}'")
            binding.nowPlaying.text = info.name
            val ok = PlaybackService.playMergedVideo(videoOnly.url.orEmpty(), audio.url.orEmpty(), info.name, artist)
            if (!ok) {
                Toast.makeText(this@MainActivity, "Playback service not ready", Toast.LENGTH_SHORT).show()
            }
        } else {
            DebugLog.log("No video stream for '${info.name}', falling back to audio")
            playAudio(info)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.playerView.player = null
        mediaController?.release()
        mediaController = null
    }
}