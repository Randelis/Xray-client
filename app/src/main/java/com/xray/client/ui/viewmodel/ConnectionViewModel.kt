package com.xray.client.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xray.client.core.CoreManager
import com.xray.client.data.SubscriptionImporter
import com.xray.client.domain.model.ProxyNode
import com.xray.client.domain.model.RankedNode
import com.xray.client.domain.repository.NodeRepository
import com.xray.client.routing.AdaptiveRoutingEngine
import com.xray.client.routing.AdaptiveRoutingEngine.SwitchResult
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
    private val coreManager:    CoreManager,
    private val routingEngine:  AdaptiveRoutingEngine,
    private val nodeRepository: NodeRepository,
    private val importer:       SubscriptionImporter,
) : ViewModel() {

    // ── Connection state ──────────────────────────────────────────────────
    val connectionState: StateFlow<ConnectionState> = coreManager.state
        .map { it.toUiState() }
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = ConnectionState.Idle,
        )

    // ── One-shot user-facing messages (errors, import results) ────────────
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun consumeMessage() { _message.value = null }

    // ── Server list with WMA-smoothed latency ─────────────────────────────
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
        // Surface unexpected core exits to the UI instead of silently going Idle.
        viewModelScope.launch {
            coreManager.state.collect { st ->
                if (st is CoreManager.State.Error) {
                    _message.value = st.cause.message ?: "Connection stopped unexpectedly"
                }
            }
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────

    fun toggleConnection() {
        viewModelScope.launch {
            if (coreManager.isRunning) {
                coreManager.stop()
                routingEngine.onStopped()
            } else {
                val node = pickSelectedOrFastest()
                if (node == null) {
                    _message.value = "No servers — import a VLESS link or subscription first"
                    return@launch
                }
                when (val r = routingEngine.switchTo(RoutingMode.SOCKS5, node)) {
                    is SwitchResult.Ok                 -> Unit
                    is SwitchResult.CoreError          ->
                        _message.value = "Failed to connect: ${r.cause.message ?: "core error"}"
                    is SwitchResult.NeedsVpnPermission ->
                        _message.value = "VPN permission required for TUN mode"
                }
            }
        }
    }

    fun selectServer(ranked: RankedNode) {
        selectedNodeId = ranked.node.id
        if (coreManager.isRunning) {
            viewModelScope.launch {
                when (val r = routingEngine.switchTo(routingEngine.activeMode.value, ranked.node)) {
                    is SwitchResult.CoreError ->
                        _message.value = "Failed to switch server: ${r.cause.message ?: "core error"}"
                    else -> Unit
                }
            }
        }
    }

    /** Import VLESS/VMess/Trojan links or a subscription URL pasted by the user. */
    fun importNodes(rawInput: String) {
        viewModelScope.launch {
            _message.value = "Importing…"
            val result = importer.import(rawInput)
            val nodes  = result.getOrElse {
                _message.value = "Import failed: ${it.message ?: "could not read input"}"
                return@launch
            }
            if (nodes.isEmpty()) {
                _message.value = "No servers found in the input"
                return@launch
            }
            nodeRepository.addNodes(nodes)
            _message.value = "Imported ${nodes.size} server(s)"
        }
    }

    fun refresh() { refreshTrigger.value++ }

    // ── Internals ─────────────────────────────────────────────────────────

    private fun pickSelectedOrFastest(): ProxyNode? {
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
                node              = node,
                smoothedLatencyMs = weightedMovingAverage(window),
                lastRawLatencyMs  = rtt ?: Long.MAX_VALUE,
                sampleCount       = window.size,
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
    }
}
