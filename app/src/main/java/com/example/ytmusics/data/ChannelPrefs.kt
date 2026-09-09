package com.example.ytmusics.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object ChannelPrefs {

    private const val PREFS_NAME = "channel_subscriptions"
    private const val KEY_SUBS = "subscriptions"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSubscriptions(context: Context): List<ChannelSubscription> {
        val json = prefs(context).getString(KEY_SUBS, "[]") ?: "[]"
        val arr = JSONArray(json)
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            ChannelSubscription(
                channelId = obj.optString("channelId", ""),
                name = obj.optString("name", ""),
                thumbUrl = obj.optString("thumbUrl", ""),
                url = obj.optString("url", "")
            )
        }
    }

    fun isSubscribed(context: Context, channelUrl: String): Boolean =
        getSubscriptions(context).any { it.url == channelUrl }

    fun subscribe(context: Context, sub: ChannelSubscription) {
        val current = getSubscriptions(context).toMutableList()
        if (current.any { it.url == sub.url }) return
        current.add(sub)
        save(context, current)
    }

    fun unsubscribe(context: Context, channelUrl: String) {
        val current = getSubscriptions(context).toMutableList()
        current.removeAll { it.url == channelUrl }
        save(context, current)
    }

    private fun save(context: Context, list: List<ChannelSubscription>) {
        val arr = JSONArray()
        list.forEach { sub ->
            arr.put(JSONObject().apply {
                put("channelId", sub.channelId)
                put("name", sub.name)
                put("thumbUrl", sub.thumbUrl)
                put("url", sub.url)
            })
        }
        prefs(context).edit().putString(KEY_SUBS, arr.toString()).apply()
    }
}

data class ChannelSubscription(
    val channelId: String,
    val name: String,
    val thumbUrl: String,
    val url: String
)
