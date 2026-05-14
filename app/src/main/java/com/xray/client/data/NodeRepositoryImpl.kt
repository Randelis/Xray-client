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

@Singleton
class NodeRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : NodeRepository {
    private val json = Json { ignoreUnknownKeys = true }

    override fun observeNodes(): Flow<List<ProxyNode>> =
        context.nodeStore.data.map { prefs ->
            prefs[NODES_KEY]?.let { json.decodeFromString<List<ProxyNode>>(it) } ?: emptyList()
        }

    override suspend fun addNode(node: ProxyNode) = mutate { it + node }
    override suspend fun removeNode(id: String) = mutate { it.filterNot { n -> n.id == id } }
    override suspend fun updateNode(node: ProxyNode) = mutate { it.map { n -> if (n.id == node.id) node else n } }

    private suspend fun mutate(transform: (List<ProxyNode>) -> List<ProxyNode>) {
        context.nodeStore.edit { prefs ->
            val current = prefs[NODES_KEY]?.let { json.decodeFromString<List<ProxyNode>>(it) } ?: emptyList()
            prefs[NODES_KEY] = json.encodeToString(transform(current))
        }
    }
}
