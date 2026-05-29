package com.xray.client.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xray.client.ui.navigation.XrayNavigationHost
import com.xray.client.ui.viewmodel.ConnectionViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val vm: ConnectionViewModel = hiltViewModel()
                val connectionState by vm.connectionState.collectAsStateWithLifecycle()
                val servers         by vm.servers.collectAsStateWithLifecycle()
                val message         by vm.message.collectAsStateWithLifecycle()

                XrayNavigationHost(
                    connectionState    = connectionState,
                    onToggleConnection = vm::toggleConnection,
                    servers            = servers,
                    onServerSelected   = vm::selectServer,
                    message            = message,
                    onConsumeMessage   = vm::consumeMessage,
                    onImport           = vm::importNodes,
                )
            }
        }
    }
}
