package com.xray.client.domain.model

import kotlinx.serialization.Serializable

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

data class RankedNode(
    val node:               ProxyNode,
    val smoothedLatencyMs:  Double,
    val lastRawLatencyMs:   Long,
    val sampleCount:        Int,
) {
    val isReachable: Boolean get() = sampleCount > 0
}
