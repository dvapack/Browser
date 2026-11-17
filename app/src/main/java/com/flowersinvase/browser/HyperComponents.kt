package com.flowersinvase.browser

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private class BlurBackdrop(val layer: GraphicsLayer) {
    var origin by mutableStateOf(Offset.Zero)
}

private val LocalBackdrop = staticCompositionLocalOf<BlurBackdrop?> { null }

@Composable
internal fun BlurScene(
    modifier: Modifier,
    content: @Composable () -> Unit,
    overlay: @Composable BoxScope.() -> Unit
) {
    val layer = rememberGraphicsLayer()
    val backdrop = remember(layer) {
        // Both the viewport and the blur sample the same composited WebView frame.
        layer.compositingStrategy = CompositingStrategy.Offscreen
        BlurBackdrop(layer)
    }
    CompositionLocalProvider(LocalBackdrop provides backdrop) {
        Box(modifier) {
            Box(
                Modifier.matchParentSize().onGloballyPositioned { backdrop.origin = it.positionInRoot() }
                    .drawWithContent {
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                    }
                    .background(MaterialTheme.colorScheme.background)
            ) { content() }
            overlay()
        }
    }
}

@Composable
internal fun BlurSurface(
    modifier: Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    content: @Composable () -> Unit
) {
    val backdrop = LocalBackdrop.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .1f
    val tint = if (dark) Color(0xFF191919).copy(alpha = .82f) else Color.White.copy(alpha = .75f)
    var offset by remember { mutableStateOf(Offset.Zero) }
    val radius = with(LocalDensity.current) { 12.dp.toPx() }
    val effect = remember(radius) { BlurEffect(radius, radius, TileMode.Clamp) }

    val rim = if (dark) Color.White.copy(alpha = .16f) else Color.Black.copy(alpha = .11f)
    Box(
        modifier.clip(shape).border(1.dp, rim, shape)
            .onGloballyPositioned { offset = it.positionInRoot() }
            .pointerInput(Unit) { detectTapGestures(onTap = {}) }
    ) {
        if (backdrop != null) {
            Canvas(Modifier.matchParentSize().graphicsLayer { renderEffect = effect; clip = true }) {
                clipRect {
                    translate(backdrop.origin.x - offset.x, backdrop.origin.y - offset.y) {
                        drawLayer(backdrop.layer)
                    }
                }
            }
        }
        Box(Modifier.matchParentSize().background(tint))
        content()
    }
}

internal enum class Glyph { Left, Right, Plus, Close, Search, Tab, Copy, Check, Reload, Submit, Menu, Tabs }

@Composable
internal fun BrowserIconButton(
    glyph: Glyph, label: String, action: () -> Unit, size: Int = 24,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    IconButton(action, modifier.size(48.dp).semantics { contentDescription = label }, enabled = enabled) {
        BrowserIcon(glyph, tint, size)
    }
}

@Composable
internal fun BrowserIcon(glyph: Glyph, color: Color, size: Int) {
    Canvas(Modifier.size(size.dp)) {
        val scale = this.size.width / 24f
        fun point(x: Float, y: Float) = Offset(x * scale, y * scale)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float = 1.8f) =
            drawLine(color, point(x1, y1), point(x2, y2), width * scale, StrokeCap.Round)
        fun rect(x: Float, y: Float, width: Float, height: Float) = drawRoundRect(
            color, point(x, y), Size(width * scale, height * scale), CornerRadius(3f * scale),
            style = Stroke(1.8f * scale)
        )
        when (glyph) {
            Glyph.Left -> {
                line(15f, 3f, 6f, 12f, 1.6f)
                line(6f, 12f, 15f, 21f, 1.6f)
            }
            Glyph.Right -> {
                line(9f, 5f, 16f, 12f)
                line(16f, 12f, 9f, 19f)
            }
            Glyph.Submit -> {
                line(4f, 12f, 20f, 12f)
                line(14f, 6f, 20f, 12f)
                line(20f, 12f, 14f, 18f)
            }
            Glyph.Plus -> {
                line(12f, 3f, 12f, 21f, 1.5f)
                line(3f, 12f, 21f, 12f, 1.5f)
            }
            Glyph.Close -> {
                line(6f, 6f, 18f, 18f)
                line(18f, 6f, 6f, 18f)
            }
            Glyph.Search -> {
                drawCircle(color, 7f * scale, point(10f, 10f), style = Stroke(1.5f * scale))
                line(15f, 15f, 21f, 21f, 1.5f)
            }
            Glyph.Menu -> {
                line(4f, 6f, 20f, 6f)
                line(4f, 12f, 17f, 12f)
                line(4f, 18f, 13f, 18f)
            }
            Glyph.Tab -> rect(3f, 3f, 18f, 18f)
            Glyph.Tabs -> {
                val rear = Path().apply {
                    moveTo(3f * scale, 16f * scale)
                    lineTo(3f * scale, 6f * scale)
                    quadraticTo(3f * scale, 3f * scale, 6f * scale, 3f * scale)
                    lineTo(16f * scale, 3f * scale)
                }
                drawPath(rear, color, style = Stroke(1.8f * scale, cap = StrokeCap.Round))
                rect(8f, 8f, 13f, 13f)
            }
            Glyph.Copy -> {
                rect(8f, 8f, 12f, 13f)
                val corner = Path().apply {
                    moveTo(4f * scale, 15f * scale)
                    lineTo(4f * scale, 7f * scale)
                    quadraticTo(4f * scale, 3f * scale, 8f * scale, 3f * scale)
                    lineTo(15f * scale, 3f * scale)
                }
                drawPath(corner, color, style = Stroke(1.8f * scale, cap = StrokeCap.Round))
            }
            Glyph.Check -> {
                line(5f, 12f, 10f, 17f)
                line(10f, 17f, 19f, 7f)
            }
            Glyph.Reload -> {
                drawArc(color, 45f, 280f, false, point(4f, 4f), Size(16f * scale, 16f * scale),
                    style = Stroke(1.8f * scale, cap = StrokeCap.Round))
                line(20f, 4f, 20f, 8f)
                line(20f, 8f, 16f, 8f)
            }
        }
    }
}
