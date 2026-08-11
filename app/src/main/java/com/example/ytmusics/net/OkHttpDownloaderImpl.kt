package com.example.ytmusics.net

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.IOException

class OkHttpDownloaderImpl(private val client: OkHttpClient) : Downloader() {

    override fun execute(request: Request): Response {
        val builder = okhttp3.Request.Builder().url(request.url())

        when (request.httpMethod()) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            "POST" -> builder.post(
                request.dataToSend()?.toRequestBody(null)
                    ?: ByteArray(0).toRequestBody(null)
            )
            else -> throw IOException("Unsupported HTTP method: ${request.httpMethod()}")
        }

        request.headers()?.forEach { (key, values) ->
            values.forEach { builder.header(key, it) }
        }

        client.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            val headers = LinkedHashMap<String, MutableList<String>>()
            resp.headers.forEach { (key, value) ->
                headers.getOrPut(key) { mutableListOf() }.add(value)
            }
            return Response(resp.code, resp.message, headers, body, request.url())
        }
    }
}
