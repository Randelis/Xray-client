package com.xray.client.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xray.client.domain.model.RankedNode
import com.xray.client.ui.components.ConnectionButton
import com.xray.client.ui.components.ConnectionState
import com.xray.client.ui.screens.ImportScreen
import com.xray.client.ui.screens.ServerCard
import kotlinx.coroutines.delay

private enum class Route { Main, Servers, Import }
private const val KEY_CONNECTION = "connection-indicator"

@OptIn(ExperimentalSharedTransitionApi::class)
private val ConnectionBoundsTransform = BoundsTransform { _, _ ->
    spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness    = Spring.StiffnessMediumLow,
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun XrayNavigationHost(
    connectionState:    ConnectionState,
    onToggleConnection: () -> Unit,
    servers:            List<RankedNode>,
    onServerSelected:   (RankedNode) -> Unit,
    message:            String?,
    onConsumeMessage:   () -> Unit,
    onImport:           (String) -> Unit,
) {
    var route by rememberSaveable { mutableStateOf(Route.Main) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            onConsumeMessage()
        }
    }

    SharedTransitionLayout {
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState    = route,
                transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) },
                label          = "route",
            ) { current ->
                when (current) {
                    Route.Main -> MainScreen(
                        connectionState    = connectionState,
                        onToggleConnection = onToggleConnection,
                        onShowServers      = { route = Route.Servers },
                        sharedScope        = this@SharedTransitionLayout,
                        animScope          = this@AnimatedContent,
                    )
                    Route.Servers -> ServerListScreen(
                        servers          = servers,
                        connectionState  = connectionState,
                        onServerSelected = onServerSelected,
                        onBack           = { route = Route.Main },
                        onAddServers     = { route = Route.Import },
                        sharedScope      = this@SharedTransitionLayout,
                        animScope        = this@AnimatedContent,
                    )
                    Route.Import -> ImportScreen(
                        onImport = onImport,
                        onBack   = { route = Route.Servers },
                    )
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier  = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    connectionState:    ConnectionState,
    onToggleConnection: () -> Unit,
    onShowServers:      () -> Unit,
    sharedScope:        SharedTransitionScope,
    animScope:          AnimatedVisibilityScope,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title   = { Text("Xray Client") },
                actions = { TextButton(onClick = onShowServers) { Text("Servers") } },
            )
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            with(sharedScope) {
                ConnectionButton(
                    state   = connectionState,
                    onClick = onToggleConnection,
                    modifier = Modifier.sharedBounds(
                        sharedContentState      = rememberSharedContentState(KEY_CONNECTION),
                        animatedVisibilityScope = animScope,
                        boundsTransform         = ConnectionBoundsTransform,
                    ),
                )
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ServerListScreen(
    servers:          List<RankedNode>,
    connectionState:  ConnectionState,
    onServerSelected: (RankedNode) -> Unit,
    onBack:           () -> Unit,
    onAddServers:     () -> Unit,
    sharedScope:      SharedTransitionScope,
    animScope:        AnimatedVisibilityScope,
) {
    BackHandler(onBack = onBack)

    val animatedIds = remember { mutableStateListOf<String>() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Servers") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←", fontSize = 24.sp) }
                },
                actions = {
                    TextButton(onClick = onAddServers) { Text("Import") }
                    with(sharedScope) {
                        StatusDot(
                            connectionState = connectionState,
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .sharedBounds(
                                    sharedContentState      = rememberSharedContentState(KEY_CONNECTION),
                                    animatedVisibilityScope = animScope,
                                    boundsTransform         = ConnectionBoundsTransform,
                                ),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (servers.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text      = "No servers yet.\nTap “Import” to add a VLESS link or subscription.",
                    textAlign = TextAlign.Center,
                    style     = MaterialTheme.typography.bodyLarge,
                    color     = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier            = Modifier.fillMaxSize().padding(padding),
            contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(servers, key = { _, s -> s.node.id }) { index, server ->
                val placement: Modifier = Modifier.animateItem(
                    fadeInSpec    = null,
                    fadeOutSpec   = null,
                    placementSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness    = Spring.StiffnessLow,
                    ),
                )

                val alreadyAnimated = server.node.id in animatedIds
                var visible by remember(server.node.id) { mutableStateOf(alreadyAnimated) }

                if (!alreadyAnimated) {
                    LaunchedEffect(Unit) {
                        delay(index.coerceAtMost(8) * 45L)
                        visible = true
                        animatedIds.add(server.node.id)
                    }
                }

                AnimatedVisibility(
                    visible  = visible,
                    modifier = placement,
                    enter = fadeIn(tween(300)) + slideInVertically(
                        initialOffsetY = { it / 3 },
                        animationSpec  = tween(300, easing = EaseOutCubic),
                    ),
                ) {
                    ServerCard(
                        server  = server,
                        onClick = { onServerSelected(server) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusDot(
    connectionState: ConnectionState,
    modifier:        Modifier = Modifier,
) {
    val color = when (connectionState) {
        ConnectionState.Idle       -> Color(0xFF4A4A4A)
        ConnectionState.Connecting -> Color(0xFFFF8A1F)
        ConnectionState.Connected  -> Color(0xFF00E676)
    }
    Box(
        modifier = modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(color),
    )
}
