package com.example.ytmusics

import android.content.Context
import android.util.Log

object DebugLog {

    const val TAG = "YTMusic"

    fun init(context: Context) {
    }

    fun log(msg: String) {
        try {
            Log.d(TAG, msg)
        } catch (_: Exception) {
        }
    }

    fun logException(msg: String, t: Throwable) {
        try {
            Log.e(TAG, msg, t)
        } catch (_: Exception) {
        }
    }
}
