package com.xray.client.domain.model

import kotlinx.serialization.Serializable

/** A single proxy endpoint that xray can route traffic through. */
@Serializable
data class ProxyNode(
    val id:       String,
    val name:     String,
    val host:     String,
    val port:     Int,
    val uuid:     String,
    val protocol: String = "vmess",
    val tls:      Boolean = true,
)

/**
 * A node decorated with its smoothed latency after the WMA pass.
 * [smoothedLatencyMs] is [Double.MAX_VALUE] when no samples exist (node unreachable).
 */
data class RankedNode(
    val node:               ProxyNode,
    val smoothedLatencyMs:  Double,
    val lastRawLatencyMs:   Long,
    val sampleCount:        Int,
) {
    val isReachable: Boolean get() = sampleCount > 0
}
