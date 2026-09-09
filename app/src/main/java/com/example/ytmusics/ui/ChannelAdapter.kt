package com.example.ytmusics.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.ytmusics.R
import com.example.ytmusics.data.ChannelResult
import com.example.ytmusics.databinding.ItemChannelBinding

class ChannelAdapter(
    private val isSubscribed: (ChannelResult) -> Boolean,
    private val onSubscribe: (ChannelResult) -> Unit,
    private val onUnsubscribe: (ChannelResult) -> Unit
) : ListAdapter<ChannelResult, ChannelAdapter.ViewHolder>(Diff) {

    inner class ViewHolder(private val binding: ItemChannelBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(channel: ChannelResult) {
            binding.channelName.text = channel.name
            val subText = if (channel.subscriberCount > 0) {
                binding.root.context.getString(R.string.subscribers, formatCount(channel.subscriberCount))
            } else ""
            binding.channelSubs.text = subText
            binding.channelThumb.load(channel.thumbUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_music_placeholder)
            }
            updateButton(channel)
            binding.subscribeButton.setOnClickListener {
                if (isSubscribed(channel)) {
                    onUnsubscribe(channel)
                } else {
                    onSubscribe(channel)
                }
                notifyItemChanged(bindingAdapterPosition)
            }
        }

        private fun updateButton(channel: ChannelResult) {
            if (isSubscribed(channel)) {
                binding.subscribeButton.text = binding.root.context.getString(R.string.unsubscribe)
            } else {
                binding.subscribeButton.text = binding.root.context.getString(R.string.subscribe)
            }
        }

        private fun formatCount(count: Long): String {
            return when {
                count >= 1_000_000_000 -> String.format("%.1fB", count / 1_000_000_000.0)
                count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
                count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
                else -> count.toString()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        private val Diff = object : DiffUtil.ItemCallback<ChannelResult>() {
            override fun areItemsTheSame(oldItem: ChannelResult, newItem: ChannelResult) =
                oldItem.url == newItem.url

            override fun areContentsTheSame(oldItem: ChannelResult, newItem: ChannelResult) =
                oldItem == newItem
        }
    }
}
