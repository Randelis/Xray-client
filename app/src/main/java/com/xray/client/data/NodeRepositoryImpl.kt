package com.xray.client.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xray.client.domain.model.ProxyNode
import com.xray.client.domain.repository.NodeRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.nodeStore: DataStore<Preferences> by preferencesDataStore("nodes")
private val NODES_KEY = stringPreferencesKey("node_list")

/**
 * Persists nodes as a JSON array in DataStore (Scoped Storage — no file permissions needed).
 */
@Singleton
class NodeRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : NodeRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override fun observeNodes(): Flow<List<ProxyNode>> =
        context.nodeStore.data.map { prefs -> decode(prefs[NODES_KEY]) }

    override suspend fun addNode(node: ProxyNode) = addNodes(listOf(node))

    override suspend fun addNodes(nodes: List<ProxyNode>) = mutate { current ->
        // Replace existing nodes with the same id, then append new ones, preserving order.
        val incomingIds = nodes.mapTo(HashSet()) { it.id }
        current.filterNot { it.id in incomingIds } + nodes
    }

    override suspend fun removeNode(id: String) = mutate { list ->
        list.filterNot { it.id == id }
    }

    override suspend fun updateNode(node: ProxyNode) = mutate { list ->
        list.map { if (it.id == node.id) node else it }
    }

    override suspend fun clearAll() = mutate { emptyList() }

    private suspend fun mutate(transform: (List<ProxyNode>) -> List<ProxyNode>) {
        context.nodeStore.edit { prefs ->
            prefs[NODES_KEY] = json.encodeToString(transform(decode(prefs[NODES_KEY])))
        }
    }

    /** Tolerant decode: corrupt/legacy data yields an empty list rather than crashing collectors. */
    private fun decode(raw: String?): List<ProxyNode> =
        raw?.let { runCatching { json.decodeFromString<List<ProxyNode>>(it) }.getOrNull() } ?: emptyList()
}
