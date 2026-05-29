package com.xray.client.domain.model

import kotlinx.serialization.Serializable

/**
 * A single proxy server. Carries enough of the VLESS / VMess / Trojan parameter
 * surface to generate a working xray outbound (TLS / Reality / ws / grpc / etc).
 *
 * All transport fields default to empty/sane values so older persisted nodes
 * (and partially-specified links) still deserialize via `ignoreUnknownKeys`.
 */
@Serializable
data class ProxyNode(
    val id:          String,
    val name:        String,            // display name / remark
    val countryCode: String = "",       // ISO 3166-1 alpha-2 — "US", "JP", "DE"
    val host:        String,
    val port:        Int,
    val uuid:        String,            // vless/vmess UUID, or trojan password
    val protocol:    String = "vless",  // vless | vmess | trojan

    // ── vless / vmess user options ─────────────────────────────────────────
    val flow:          String = "",     // vless flow (e.g. xtls-rprx-vision)
    val encryption:    String = "none", // vless encryption (almost always "none")
    val vmessSecurity: String = "auto", // vmess cipher (scy): auto/aes-128-gcm/…
    val alterId:       Int    = 0,      // vmess alterId (AEAD ⇒ 0)

    // ── stream / transport ─────────────────────────────────────────────────
    val network:     String = "tcp",    // tcp | ws | grpc | h2 | quic
    val security:    String = "none",   // none | tls | reality
    val sni:         String = "",       // TLS / Reality serverName
    val alpn:        String = "",       // comma-separated, e.g. "h2,http/1.1"
    val fingerprint: String = "",       // uTLS fingerprint (fp): chrome/firefox/…
    val publicKey:   String = "",       // Reality public key (pbk)
    val shortId:     String = "",       // Reality short id (sid)
    val spiderX:     String = "",       // Reality spiderX (spx)
    val path:        String = "",       // ws / h2 path
    val hostHeader:  String = "",       // ws / h2 Host header
    val serviceName: String = "",       // grpc serviceName
    val headerType:  String = "",       // tcp header type (e.g. "http")
)

data class RankedNode(
    val node:               ProxyNode,
    val smoothedLatencyMs:  Double,
    val lastRawLatencyMs:   Long,
    val sampleCount:        Int,
) {
    val isReachable: Boolean get() = sampleCount > 0
}
