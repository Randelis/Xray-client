package com.xray.client.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xray.client.core.CoreManager
import com.xray.client.domain.model.ProxyNode
import com.xray.client.domain.model.RankedNode
import com.xray.client.domain.repository.NodeRepository
import com.xray.client.routing.AdaptiveRoutingEngine
import com.xray.client.routing.RoutingMode
import com.xray.client.ui.components.ConnectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject

@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val coreManager:     CoreManager,
    private val routingEngine:   AdaptiveRoutingEngine,
    private val nodeRepository:  NodeRepository,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = coreManager.state
        .map { it.toUiState() }
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = ConnectionState.Idle,
        )

    private val sampleWindows = mutableMapOf<String, ArrayDeque<Long>>()
    private val refreshTrigger = MutableStateFlow(0)

    val servers: StateFlow<List<RankedNode>> = combine(
        nodeRepository.observeNodes(),
        refreshTrigger,
    ) { nodes, _ -> nodes }
        .map { nodes -> probeAll(nodes) }
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private var selectedNodeId: String? = null

    init {
        viewModelScope.launch {
            val existing = nodeRepository.observeNodes().first()
            if (existing.isEmpty()) {
                SAMPLE_NODES.forEach { nodeRepository.addNode(it) }
            }
        }
    }

    fun toggleConnection() {
        viewModelScope.launch {
            if (coreManager.isRunning) {
                coreManager.stop()
            } else {
                val node = pickSelectedOrFastest() ?: return@launch
                routingEngine.switchTo(RoutingMode.SOCKS5, node)
            }
        }
    }

    fun selectServer(ranked: RankedNode) {
        selectedNodeId = ranked.node.id
        if (coreManager.isRunning) {
            viewModelScope.launch {
                routingEngine.switchTo(routingEngine.activeMode.value, ranked.node)
            }
        }
    }

    fun refresh() { refreshTrigger.value++ }

    private suspend fun pickSelectedOrFastest(): ProxyNode? {
        val ranked = servers.value
        return ranked.firstOrNull { it.node.id == selectedNodeId }?.node
            ?: ranked.firstOrNull()?.node
    }

    private suspend fun probeAll(nodes: List<ProxyNode>): List<RankedNode> = coroutineScope {
        val results = nodes.map { node ->
            async(Dispatchers.IO) { node to tcpRtt(node) }
        }.awaitAll()

        results.map { (node, rtt) ->
            val window = sampleWindows.getOrPut(node.id) { ArrayDeque(WMA_WINDOW) }
            if (rtt != null) {
                if (window.size == WMA_WINDOW) window.removeFirst()
                window.addLast(rtt)
            }
            RankedNode(
                node               = node,
                smoothedLatencyMs  = weightedMovingAverage(window),
                lastRawLatencyMs   = rtt ?: Long.MAX_VALUE,
                sampleCount        = window.size,
            )
        }.sortedWith(compareBy({ !it.isReachable }, { it.smoothedLatencyMs }))
    }

    private suspend fun tcpRtt(node: ProxyNode): Long? = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { s ->
                val t0 = System.currentTimeMillis()
                s.connect(InetSocketAddress(node.host, node.port), CONNECT_TIMEOUT_MS)
                System.currentTimeMillis() - t0
            }
        }.getOrNull()
    }

    private fun weightedMovingAverage(samples: ArrayDeque<Long>): Double {
        if (samples.isEmpty()) return Double.MAX_VALUE
        var weightedSum = 0.0
        var totalWeight = 0
        samples.forEachIndexed { i, v ->
            val w = i + 1
            weightedSum += w * v
            totalWeight += w
        }
        return weightedSum / totalWeight
    }

    private fun CoreManager.State.toUiState(): ConnectionState = when (this) {
        is CoreManager.State.Idle,
        is CoreManager.State.Error    -> ConnectionState.Idle
        is CoreManager.State.Starting,
        is CoreManager.State.Stopping -> ConnectionState.Connecting
        is CoreManager.State.Running  -> ConnectionState.Connected
    }

    companion object {
        private const val WMA_WINDOW         = 5
        private const val CONNECT_TIMEOUT_MS = 3_000

        private val SAMPLE_NODES = listOf(
            sample("us-la",  "Los Angeles",  "US", "one.one.one.one",        443),
            sample("jp-tk",  "Tokyo",        "JP", "dns.google",             443),
            sample("de-fr",  "Frankfurt",    "DE", "dns.quad9.net",          443),
            sample("uk-ln",  "London",       "GB", "cloudflare-dns.com",     443),
            sample("sg-sg",  "Singapore",    "SG", "dns.adguard.com",        443),
            sample("nl-am",  "Amsterdam",    "NL", "doh.opendns.com",        443),
            sample("au-sy",  "Sydney",       "AU", "dns11.quad9.net",        443),
            sample("ca-tr",  "Toronto",      "CA", "doh.libredns.gr",        443),
            sample("br-sp",  "São Paulo",    "BR", "dns.nextdns.io",         443),
            sample("fr-pa",  "Paris",        "FR", "doh.cleanbrowsing.org",  443),
        )

        private fun sample(id: String, city: String, cc: String, host: String, port: Int) =
            ProxyNode(
                id          = id,
                name        = city,
                countryCode = cc,
                host        = host,
                port        = port,
                uuid        = "00000000-0000-0000-0000-000000000000",
            )
    }
}
