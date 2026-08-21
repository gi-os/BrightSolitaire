package com.thelightphone.solitaire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The finish button's whole promise is that it does not start something it
 * cannot end, so most of this replays the line it hands back and insists the
 * board is won at the bottom of it.
 */
class FinishTest {

    /** The cap in Finish.kt. Nothing may come back longer than this. */
    private val maxMoves = 220

    @Test
    fun `a won board is already finished`() {
        assertEquals(emptyList(), won().finishingLine())
    }

    @Test
    fun `one card short, and it plays that card`() {
        val game = won().copy(
            foundations = won().foundations.mapIndexed { i, pile -> if (i == 0) pile.dropLast(1) else pile },
            tableau = List(Game.COLUMNS) { i ->
                if (i == 0) listOf(TableauCard(Card(13, Suit.SPADES), faceUp = true)) else emptyList()
            },
        )
        val line = assertNotNull(game.finishingLine())
        assertEquals(listOf(Action.Shift(Pile.Tableau(0), 0, Pile.Foundation(0))), line)
    }

    @Test
    fun `a revealed klondike board is always finished, and the line wins`() {
        var longest = 0
        for (seed in 1L..60L) {
            val game = revealedKlondike(seed)
            assertTrue(game.isFullyRevealed, "seed $seed is not a revealed board")
            val line = assertNotNull(game.finishingLine(), "seed $seed found no finish")
            longest = maxOf(longest, line.size)
            game.assertLineWins(line, "klondike $seed")
        }
        assertTrue(longest <= maxMoves, "a line of $longest is longer than the cap")
    }

    @Test
    fun `a revealed yukon board is always finished, and the line wins`() {
        for (seed in 1L..40L) {
            val game = Game.deal(Variant.YUKON, seed).allFaceUp()
            val line = assertNotNull(game.finishingLine(), "seed $seed found no finish")
            assertTrue(line.size <= maxMoves, "seed $seed: ${line.size} moves")
            game.assertLineWins(line, "yukon $seed")
        }
    }

    @Test
    fun `a finish never unstacks a foundation and never draws in yukon`() {
        for (seed in 1L..40L) {
            val game = Game.deal(Variant.YUKON, seed).allFaceUp()
            for (action in assertNotNull(game.finishingLine())) {
                assertTrue(action != Action.Draw, "seed $seed drew from a stock Yukon does not have")
                val shift = action as? Action.Shift ?: continue
                assertTrue(shift.source !is Pile.Foundation, "seed $seed took a card back off a foundation")
            }
        }
    }

    @Test
    fun `a board with nowhere to go is not offered a finish`() {
        val stuck = Game(
            stock = emptyList(),
            waste = emptyList(),
            foundations = List(Game.FOUNDATIONS) { emptyList() },
            tableau = listOf(
                listOf(Card(5, Suit.SPADES), Card(9, Suit.CLUBS)).faceUp(),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            ),
            variant = Variant.YUKON,
        )
        assertNull(stuck.finishingLine())
    }

    @Test
    fun `a fresh deal is not fully revealed and is not offered a finish`() {
        for (variant in Variant.entries) {
            val game = Game.deal(variant, 1)
            assertTrue(!game.isFullyRevealed, "$variant deals every card face up")
        }
    }

    @Test
    fun `revealed says exactly what it means`() {
        val hidden = Game.deal(Variant.KLONDIKE, 1)
        assertTrue(!hidden.isFullyRevealed)
        assertTrue(hidden.allFaceUp().isFullyRevealed)
        assertTrue(won().isFullyRevealed, "an empty table hides nothing")
    }

    @Test
    fun `a tight budget gives up rather than answering wrongly`() {
        val game = Game.deal(Variant.YUKON, 1).allFaceUp()
        // Whatever comes back on a budget this small, it still has to be a win.
        game.finishingLine(budget = 50)?.let { game.assertLineWins(it, "tight budget") }
    }

    // ------------------------------------------------------------ helpers

    private fun Game.assertLineWins(line: List<Action>, label: String) {
        var game = this
        line.forEachIndexed { i, action ->
            game = assertNotNull(game.perform(action), "$label: move $i ($action) is not legal")
        }
        assertTrue(game.isWon, "$label: ${line.size} moves and the board is not won")
    }

    /**
     * A Klondike board with every card turned over: seven descending, alternating
     * columns and whatever would not fit into one still in the stock. Above its
     * face down cards a Klondike column is always a run, so a board with no face
     * down cards left is seven runs — which is what makes this shape the one the
     * finish button actually meets, and the round robin deal it is tempting to
     * write instead a position no game of Klondike can reach.
     */
    private fun revealedKlondike(seed: Long): Game {
        val unused = shuffledDeck(seed).toMutableList()
        val columns = ArrayList<List<TableauCard>>(Game.COLUMNS)
        repeat(Game.COLUMNS) {
            var head = unused.removeFirstOrNull() ?: return@repeat
            val column = arrayListOf(TableauCard(head, faceUp = true))
            while (true) {
                val next = unused.firstOrNull { it.rank == head.rank - 1 && it.isRed != head.isRed } ?: break
                unused.remove(next)
                column += TableauCard(next, faceUp = true)
                head = next
            }
            columns.add(column)
        }
        while (columns.size < Game.COLUMNS) columns.add(emptyList())
        return Game(
            stock = unused.toList(),
            waste = emptyList(),
            foundations = List(Game.FOUNDATIONS) { emptyList() },
            tableau = columns,
        )
    }

    private fun won() = Game(
        stock = emptyList(),
        waste = emptyList(),
        foundations = Suit.entries.map { suit -> (1..13).map { Card(it, suit) } },
        tableau = List(Game.COLUMNS) { emptyList() },
    )

    private fun Game.allFaceUp(): Game =
        copy(tableau = tableau.map { column -> column.map { it.copy(faceUp = true) } })

    private fun List<Card>.faceUp() = map { TableauCard(it, faceUp = true) }
}
