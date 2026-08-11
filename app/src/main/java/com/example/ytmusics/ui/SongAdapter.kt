package com.example.ytmusics.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.ytmusics.R
import com.example.ytmusics.data.SongResult
import com.example.ytmusics.databinding.ItemSongBinding
import java.util.Locale

class SongAdapter(
    private val onClick: (SongResult) -> Unit
) : ListAdapter<SongResult, SongAdapter.ViewHolder>(Diff) {

    inner class ViewHolder(private val binding: ItemSongBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(song: SongResult) {
            binding.title.text = song.title
            binding.subtitle.text = listOf(
                song.uploader,
                if (song.duration > 0) formatDuration(song.duration) else null
            ).filterNotNull().joinToString(" \u2022 ")
            binding.thumb.load(song.thumbUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_music_placeholder)
            }
            binding.root.setOnClickListener { onClick(song) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    companion object {
        private val Diff = object : DiffUtil.ItemCallback<SongResult>() {
            override fun areItemsTheSame(oldItem: SongResult, newItem: SongResult) =
                oldItem.url == newItem.url

            override fun areContentsTheSame(oldItem: SongResult, newItem: SongResult) =
                oldItem == newItem
        }
    }
}
