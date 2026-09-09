package com.example.ytmusics.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.ytmusics.R
import com.example.ytmusics.data.ChannelSubscription
import com.example.ytmusics.data.SongResult
import com.example.ytmusics.databinding.ItemSubscriptionBinding

data class SubscriptionEntry(
    val sub: ChannelSubscription,
    val latestVideo: SongResult?
)

class SubscriptionAdapter(
    private val onClick: (SubscriptionEntry) -> Unit
) : ListAdapter<SubscriptionEntry, SubscriptionAdapter.ViewHolder>(Diff) {

    inner class ViewHolder(private val binding: ItemSubscriptionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: SubscriptionEntry) {
            binding.channelName.text = entry.sub.name
            binding.latestVideo.text =
                entry.latestVideo?.title ?: entry.sub.name
            binding.channelThumb.load(entry.sub.thumbUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_music_placeholder)
            }
            binding.root.setOnClickListener { onClick(entry) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSubscriptionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        private val Diff = object : DiffUtil.ItemCallback<SubscriptionEntry>() {
            override fun areItemsTheSame(oldItem: SubscriptionEntry, newItem: SubscriptionEntry) =
                oldItem.sub.channelId == newItem.sub.channelId

            override fun areContentsTheSame(oldItem: SubscriptionEntry, newItem: SubscriptionEntry) =
                oldItem == newItem
        }
    }
}