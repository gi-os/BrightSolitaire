package com.thelightphone.solitaire

import com.thelightphone.solitaire.Victory.step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The waterfall runs on a frame clock with no way out except finishing, so
 * "it always finishes" is the property that matters most here.
 */
class VictoryTest {

    /** Roughly an LP3: 411 x 428 dp of board with a 53 dp card. */
    private val board = CascadeBoard(
        width = 411f,
        height = 428f,
        cardWidth = 53f,
        cardHeight = 75f,
        foundationX = listOf(177f, 234f, 291f, 348f),
        foundationY = 0f,
    )

    private fun wonGame() = Game(
        stock = emptyList(),
        waste = emptyList(),
        foundations = Suit.entries.map { suit -> (1..13).map { Card(it, suit) } },
        tableau = List(7) { emptyList() },
    )

    @Test
    fun `every card is queued, highest first`() {
        val cascade = Victory.start(wonGame())
        assertEquals(52, cascade.waiting.size)
        assertTrue(cascade.waiting.take(4).all { it.card.rank == 13 }, "kings leave first")
        assertTrue(cascade.waiting.takeLast(4).all { it.card.rank == 1 }, "aces leave last")
        assertEquals(52, cascade.waiting.map { it.card }.toSet().size, "no card queued twice")
        assertTrue(cascade.flying.isEmpty())
        assertFalse(cascade.finished)
    }

    @Test
    fun `it always finishes, at any frame rate`() {
        for (fps in listOf(15, 30, 60, 90, 120)) {
            val dt = 1f / fps
            var cascade = Victory.start(wonGame())
            var frames = 0
            while (!cascade.finished) {
                cascade = cascade.step(dt, board)
                assertTrue(
                    ++frames < fps * 60,
                    "at ${fps}fps it was still going after a minute of frames",
                )
            }
            assertTrue(
                cascade.elapsed < Victory.PATIENCE,
                "at ${fps}fps it took ${cascade.elapsed}s, longer than the patience limit",
            )
        }
    }

    @Test
    fun `every card launches exactly once and every foundation empties`() {
        var cascade = Victory.start(wonGame())
        val seen = ArrayList<Card>()
        var previous = cascade.flying.size
        while (!cascade.finished) {
            val next = cascade.step(1f / 60f, board)
            // A card is new to the air if the queue got shorter.
            if (next.waiting.size < cascade.waiting.size) {
                seen += cascade.waiting.first().card
            }
            previous = next.flying.size
            cascade = next
        }
        assertEquals(52, seen.size)
        assertEquals(52, seen.toSet().size, "a card launched twice")
        assertEquals(List(Game.FOUNDATIONS) { 13 }, cascade.launched)
        assertEquals(0, previous)
    }

    @Test
    fun `cards stay on the board and bounce off the bottom`() {
        var cascade = Victory.start(wonGame())
        val floor = board.height - board.cardHeight
        var bounced = false
        var wentUp = false

        while (!cascade.finished) {
            cascade = cascade.step(1f / 60f, board)
            for (falling in cascade.flying) {
                assertTrue(falling.x.isFinite() && falling.y.isFinite(), "a card went to infinity")
                assertTrue(falling.y <= floor + 0.01f, "a card fell through the bottom")
                if (falling.y >= floor - 0.01f) bounced = true
                if (falling.velocityY < 0f) wentUp = true
            }
        }
        assertTrue(bounced, "nothing ever reached the bottom")
        assertTrue(wentUp, "nothing ever travelled upward")
    }

    @Test
    fun `cards leave in both directions`() {
        var cascade = Victory.start(wonGame())
        var leftward = false
        var rightward = false
        while (!cascade.finished) {
            cascade = cascade.step(1f / 60f, board)
            for (falling in cascade.flying) {
                if (falling.velocityX < 0f) leftward = true
                if (falling.velocityX > 0f) rightward = true
            }
        }
        assertTrue(leftward && rightward, "the cards all went the same way")
    }

    @Test
    fun `a silly board does not hang it`() {
        val awkward = CascadeBoard(
            width = 1f,
            height = 1f,
            cardWidth = 1f,
            cardHeight = 1f,
            foundationX = emptyList(),
            foundationY = 0f,
        )
        var cascade = Victory.start(wonGame())
        var frames = 0
        while (!cascade.finished) {
            cascade = cascade.step(1f / 60f, awkward)
            assertTrue(++frames < 5_000, "a one dp board would not finish")
        }
    }

    @Test
    fun `a half finished game still cascades what it has`() {
        val partial = Game(
            stock = emptyList(),
            waste = emptyList(),
            foundations = listOf(
                (1..13).map { Card(it, Suit.SPADES) },
                (1..5).map { Card(it, Suit.HEARTS) },
                emptyList(),
                (1..2).map { Card(it, Suit.CLUBS) },
            ),
            tableau = List(7) { emptyList() },
        )
        var cascade = Victory.start(partial)
        assertEquals(20, cascade.waiting.size)
        var frames = 0
        while (!cascade.finished) {
            cascade = cascade.step(1f / 60f, board)
            assertTrue(++frames < 5_000)
        }
        assertEquals(listOf(13, 5, 0, 2), cascade.launched)
    }
}
