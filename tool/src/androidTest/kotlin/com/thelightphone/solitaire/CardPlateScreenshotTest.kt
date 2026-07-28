package com.thelightphone.solitaire

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Renders the card components at their real Light Phone III size and writes PNGs
 * to the app's external files directory, where CI pulls them from.
 *
 * This does not launch the tool. A LightOS tool normally needs the LightOS server
 * app to start, which on an emulator means installing it into /system/priv-app.
 * Compose's test rule renders the composables directly instead, so this runs on a
 * plain AOSP image with no system-app setup at all.
 *
 * `captureToImage` reads the rendered node, not the screen, so the Light Phone
 * grayscale filter does not apply. That costs nothing here: the deck is already
 * black and white by design.
 */
class CardPlateScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun cardPlateLight() {
        capture("cards-light", foreground = Color.Black, background = Color.White)
    }

    @Test
    fun cardPlateDark() {
        capture("cards-dark", foreground = Color.White, background = Color.Black)
    }

    private fun capture(name: String, foreground: Color, background: Color) {
        compose.setContent {
            CardPlate(foreground = foreground, background = background)
        }
        compose.waitForIdle()

        val bitmap = compose.onNodeWithTag(PLATE).captureToImage().asAndroidBitmap()
        write(bitmap, name)
    }

    private fun write(bitmap: Bitmap, name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        val file = File(dir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // The runner log gives CI the exact path if the pull ever needs debugging.
        println("SCREENSHOT $file (${bitmap.width}x${bitmap.height})")
    }

    private companion object {
        const val PLATE = "plate"

        /**
         * The real card size on an LP3. `geometryFor` computes
         * (411dp - 6dp*2 - 4dp*6) / 7 = 53.6dp wide, and 1.42 times that tall.
         * Hardcoded here so a layout change shows up as a screenshot diff.
         */
        val CARD_W: Dp = 53.6.dp
        val CARD_H: Dp = CARD_W * 1.42f
    }

    @Composable
    private fun CardPlate(foreground: Color, background: Color) {
        val subdued = foreground.copy(alpha = 0.45f)

        Column(
            modifier = Modifier
                .testTag(PLATE)
                .background(background)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Filled suits read as one color, outlined suits as the other. This is
            // the whole trick that makes the alternating-color rule legible at 1 bit.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (suit in Suit.entries) {
                    CardView(
                        card = Card(rank = 1, suit = suit),
                        width = CARD_W,
                        height = CARD_H,
                        foreground = foreground,
                        background = background,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (suit in Suit.entries) {
                    CardView(
                        card = Card(rank = 13, suit = suit),
                        width = CARD_W,
                        height = CARD_H,
                        foreground = foreground,
                        background = background,
                    )
                }
            }

            // A ten, a face-down back, and the empty stock with its redeal ring.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CardView(
                    card = Card(rank = 10, suit = Suit.DIAMONDS),
                    width = CARD_W,
                    height = CARD_H,
                    foreground = foreground,
                    background = background,
                )
                CardBack(CARD_W, CARD_H, foreground, background)
                EmptySlot(CARD_W, CARD_H, subdued)
                EmptySlot(CARD_W, CARD_H, subdued) {
                    Box(Modifier.size(CARD_W * 0.30f).border(1.dp, subdued, CircleShape))
                }
            }
        }
    }
}
