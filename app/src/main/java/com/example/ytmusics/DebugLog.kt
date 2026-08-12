package com.example.ytmusics

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLog {

    const val TAG = "YTMusic"

    private var file: File? = null

    fun init(context: Context) {
        val f = File(context.getExternalFilesDir(null) ?: context.filesDir, "ytmusic-debug.log")
        file = f
        val versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        log(
            "=== app start ===\n" +
                "device=${Build.MANUFACTURER} ${Build.MODEL}\n" +
                "android=${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})\n" +
                "version=$versionName\n" +
                "logfile=$f"
        )
    }

    @Synchronized
    fun log(msg: String) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$ts $msg"
        try {
            Log.d(TAG, msg)
        } catch (_: Exception) {
        }
        try {
            file?.appendText(line + "\n")
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun logException(msg: String, t: Throwable) {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        log("$msg\n$sw")
    }
}
