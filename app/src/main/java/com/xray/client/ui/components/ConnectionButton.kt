package com.xray.client.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

enum class ConnectionState { Idle, Connecting, Connected }

private val IdleColor       = Color(0xFF4A4A4A)
private val ConnectingColor = Color(0xFFFF8A1F)
private val ConnectedColor  = Color(0xFF00E676)

@Composable
fun ConnectionButton(
    state: ConnectionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val baseColor by animateColorAsState(
        targetValue = when (state) {
            ConnectionState.Idle       -> IdleColor
            ConnectionState.Connecting -> ConnectingColor
            ConnectionState.Connected  -> ConnectedColor
        },
        animationSpec = tween(durationMillis = 500),
        label = "baseColor",
    )

    val infinite = rememberInfiniteTransition(label = "ConnectionButton")

    val pulseAlpha by infinite.animateFloat(
        initialValue = 0.55f,
        targetValue  = 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    val heartbeat by infinite.animateFloat(
        initialValue = 1f,
        targetValue  = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1200
                1.00f at    0
                1.22f at  120
                1.00f at  240
                1.16f at  360
                1.00f at  500
                1.00f at 1200
            },
        ),
        label = "heartbeat",
    )

    val iconScale by animateFloatAsState(
        targetValue = when (state) {
            ConnectionState.Idle       -> 0.85f
            ConnectionState.Connecting -> 0.95f
            ConnectionState.Connected  -> 1.10f
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "iconScale",
    )

    Box(
        modifier = modifier
            .size(220.dp)
            .drawBehind {
                if (state == ConnectionState.Connected) {
                    val r = (size.minDimension / 2.1f) * heartbeat
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                ConnectedColor.copy(alpha = 0.45f),
                                ConnectedColor.copy(alpha = 0.10f),
                                Color.Transparent,
                            ),
                            center = center,
                            radius = r,
                        ),
                        radius = r,
                        center = center,
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(160.dp)
                .alpha(if (state == ConnectionState.Connecting) pulseAlpha else 1f)
                .clip(CircleShape)
                .background(baseColor)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            PowerIcon(
                modifier = Modifier
                    .size(72.dp)
                    .scale(iconScale),
            )
        }
    }
}

@Composable
private fun PowerIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke  = size.minDimension * 0.10f
        val inset   = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)

        drawArc(
            color      = Color.White,
            startAngle = -60f,
            sweepAngle = 300f,
            useCenter  = false,
            topLeft    = Offset(inset, inset),
            size       = arcSize,
            style      = Stroke(width = stroke, cap = StrokeCap.Round),
        )

        drawLine(
            color       = Color.White,
            start       = Offset(size.width / 2f, stroke * 0.4f),
            end         = Offset(size.width / 2f, size.height * 0.45f),
            strokeWidth = stroke,
            cap         = StrokeCap.Round,
        )
    }
}
