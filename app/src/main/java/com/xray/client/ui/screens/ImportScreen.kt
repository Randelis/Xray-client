package com.xray.client.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

/**
 * Panel for importing servers. Accepts:
 *   • one or more share links (vless:// / vmess:// / trojan://), one per line, or
 *   • a single subscription URL (https://…) that returns a base64 list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    onImport: (String) -> Unit,
    onBack:   () -> Unit,
) {
    BackHandler(onBack = onBack)

    var input by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title          = { Text("Import servers") },
                navigationIcon = { IconButton(onClick = onBack) { Text("←") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text  = "Paste a VLESS / VMess / Trojan link (one per line) or a subscription URL.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value         = input,
                onValueChange = { input = it },
                modifier      = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp),
                label         = { Text("vless://…  or  https://your-subscription") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            )

            Button(
                onClick  = {
                    val text = input.trim()
                    if (text.isNotEmpty()) {
                        onImport(text)
                        onBack()
                    }
                },
                enabled  = input.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Import")
            }
        }
    }
}
