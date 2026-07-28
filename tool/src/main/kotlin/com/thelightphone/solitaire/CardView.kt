package com.thelightphone.solitaire

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

/**
 * Everything on this screen is one bit deep, so suit color is carried by shape
 * instead of hue: black suits are filled, red suits are drawn as outlines.
 */

@Composable
fun SuitGlyph(suit: Suit, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val path = suitPath(suit, size)
        if (suit.isRed) {
            drawPath(path, color, style = Stroke(width = size.minDimension * 0.12f))
        } else {
            drawPath(path, color)
        }
    }
}

@Composable
fun CardView(
    card: Card,
    width: Dp,
    height: Dp,
    foreground: Color,
    background: Color,
    modifier: Modifier = Modifier,
    showCenterGlyph: Boolean = true,
    borderWidth: Dp = 1.dp,
) {
    val shape = RoundedCornerShape(width * 0.11f)
    Box(
        modifier
            .size(width, height)
            .clip(shape)
            .background(background)
            .border(borderWidth, foreground, shape)
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = width * 0.10f, top = height * 0.045f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = card.label,
                style = TextStyle(
                    color = foreground,
                    fontSize = (width.value * 0.30f).sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Spacer(Modifier.width(width * 0.07f))
            SuitGlyph(card.suit, foreground, Modifier.size(width * 0.19f))
        }
        if (showCenterGlyph) {
            SuitGlyph(
                suit = card.suit,
                color = foreground,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = height * 0.10f)
                    .size(width * 0.44f),
            )
        }
    }
}

@Composable
fun CardBack(
    width: Dp,
    height: Dp,
    foreground: Color,
    background: Color,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    val shape = RoundedCornerShape(width * 0.11f)
    Box(
        modifier
            .size(width, height)
            .clip(shape)
            .background(foreground)
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .size(width * 0.68f, height * 0.76f)
                .border(
                    width = if (emphasized) 3.dp else 1.dp,
                    color = background,
                    shape = RoundedCornerShape(width * 0.06f),
                )
        )
    }
}

@Composable
fun EmptySlot(
    width: Dp,
    height: Dp,
    outline: Color,
    modifier: Modifier = Modifier,
    borderWidth: Dp = 1.dp,
    content: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(width * 0.11f)
    Box(
        modifier = modifier.size(width, height).border(borderWidth, outline, shape),
        contentAlignment = Alignment.Center,
    ) {
        content?.invoke()
    }
}

// ---------------------------------------------------------------- shapes

private fun suitPath(suit: Suit, canvas: Size): Path {
    val s = min(canvas.width, canvas.height)
    val ox = (canvas.width - s) / 2f
    val oy = (canvas.height - s) / 2f
    fun x(t: Float) = ox + t * s
    fun y(t: Float) = oy + t * s

    val path = Path()
    when (suit) {
        Suit.DIAMONDS -> {
            path.moveTo(x(0.50f), y(0.03f))
            path.lineTo(x(0.90f), y(0.50f))
            path.lineTo(x(0.50f), y(0.97f))
            path.lineTo(x(0.10f), y(0.50f))
            path.close()
        }

        Suit.HEARTS -> {
            path.moveTo(x(0.50f), y(0.95f))
            path.cubicTo(x(0.50f), y(0.72f), x(0.04f), y(0.58f), x(0.04f), y(0.32f))
            path.cubicTo(x(0.04f), y(0.07f), x(0.36f), y(0.04f), x(0.50f), y(0.27f))
            path.cubicTo(x(0.64f), y(0.04f), x(0.96f), y(0.07f), x(0.96f), y(0.32f))
            path.cubicTo(x(0.96f), y(0.58f), x(0.50f), y(0.72f), x(0.50f), y(0.95f))
            path.close()
        }

        Suit.SPADES -> {
            path.moveTo(x(0.50f), y(0.04f))
            path.cubicTo(x(0.50f), y(0.27f), x(0.04f), y(0.41f), x(0.04f), y(0.66f))
            path.cubicTo(x(0.04f), y(0.85f), x(0.32f), y(0.90f), x(0.44f), y(0.78f))
            path.cubicTo(x(0.44f), y(0.88f), x(0.38f), y(0.94f), x(0.30f), y(0.97f))
            path.lineTo(x(0.70f), y(0.97f))
            path.cubicTo(x(0.62f), y(0.94f), x(0.56f), y(0.88f), x(0.56f), y(0.78f))
            path.cubicTo(x(0.68f), y(0.90f), x(0.96f), y(0.85f), x(0.96f), y(0.66f))
            path.cubicTo(x(0.96f), y(0.41f), x(0.50f), y(0.27f), x(0.50f), y(0.04f))
            path.close()
        }

        Suit.CLUBS -> {
            val r = s * 0.225f
            path.addOval(Rect(Offset(x(0.50f), y(0.26f)), r))
            path.addOval(Rect(Offset(x(0.25f), y(0.62f)), r))
            path.addOval(Rect(Offset(x(0.75f), y(0.62f)), r))
            path.moveTo(x(0.42f), y(0.58f))
            path.lineTo(x(0.33f), y(0.97f))
            path.lineTo(x(0.67f), y(0.97f))
            path.lineTo(x(0.58f), y(0.58f))
            path.close()
        }
    }
    return path
}
