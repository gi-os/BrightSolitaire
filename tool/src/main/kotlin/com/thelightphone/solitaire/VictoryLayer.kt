package com.thelightphone.solitaire

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.thelightphone.solitaire.Victory.step
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas

/**
 * The waterfall.
 *
 * The cards do not get composables. Every frame draws them where they currently
 * are straight into an offscreen bitmap and never clears it, so the paint builds
 * up into the ribbons that make the effect. That also keeps it cheap: the cost
 * per frame is the few cards in the air, not every position they have held.
 */
@Composable
fun VictoryWaterfall(
    game: Game,
    cardWidth: Dp,
    cardHeight: Dp,
    foundationX: List<Dp>,
    foundationY: Dp,
    boardWidth: Dp,
    boardHeight: Dp,
    foreground: Color,
    background: Color,
    onLaunchedChange: (List<Int>) -> Unit,
    onFinished: () -> Unit,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

    val widthPx = with(density) { boardWidth.roundToPx() }.coerceAtLeast(1)
    val heightPx = with(density) { boardHeight.roundToPx() }.coerceAtLeast(1)

    val trail = remember(widthPx, heightPx) { ImageBitmap(widthPx, heightPx) }
    val trailCanvas = remember(trail) { GraphicsCanvas(trail) }
    val painter = remember { CanvasDrawScope() }
    val canvasSize = Size(widthPx.toFloat(), heightPx.toFloat())

    var cascade by remember { mutableStateOf(Victory.start(game)) }

    val board = remember(boardWidth, boardHeight, cardWidth, cardHeight, foundationY) {
        CascadeBoard(
            width = boardWidth.value,
            height = boardHeight.value,
            cardWidth = cardWidth.value,
            cardHeight = cardHeight.value,
            foundationX = foundationX.map { it.value },
            foundationY = foundationY.value,
        )
    }

    LaunchedEffect(board) {
        var previous = withFrameNanos { it }
        var reported = cascade.launched

        while (true) {
            val now = withFrameNanos { it }
            // Cap the step so one slow frame cannot drop a card through the floor.
            val dt = ((now - previous) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.05f)
            previous = now

            val next = cascade.step(dt, board)
            cascade = next

            // Only when it actually changes: this drives state in the caller, and
            // once a frame would mean recomposing the whole board 60 times a second.
            if (next.launched != reported) {
                reported = next.launched
                onLaunchedChange(next.launched)
            }

            painter.draw(density, layoutDirection, trailCanvas, canvasSize) {
                for (falling in next.flying) {
                    drawCascadeCard(
                        card = falling.card,
                        topLeft = Offset(falling.x.dp.toPx(), falling.y.dp.toPx()),
                        size = Size(cardWidth.toPx(), cardHeight.toPx()),
                        foreground = foreground,
                        background = background,
                    )
                }
            }

            if (next.finished || next.elapsed > Victory.PATIENCE) {
                onFinished()
                break
            }
        }
    }

    Canvas(Modifier.fillMaxSize()) {
        // Reading the cascade is what ties this to the frame clock.
        if (cascade.elapsed >= 0f) drawImage(trail)
    }
}

private fun DrawScope.drawCascadeCard(
    card: Card,
    topLeft: Offset,
    size: Size,
    foreground: Color,
    background: Color,
) {
    val corner = CornerRadius(size.width * 0.11f)
    drawRoundRect(background, topLeft, size, corner)
    drawRoundRect(foreground, topLeft, size, corner, style = Stroke(width = 1.dp.toPx()))

    // No rank text: nobody reads it at this speed, and the suit alone still says card.
    val glyph = size.width * 0.44f
    translate(topLeft.x + (size.width - glyph) / 2f, topLeft.y + (size.height - glyph) / 2f) {
        val path = suitPath(card.suit, Size(glyph, glyph))
        if (card.suit.isRed) {
            drawPath(path, foreground, style = Stroke(width = glyph * 0.12f))
        } else {
            drawPath(path, foreground)
        }
    }
}
