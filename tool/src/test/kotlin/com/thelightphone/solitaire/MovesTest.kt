package com.thelightphone.solitaire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MovesTest {

    @Test
    fun `every generated action can actually be performed`() {
        for (seed in 1L..60L) {
            var game = Game.deal(seed)
            repeat(40) {
                val actions = game.legalActions()
                for (action in actions) {
                    assertNotNull(game.perform(action), "seed $seed offered an illegal $action")
                }
                game = actions.firstOrNull()?.let { game.perform(it) } ?: return@repeat
            }
        }
    }

    @Test
    fun `a tap and the action it reports always agree`() {
        for (seed in 1L..40L) {
            var game = Game.deal(seed)
            repeat(60) {
                val board = game
                var advance: Game? = null

                val taps = buildList {
                    add(Pile.Stock to -1)
                    add(Pile.Waste to board.waste.lastIndex)
                    for (f in 0 until Game.FOUNDATIONS) add(Pile.Foundation(f) to board.foundations[f].lastIndex)
                    for (c in 0 until Game.COLUMNS) {
                        for (i in board.tableau[c].indices) add(Pile.Tableau(c) to i)
                    }
                }

                for ((pile, index) in taps) {
                    val action = board.autoAction(pile, index)
                    val direct = board.autoMove(pile, index)
                    if (action == null) {
                        assertNull(direct, "seed $seed reported no action for $pile but moved anyway")
                    } else {
                        assertEquals(board.perform(action), direct, "seed $seed disagreed on $action")
                        if (advance == null && pile != Pile.Stock) advance = direct
                    }
                }

                game = advance ?: board.draw() ?: return@repeat
            }
        }
    }

    @Test
    fun `the generator never offers a pure relabelling of the board`() {
        val game = Game(
            stock = emptyList(),
            waste = emptyList(),
            foundations = List(4) { emptyList() },
            tableau = listOf(
                listOf(TableauCard(Card(13, Suit.SPADES), true)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            ),
        )
        // Sliding the lone king between empty columns is the same board with two
        // columns swapped, so it must not appear.
        assertTrue(game.legalActions().isEmpty(), "offered ${game.legalActions()}")
        assertTrue(game.isDeadEnd())
    }

    @Test
    fun `hints put the foundation first and never unstack one`() {
        val game = Game(
            stock = emptyList(),
            waste = listOf(Card(2, Suit.HEARTS)),
            foundations = listOf(listOf(Card(1, Suit.HEARTS)), emptyList(), emptyList(), emptyList()),
            tableau = listOf(
                listOf(TableauCard(Card(3, Suit.SPADES), true)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            ),
        )
        val best = game.hints().first()
        assertEquals(Action.Shift(Pile.Waste, 0, Pile.Foundation(0)), best)
        assertTrue(
            game.hints().none { it is Action.Shift && it.source is Pile.Foundation },
            "a hint offered to take a card back off a foundation",
        )
    }

    @Test
    fun `hints prefer uncovering a face down card over shuffling`() {
        val game = Game(
            stock = emptyList(),
            waste = emptyList(),
            foundations = List(4) { emptyList() },
            tableau = listOf(
                // Moving this seven uncovers something.
                listOf(TableauCard(Card(9, Suit.CLUBS), false), TableauCard(Card(7, Suit.HEARTS), true)),
                // Moving this seven does not.
                listOf(TableauCard(Card(7, Suit.DIAMONDS), true)),
                listOf(TableauCard(Card(8, Suit.SPADES), true)),
                emptyList(), emptyList(), emptyList(), emptyList(),
            ),
        )
        val best = game.hints().first()
        assertEquals(Pile.Tableau(0), (best as Action.Shift).source, "should uncover the nine of clubs")
    }

    @Test
    fun `drawing is offered when nothing on the table can move`() {
        assertTrue(Game.deal(5).hints().isNotEmpty(), "a fresh deal always has a suggestion")

        val stuckButAlive = Game(
            stock = listOf(Card(1, Suit.SPADES)),
            waste = emptyList(),
            foundations = List(4) { emptyList() },
            tableau = listOf(
                listOf(TableauCard(Card(5, Suit.CLUBS), true)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            ),
        )
        assertEquals(listOf(Action.Draw), stuckButAlive.hints())
        assertFalse(stuckButAlive.isDeadEnd(), "the ace in the stock is still reachable")
    }

    @Test
    fun `a dead end is only called when the whole stock has been checked`() {
        // Every column is occupied, so no king can move. The face up cards are all
        // twos and fours, two ranks apart, so none can stack on another. No ace is
        // anywhere, so no foundation can open. The one card in the stock is a five,
        // which has no six to sit on and no empty column to start.
        val dead = Game(
            stock = listOf(Card(5, Suit.CLUBS)),
            waste = emptyList(),
            foundations = List(4) { emptyList() },
            tableau = listOf(
                listOf(TableauCard(Card(10, Suit.HEARTS), false), TableauCard(Card(2, Suit.SPADES), true)),
                listOf(TableauCard(Card(9, Suit.HEARTS), false), TableauCard(Card(2, Suit.HEARTS), true)),
                listOf(TableauCard(Card(8, Suit.HEARTS), false), TableauCard(Card(2, Suit.DIAMONDS), true)),
                listOf(TableauCard(Card(7, Suit.HEARTS), false), TableauCard(Card(2, Suit.CLUBS), true)),
                listOf(TableauCard(Card(6, Suit.HEARTS), false), TableauCard(Card(4, Suit.SPADES), true)),
                listOf(TableauCard(Card(11, Suit.HEARTS), false), TableauCard(Card(4, Suit.HEARTS), true)),
                listOf(TableauCard(Card(12, Suit.HEARTS), false), TableauCard(Card(4, Suit.DIAMONDS), true)),
            ),
        )
        assertTrue(dead.legalActions().none { it is Action.Shift }, "fixture is not actually stuck")
        assertTrue(dead.isDeadEnd(), "no ace anywhere and no column can take anything")
        assertFalse(Game.deal(1).isDeadEnd(), "a fresh deal always has something to do")
    }

    @Test
    fun `the cards an action carries are the cards that move`() {
        val game = Game(
            stock = listOf(Card(4, Suit.SPADES)),
            waste = emptyList(),
            foundations = List(4) { emptyList() },
            tableau = listOf(
                listOf(
                    TableauCard(Card(9, Suit.SPADES), true),
                    TableauCard(Card(8, Suit.HEARTS), true),
                ),
                listOf(TableauCard(Card(10, Suit.DIAMONDS), true)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            ),
        )
        val shift = Action.Shift(Pile.Tableau(0), 0, Pile.Tableau(1))
        assertEquals(listOf(Card(9, Suit.SPADES), Card(8, Suit.HEARTS)), game.cardsMovedBy(shift))
        assertEquals(listOf(Card(4, Suit.SPADES)), game.cardsMovedBy(Action.Draw))

        // A redeal moves the whole waste at once, so nothing should fly.
        val redeal = Game(
            stock = emptyList(),
            waste = listOf(Card(4, Suit.SPADES)),
            foundations = List(4) { emptyList() },
            tableau = List(7) { emptyList() },
        )
        assertTrue(redeal.cardsMovedBy(Action.Draw).isEmpty())
    }

    @Test
    fun `a pile that is receiving a card in flight does not also show it`() {
        // Columns stack, so the landed cards are dropped and what they cover shows.
        assertNull(visibleCardIndex(Pile.Tableau(2), 5, Pile.Tableau(2), 4), "the landed card")
        assertNull(visibleCardIndex(Pile.Tableau(2), 4, Pile.Tableau(2), 4), "the first landed card")
        assertEquals(3, visibleCardIndex(Pile.Tableau(2), 3, Pile.Tableau(2), 4), "the card underneath")

        // Single card piles step down instead, or they look empty mid-flight.
        assertEquals(2, visibleCardIndex(Pile.Waste, 3, Pile.Waste, 3))
        assertEquals(0, visibleCardIndex(Pile.Foundation(1), 1, Pile.Foundation(1), 1))
        assertEquals(-1, visibleCardIndex(Pile.Waste, 0, Pile.Waste, 0), "nothing underneath")

        // Piles not involved are untouched, and so is a still board.
        assertEquals(7, visibleCardIndex(Pile.Tableau(0), 7, Pile.Tableau(1), 2))
        assertEquals(7, visibleCardIndex(Pile.Tableau(0), 7, null, 0))
        assertEquals(-1, visibleCardIndex(Pile.Tableau(3), -1, null, 0), "an empty column")
    }
}
