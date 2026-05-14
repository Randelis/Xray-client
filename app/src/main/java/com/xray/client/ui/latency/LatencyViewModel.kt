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

@HiltViewModel
class LatencyViewModel @Inject constructor(
    private val nodeRepository: NodeRepository,
) : ViewModel() {

    private val sampleWindows = mutableMapOf<String, ArrayDeque<Long>>()

    private val _rankedNodes  = MutableStateFlow<List<RankedNode>>(emptyList())
    val rankedNodes: StateFlow<List<RankedNode>> = _rankedNodes.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private var probeJob: Job? = null

    init {
        viewModelScope.launch {
            nodeRepository.observeNodes().distinctUntilChanged().collect { probeAll(it) }
        }
    }

    fun refresh() { viewModelScope.launch { probeAll(nodeRepository.observeNodes().first()) } }

    private suspend fun probeAll(nodes: List<ProxyNode>) {
        probeJob?.cancelAndJoin()
        probeJob = viewModelScope.launch {
            _isRefreshing.value = true
            val results = nodes.map { async(Dispatchers.IO) { it to tcpRtt(it) } }.awaitAll()
            val ranked = results.map { (node, rtt) ->
                val window = sampleWindows.getOrPut(node.id) { ArrayDeque(WMA_WINDOW) }
                if (rtt != null) { if (window.size == WMA_WINDOW) window.removeFirst(); window.addLast(rtt) }
                RankedNode(node, weightedMovingAverage(window), rtt ?: Long.MAX_VALUE, window.size)
            }.sortedWith(compareBy({ !it.isReachable }, { it.smoothedLatencyMs }))
            _rankedNodes.value  = ranked
            _isRefreshing.value = false
        }
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
        var weightedSum = 0.0; var totalWeight = 0
        samples.forEachIndexed { i, v -> val w = i + 1; weightedSum += w * v; totalWeight += w }
        return weightedSum / totalWeight
    }

    companion object {
        private const val WMA_WINDOW         = 5
        private const val CONNECT_TIMEOUT_MS = 3_000
    }
}
