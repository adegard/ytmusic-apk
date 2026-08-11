package com.example.ytmusics

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import android.view.inputmethod.EditorInfo
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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.ytmusics.data.SongResult
import com.example.ytmusics.data.YouTubeApi
import com.example.ytmusics.databinding.ActivityMainBinding
import com.example.ytmusics.net.DownloaderProvider
import com.example.ytmusics.ui.SongAdapter
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "YTMusic"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: SongAdapter
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()

        YouTubeApi.init(DownloaderProvider.downloader())

        adapter = SongAdapter { song -> playSong(song) }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.searchButton.setOnClickListener { doSearch() }
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else {
                false
            }
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

        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)

        binding.progress.isVisible = true
        binding.emptyText.isVisible = false
        binding.emptyText.setText(R.string.search_hint)

        Log.d(TAG, "Searching for: $query")
        lifecycleScope.launch {
            try {
                val results = YouTubeApi.search(query)
                Log.d(TAG, "Search returned ${results.size} results")
                adapter.submitList(results)
                binding.emptyText.isVisible = results.isEmpty()
                if (results.isEmpty()) binding.emptyText.setText(R.string.no_results)
            } catch (e: Exception) {
                Log.e(TAG, "Search failed", e)
                binding.emptyText.isVisible = true
                binding.emptyText.text = "Search failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                binding.progress.isVisible = false
            }
        }
    }

    private fun playSong(song: SongResult) {
        lifecycleScope.launch {
            try {
                val info = YouTubeApi.resolveStreamInfo(song.url)
                val stream = YouTubeApi.pickAudioStream(info)
                if (stream == null) {
                    Toast.makeText(
                        this@MainActivity,
                        "No playable audio stream found",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                binding.nowPlaying.text = info.name
                Log.d(TAG, "Playing '${info.name}' stream: ${stream.id.take(80)}")
                player?.setMediaItem(MediaItem.fromUri(stream.id))
                player?.prepare()
                player?.playWhenReady = true
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "Failed to play: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun setupPlayer() {
        val userAgent =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/119.0.0.0 Mobile Safari/537.36"
        val dataSourceFactory = DefaultHttpDataSource.Factory().setUserAgent(userAgent)

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
                Toast.makeText(
                    this@MainActivity,
                    "Playback error: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
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
