package com.androclaw.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Ollama exposes OpenAI-compatible APIs at `.../v1/chat/completions`.
 * Accepts a host root like `http://192.168.1.5:11434` or `http://host:11434/` and ensures `/v1`.
 */
fun normalizeOllamaOpenAiBaseUrl(input: String): String {
    var u = input.trim().trimEnd('/')
    if (u.isEmpty()) return ""
    if (!u.endsWith("/v1", ignoreCase = true)) u = "$u/v1"
    return u
}

fun Context.hasInternetConnectivity(): Boolean {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

fun Throwable.isLikelyConnectivityFailure(): Boolean {
    var t: Throwable? = this
    while (t != null) {
        when (t) {
            is UnknownHostException -> return true
            is ConnectException -> return true
            is SocketTimeoutException -> return true
            is IOException -> {
                val m = t.message ?: ""
                if (m.contains("Unable to resolve host", ignoreCase = true)) return true
                if (m.contains("No address associated with hostname", ignoreCase = true)) return true
                if (m.contains("Network is unreachable", ignoreCase = true)) return true
            }
        }
        t = t.cause
    }
    return false
}

object NetworkErrors {
    const val NO_CONNECTION_USER_MESSAGE =
        "No internet connection. Turn on Wi-Fi or mobile data and try again."

    fun isConnectivityUserMessage(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        if (message == NO_CONNECTION_USER_MESSAGE) return true
        if (message.contains("Unable to resolve host", ignoreCase = true)) return true
        if (message.contains("No address associated with hostname", ignoreCase = true)) return true
        if (message.contains("Network is unreachable", ignoreCase = true)) return true
        return false
    }
}
