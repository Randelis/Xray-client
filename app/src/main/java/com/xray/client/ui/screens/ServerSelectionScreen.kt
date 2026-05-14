package com.xray.client.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xray.client.domain.model.RankedNode

@Composable
fun ServerCard(
    server:   RankedNode,
    onClick:  () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pingMs = if (server.isReachable) server.smoothedLatencyMs.toInt() else Int.MAX_VALUE

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape  = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlagIcon(countryCode = server.node.countryCode)
            Spacer(Modifier.width(16.dp))

            Text(
                text     = server.node.name,
                style    = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )

            PingIndicator(pingMs = pingMs)
            Spacer(Modifier.width(10.dp))
            Text(
                text  = if (server.isReachable) "${pingMs} ms" else "—",
                style = MaterialTheme.typography.labelLarge,
                color = pingColor(pingMs),
            )
        }
    }
}

@Composable
private fun FlagIcon(countryCode: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text      = countryCodeToFlag(countryCode),
            fontSize  = 22.sp,
            textAlign = TextAlign.Center,
        )
    }
}

private fun countryCodeToFlag(code: String): String {
    if (code.length != 2 || !code.all { it.isLetter() }) return "🏳"
    return code.uppercase().map {
        String(Character.toChars(0x1F1E6 + (it.code - 'A'.code)))
    }.joinToString("")
}

@Composable
private fun PingIndicator(pingMs: Int, modifier: Modifier = Modifier) {
    val color    = pingColor(pingMs)
    val infinite = rememberInfiniteTransition(label = "ping")

    val t by infinite.animateFloat(
        initialValue = 0f,
        targetValue  = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
        ),
        label = "wavefront",
    )

    Box(
        modifier = modifier
            .size(28.dp)
            .drawBehind {
                floatArrayOf(0f, 0.33f, 0.66f).forEach { offset ->
                    val phase  = (t + offset) % 1f
                    val radius = (size.minDimension / 2f) * (0.25f + 0.75f * phase)
                    val alpha  = (1f - phase) * 0.55f
                    drawCircle(
                        color  = color.copy(alpha = alpha),
                        radius = radius,
                        center = center,
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}

internal fun pingColor(ms: Int): Color = when {
    ms < 100 -> Color(0xFF00E676)
    ms < 300 -> Color(0xFFFFB300)
    else     -> Color(0xFFFF3D00)
}
