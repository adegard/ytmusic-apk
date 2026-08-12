package com.example.ytmusics

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.ytmusics.data.SongResult
import com.example.ytmusics.data.YouTubeApi
import com.example.ytmusics.databinding.ActivityMainBinding
import com.example.ytmusics.net.DownloaderProvider
import com.example.ytmusics.ui.SongAdapter
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class MainActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "YTMusic"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: SongAdapter
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                DebugLog.logException("UNCAUGHT on ${thread.name}", throwable)
            } catch (_: Throwable) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        DebugLog.init(this)
        DebugLog.log("onCreate: auto_search=${intent.getStringExtra("auto_search")}")

        applyWindowInsets()

        YouTubeApi.init(DownloaderProvider.downloader())

        adapter = SongAdapter { song -> playSong(song) }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.searchButton.setOnClickListener { doSearch() }
        binding.searchInput.setOnEditorActionListener { _, _, _ ->
            doSearch()
            true
        }

        setupPlayer()

        if (isDebuggable() && intent.getStringExtra("auto_search") != null) {
            binding.searchInput.setText(intent.getStringExtra("auto_search"))
            doSearch()
        }
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

    private fun doSearch() {
        val query = binding.searchInput.text.toString().trim()
        if (query.isEmpty()) return

        DebugLog.log("Search requested: '$query'")

        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)

        binding.progress.isVisible = true
        binding.emptyText.isVisible = true
        binding.emptyText.setText(getString(R.string.searching, query))

        lifecycleScope.launch {
            try {
                val results = withTimeout(60_000) { YouTubeApi.search(query) }
                DebugLog.log("Search returned ${results.size} results for '$query'")
                adapter.submitList(results)
                if (results.isEmpty()) {
                    binding.emptyText.setText(R.string.no_results)
                } else {
                    binding.emptyText.setText(getString(R.string.found_count, results.size))
                }
                binding.recycler.post {
                    DebugLog.log(
                        "layout: emptyText h=${binding.emptyText.height} " +
                            "recycler h=${binding.recycler.height} w=${binding.recycler.width} " +
                            "children=${binding.recycler.childCount} items=${adapter.itemCount} " +
                            "player h=${binding.playerView.height}"
                    )
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

    private fun playSong(song: SongResult) {
        DebugLog.log("Play requested: ${song.title} :: ${song.url}")
        lifecycleScope.launch {
            try {
                binding.nowPlaying.text = getString(R.string.loading_stream)
                val info = withTimeout(60_000) { YouTubeApi.resolveStreamInfo(song.url) }
                val stream = YouTubeApi.pickAudioStream(info)
                if (stream == null) {
                    DebugLog.log("No audio stream for '${song.title}'")
                    Toast.makeText(
                        this@MainActivity,
                        "No playable audio stream found",
                        Toast.LENGTH_SHORT
                    ).show()
                    binding.nowPlaying.text = info.name
                    return@launch
                }
                val streamUrl = stream.url ?: run {
                    DebugLog.log("Stream has no URL for '${song.title}'")
                    Toast.makeText(
                        this@MainActivity,
                        "No playable audio stream found",
                        Toast.LENGTH_SHORT
                    ).show()
                    binding.nowPlaying.text = info.name
                    return@launch
                }
                DebugLog.log("Playing '${info.name}' stream: $streamUrl")
                binding.nowPlaying.text = info.name
                player?.setMediaItem(MediaItem.fromUri(streamUrl))
                player?.prepare()
                player?.playWhenReady = true
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

    private fun setupPlayer() {
        val dataSourceFactory = OkHttpDataSource.Factory(DownloaderProvider.okHttpClient())
            .setDefaultRequestProperties(mapOf("Referer" to "https://www.youtube.com/"))

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory)
            )
            .build()

        binding.playerView.player = player
        binding.playerView.setShowNextButton(false)
        binding.playerView.setShowPreviousButton(false)

        player?.addListener(object : Player.Listener {
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
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
