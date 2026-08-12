package com.example.ytmusics

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLog {

    const val TAG = "YTMusic"

    private const val PUBLIC_NAME = "ytmusic-debug.log"

    private var file: File? = null
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        val f = File(context.getExternalFilesDir(null) ?: context.filesDir, PUBLIC_NAME)
        try {
            f.parentFile?.mkdirs()
        } catch (_: Exception) {
        }
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
        mirrorToPublic()
    }

    @Synchronized
    fun logException(msg: String, t: Throwable) {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        log("$msg\n$sw")
    }

    private fun mirrorToPublic() {
        val ctx = appContext ?: return
        val f = file ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val resolver = ctx.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            resolver.delete(
                collection,
                "${MediaStore.Downloads.DISPLAY_NAME}=?",
                arrayOf(PUBLIC_NAME)
            )
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, PUBLIC_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri: Uri? = resolver.insert(collection, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { it.write(f.readText().toByteArray()) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        } catch (_: Exception) {
        }
    }
}
