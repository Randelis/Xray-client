package com.xray.client.data

import com.xray.client.domain.model.ProxyNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns user input from the import panel into [ProxyNode]s.
 *
 * If the input is a single http(s) URL it is treated as a subscription: the body
 * is fetched and handed to [SubscriptionParser] (which base64-decodes as needed).
 * Otherwise the raw text is parsed directly as one or more share links.
 */
@Singleton
class SubscriptionImporter @Inject constructor() {

    suspend fun import(rawInput: String): Result<List<ProxyNode>> = withContext(Dispatchers.IO) {
        runCatching {
            val input = rawInput.trim()
            val text  = if (isSubscriptionUrl(input)) fetch(input) else input
            SubscriptionParser.parseConfigs(text)
        }
    }

    private fun isSubscriptionUrl(input: String): Boolean {
        val lower = input.lowercase()
        // A subscription is a single bare URL — not a vless://… style share link
        // and not multiple lines of links.
        return (lower.startsWith("http://") || lower.startsWith("https://")) &&
            !input.any { it.isWhitespace() }
    }

    private fun fetch(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod    = "GET"
            connectTimeout   = CONNECT_TIMEOUT_MS
            readTimeout      = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) error("Subscription fetch failed (HTTP $code)")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS    = 15_000
        const val USER_AGENT         = "XrayClient/1.0"
    }
}
