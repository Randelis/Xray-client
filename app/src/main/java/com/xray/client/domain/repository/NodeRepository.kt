package com.xray.client.domain.repository

import com.xray.client.domain.model.ProxyNode
import kotlinx.coroutines.flow.Flow

interface NodeRepository {
    /** Cold flow that emits the current node list and any subsequent updates. */
    fun observeNodes(): Flow<List<ProxyNode>>

    suspend fun addNode(node: ProxyNode)
    suspend fun removeNode(id: String)
    suspend fun updateNode(node: ProxyNode)
}
