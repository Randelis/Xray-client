package com.xray.client.data

import com.xray.client.domain.model.ProxyNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/**
 * Parses pasted proxy links or a (base64-encoded) subscription body into
 * [ProxyNode]s. Supports `vless://`, `vmess://` and `trojan://`.
 *
 * The input may be:
 *   • one or more share links, one per line, or
 *   • a base64 blob (typical subscription body) wrapping the above.
 */
object SubscriptionParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parseConfigs(rawInput: String): List<ProxyNode> {
        val text = maybeBase64Decode(rawInput.trim())
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                runCatching {
                    when {
                        line.startsWith("vless://")  -> parseVless(line)
                        line.startsWith("vmess://")  -> parseVmess(line)
                        line.startsWith("trojan://") -> parseTrojan(line)
                        else -> null
                    }
                }.getOrNull()
            }
            .filter { it.host.isNotBlank() && it.port in 1..65535 }
            .toList()
    }

    // ── vless://uuid@host:port?query#remark ────────────────────────────────
    private fun parseVless(link: String): ProxyNode {
        val uri    = URI(link)
        val q      = parseQuery(uri.rawQuery)
        val host   = uri.host ?: error("no host")
        val port   = uri.port.takeIf { it > 0 } ?: error("no port")
        val remark = decodeFragment(uri.fragment) ?: host

        return ProxyNode(
            id          = stableId("vless", host, port, uri.userInfo.orEmpty(), remark),
            name        = remark,
            countryCode = guessCountry(remark),
            host        = host,
            port        = port,
            uuid        = uri.userInfo.orEmpty(),
            protocol    = "vless",
            flow        = q["flow"].orEmpty(),
            encryption  = q["encryption"].ifNullOrBlank("none"),
            network     = q["type"].ifNullOrBlank("tcp"),
            security    = q["security"].ifNullOrBlank("none"),
            sni         = q["sni"] ?: q["host"].orEmpty(),
            alpn        = q["alpn"].orEmpty(),
            fingerprint = q["fp"].orEmpty(),
            publicKey   = q["pbk"].orEmpty(),
            shortId     = q["sid"].orEmpty(),
            spiderX     = q["spx"].orEmpty(),
            path        = q["path"].orEmpty(),
            hostHeader  = q["host"].orEmpty(),
            serviceName = q["serviceName"].orEmpty(),
            headerType  = q["headerType"].orEmpty(),
        )
    }

    // ── vmess://<base64 JSON> ──────────────────────────────────────────────
    private fun parseVmess(link: String): ProxyNode {
        val decoded = decodeBase64(link.removePrefix("vmess://")) ?: error("bad vmess base64")
        val obj     = json.parseToJsonElement(decoded) as JsonObject

        fun str(key: String): String = obj[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        fun int(key: String): Int    = obj[key]?.jsonPrimitive?.intOrNull
            ?: obj[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0

        val host   = str("add")
        val port   = int("port")
        val remark = str("ps").ifBlank { host }
        val tls    = str("tls")

        return ProxyNode(
            id            = stableId("vmess", host, port, str("id"), remark),
            name          = remark,
            countryCode   = guessCountry(remark),
            host          = host,
            port          = port,
            uuid          = str("id"),
            protocol      = "vmess",
            vmessSecurity = str("scy").ifBlank { "auto" },
            alterId       = int("aid"),
            network       = str("net").ifBlank { "tcp" },
            security      = if (tls.isBlank() || tls == "none") "none" else "tls",
            sni           = str("sni").ifBlank { str("host") },
            alpn          = str("alpn"),
            fingerprint   = str("fp"),
            path          = str("path"),
            hostHeader    = str("host"),
            serviceName   = str("path").takeIf { str("net") == "grpc" }.orEmpty(),
            headerType    = str("type"),
        )
    }

    // ── trojan://password@host:port?query#remark ───────────────────────────
    private fun parseTrojan(link: String): ProxyNode {
        val uri    = URI(link)
        val q      = parseQuery(uri.rawQuery)
        val host   = uri.host ?: error("no host")
        val port   = uri.port.takeIf { it > 0 } ?: error("no port")
        val remark = decodeFragment(uri.fragment) ?: host

        return ProxyNode(
            id          = stableId("trojan", host, port, uri.userInfo.orEmpty(), remark),
            name        = remark,
            countryCode = guessCountry(remark),
            host        = host,
            port        = port,
            uuid        = uri.userInfo.orEmpty(),  // trojan password
            protocol    = "trojan",
            network     = q["type"].ifNullOrBlank("tcp"),
            security    = q["security"].ifNullOrBlank("tls"),  // trojan is TLS by default
            sni         = q["sni"] ?: q["peer"] ?: q["host"].orEmpty(),
            alpn        = q["alpn"].orEmpty(),
            fingerprint = q["fp"].orEmpty(),
            path        = q["path"].orEmpty(),
            hostHeader  = q["host"].orEmpty(),
            serviceName = q["serviceName"].orEmpty(),
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val key   = pair.substring(0, idx)
            val value = runCatching {
                URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            }.getOrElse { pair.substring(idx + 1) }
            key to value
        }.toMap()
    }

    private fun decodeFragment(fragment: String?): String? =
        fragment?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }

    /** Returns the input unchanged if it already contains links, else base64-decodes it. */
    private fun maybeBase64Decode(input: String): String {
        if (input.contains("://")) return input
        return decodeBase64(input) ?: input
    }

    private fun decodeBase64(value: String): String? {
        val cleaned = value.trim().replace("\n", "").replace("\r", "").replace(" ", "")
        if (cleaned.isEmpty()) return null
        // Pad to a multiple of 4 so both padded and unpadded inputs decode.
        val padded = cleaned.padEnd((cleaned.length + 3) / 4 * 4, '=')
        return runCatching {
            String(Base64.getUrlDecoder().decode(padded))
        }.recoverCatching {
            String(Base64.getMimeDecoder().decode(padded))
        }.getOrNull()
    }

    private fun stableId(vararg parts: Any): String =
        parts.joinToString("|").hashCode().toString()

    private fun String?.ifNullOrBlank(fallback: String): String =
        if (this.isNullOrBlank()) fallback else this

    // Best-effort country guess from a remark like "🇺🇸 US-Los Angeles" or "Tokyo JP".
    private val countryNames = mapOf(
        "united states" to "US", "usa" to "US", "america" to "US",
        "japan" to "JP", "tokyo" to "JP", "germany" to "DE", "frankfurt" to "DE",
        "singapore" to "SG", "hong kong" to "HK", "hongkong" to "HK",
        "united kingdom" to "GB", "london" to "GB", "netherlands" to "NL",
        "france" to "FR", "canada" to "CA", "korea" to "KR", "taiwan" to "TW",
        "russia" to "RU", "india" to "IN", "australia" to "AU", "turkey" to "TR",
    )

    private fun guessCountry(remark: String): String {
        // 1) Flag emoji → regional indicators back to ASCII.
        val chars = remark.codePoints().toArray()
        for (i in 0 until chars.size - 1) {
            val a = chars[i]; val b = chars[i + 1]
            if (a in 0x1F1E6..0x1F1FF && b in 0x1F1E6..0x1F1FF) {
                val c1 = 'A' + (a - 0x1F1E6); val c2 = 'A' + (b - 0x1F1E6)
                return "$c1$c2"
            }
        }
        // 2) Known country name in the remark.
        val lower = remark.lowercase()
        countryNames.forEach { (name, cc) -> if (lower.contains(name)) return cc }
        return ""
    }
}
