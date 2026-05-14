package com.xray.client.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xray.client.domain.model.RankedNode
import com.xray.client.ui.latency.LatencyViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme { NodeListScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeListScreen(vm: LatencyViewModel = hiltViewModel()) {
    val nodes        by vm.rankedNodes.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Xray Client") },
                actions = {
                    if (isRefreshing)
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    else
                        TextButton(onClick = vm::refresh) { Text("Refresh") }
                }
            )
        }
    ) { padding ->
        if (nodes.isEmpty() && !isRefreshing) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Text("No nodes configured.", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(contentPadding = padding) {
                items(nodes, key = { it.node.id }) { NodeRow(it); HorizontalDivider() }
            }
        }
    }
}

@Composable
private fun NodeRow(ranked: RankedNode) {
    ListItem(
        headlineContent   = { Text(ranked.node.name) },
        supportingContent = { Text("${ranked.node.host}:${ranked.node.port}") },
        trailingContent   = {
            if (ranked.isReachable)
                Text("${"%.0f".format(ranked.smoothedLatencyMs)} ms",
                    style = MaterialTheme.typography.labelLarge,
                    color = latencyColor(ranked.smoothedLatencyMs))
            else
                Text("timeout", color = MaterialTheme.colorScheme.error)
        }
    )
}

@Composable
private fun latencyColor(ms: Double) = when {
    ms < 100  -> MaterialTheme.colorScheme.primary
    ms < 300  -> MaterialTheme.colorScheme.tertiary
    else      -> MaterialTheme.colorScheme.error
}
