package com.xray.client.ui.latency

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xray.client.domain.model.ProxyNode
import com.xray.client.domain.model.RankedNode
import com.xray.client.domain.repository.NodeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject

/**
 * Fetches latency for every known node and ranks them using a Weighted Moving
 * Average (WMA) so that a single jittery sample doesn't cause the list to
 * re-sort on every poll.
 *
 * WMA weight scheme (window = 5 samples, index 0 = oldest):
 *   weight[i] = i + 1   →   oldest:1, …, newest:5
 *   WMA = Σ(weight[i] × latency[i]) / Σ(weight[i])
 *
 * A node that cannot be reached gets latency = null; its WMA stays at
 * Double.MAX_VALUE and it sinks to the bottom of the list.
 */
@HiltViewModel
class LatencyViewModel @Inject constructor(
    private val nodeRepository: NodeRepository,
) : ViewModel() {

    // Per-node sliding window of raw RTT measurements (oldest → newest)
    private val sampleWindows = mutableMapOf<String, ArrayDeque<Long>>()

    private val _rankedNodes   = MutableStateFlow<List<RankedNode>>(emptyList())
    val rankedNodes: StateFlow<List<RankedNode>> = _rankedNodes.asStateFlow()

    private val _isRefreshing  = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // Tracks the currently running probe job so refresh() can cancel it
    private var probeJob: Job? = null

    init {
        // Whenever the node list changes (import / add / remove), re-probe
        viewModelScope.launch {
            nodeRepository.observeNodes()
                .distinctUntilChanged()
                .collect { nodes -> probeAll(nodes) }
        }
    }

    /** Manually trigger a fresh latency sweep (e.g. pull-to-refresh). */
    fun refresh() {
        viewModelScope.launch {
            probeAll(nodeRepository.observeNodes().first())
        }
    }

    // -------------------------------------------------------------------------
    // Probing
    // -------------------------------------------------------------------------

    private suspend fun probeAll(nodes: List<ProxyNode>) {
        probeJob?.cancelAndJoin()
        probeJob = viewModelScope.launch {
            _isRefreshing.value = true

            // Fan-out: measure all nodes concurrently on the IO thread pool
            val results = nodes.map { node ->
                async(Dispatchers.IO) { node to tcpRtt(node) }
            }.awaitAll()

            val ranked = results.map { (node, rawLatency) ->
                val window = sampleWindows.getOrPut(node.id) { ArrayDeque(WMA_WINDOW) }
                if (rawLatency != null) {
                    if (window.size == WMA_WINDOW) window.removeFirst()
                    window.addLast(rawLatency)
                }
                RankedNode(
                    node              = node,
                    smoothedLatencyMs = weightedMovingAverage(window),
                    lastRawLatencyMs  = rawLatency ?: Long.MAX_VALUE,
                    sampleCount       = window.size,
                )
            }.sortedWith(
                compareBy(
                    { !it.isReachable },          // reachable nodes always first
                    { it.smoothedLatencyMs },     // then lowest WMA
                )
            )

            _rankedNodes.value  = ranked
            _isRefreshing.value = false
        }
    }

    // -------------------------------------------------------------------------
    // Latency measurement  — TCP connect RTT to host:port
    //
    // We measure a TCP handshake to the proxy endpoint itself.  This is the
    // dominant component of perceived latency for short-lived connections and
    // avoids routing test traffic through the actual proxy (which may timeout
    // if the node is misconfigured rather than slow).
    // -------------------------------------------------------------------------

    private suspend fun tcpRtt(node: ProxyNode): Long? = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { socket ->
                val t0 = System.currentTimeMillis()
                socket.connect(InetSocketAddress(node.host, node.port), CONNECT_TIMEOUT_MS)
                System.currentTimeMillis() - t0
            }
        }.getOrNull()
    }

    // -------------------------------------------------------------------------
    // Weighted Moving Average
    // -------------------------------------------------------------------------

    /**
     * Returns [Double.MAX_VALUE] for an empty window so unreachable nodes sort
     * to the end regardless of how they compare with finite values.
     */
    private fun weightedMovingAverage(samples: ArrayDeque<Long>): Double {
        if (samples.isEmpty()) return Double.MAX_VALUE
        // Enumerate oldest-to-newest; weight = position (1-based)
        var weightedSum = 0.0
        var totalWeight = 0
        samples.forEachIndexed { index, rtt ->
            val weight   = index + 1
            weightedSum += weight * rtt
            totalWeight += weight
        }
        return weightedSum / totalWeight
    }

    companion object {
        private const val WMA_WINDOW        = 5
        private const val CONNECT_TIMEOUT_MS = 3_000
    }
}
