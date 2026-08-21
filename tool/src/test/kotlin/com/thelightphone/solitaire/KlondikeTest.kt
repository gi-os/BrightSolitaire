package com.thelightphone.solitaire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules engine has no Android dependency on purpose, so all of this runs on
 * the JVM in a plain unit test. CI runs it before it will build an APK.
 */
class KlondikeTest {

    // ------------------------------------------------------------ the deal

    @Test
    fun `every deal is a legal klondike layout`() {
        for (seed in 1L..200L) {
            val game = Game.deal(Variant.KLONDIKE, seed)
            game.assertInvariants("deal $seed")
            assertEquals(24, game.stock.size, "seed $seed stock")
            assertEquals(28, game.tableau.sumOf { it.size }, "seed $seed tableau")
            assertEquals((1..7).toList(), game.tableau.map { it.size }, "seed $seed columns")
            assertTrue(game.tableau.all { column -> column.count { it.faceUp } == 1 })
            assertTrue(game.tableau.all { it.last().faceUp })
            assertTrue(game.waste.isEmpty() && game.foundations.all { it.isEmpty() })
            assertEquals(0, game.moves)
        }
    }

    @Test
    fun `the same seed always deals the same game`() {
        assertEquals(Game.deal(Variant.KLONDIKE, 42), Game.deal(Variant.KLONDIKE, 42))
        assertFalse(Game.deal(Variant.KLONDIKE, 42) == Game.deal(Variant.KLONDIKE, 43))
    }

    // ------------------------------------------------------------ the stock

    @Test
    fun `drawing through the stock and redealing preserves every card`() {
        var game = Game.deal(Variant.KLONDIKE, 7)
        val original = game.stock.toSet()
        repeat(24) { game = assertNotNull(game.draw()) }

        assertEquals(original, game.waste.toSet())
        assertTrue(game.stock.isEmpty())

        val recycled = assertNotNull(game.draw())
        assertEquals(24, recycled.stock.size)
        assertTrue(recycled.waste.isEmpty())
        assertEquals(game.waste.reversed(), recycled.stock, "redeal keeps the order")
    }

    @Test
    fun `an empty stock and waste is not a move`() {
        val empty = Game(emptyList(), emptyList(), List(4) { emptyList() }, List(7) { emptyList() })
        assertNull(empty.draw())
    }

    // ------------------------------------------------------------ placement

    @Test
    fun `foundations take an ace then the same suit in order`() {
        val game = fixture()
        assertTrue(game.acceptsOnFoundation(0, Card(1, Suit.SPADES)))
        assertFalse(game.acceptsOnFoundation(0, Card(2, Suit.SPADES)))
        assertTrue(game.acceptsOnFoundation(1, Card(2, Suit.HEARTS)))
        assertFalse(game.acceptsOnFoundation(1, Card(2, Suit.DIAMONDS)), "wrong suit")
        assertFalse(game.acceptsOnFoundation(1, Card(3, Suit.HEARTS)), "skipped a rank")
    }

    @Test
    fun `columns take a king when empty and alternating colors otherwise`() {
        val game = fixture()
        assertTrue(game.acceptsOnTableau(0, Card(13, Suit.HEARTS)))
        assertFalse(game.acceptsOnTableau(0, Card(12, Suit.HEARTS)), "only a king on an empty column")
        assertTrue(game.acceptsOnTableau(1, Card(12, Suit.HEARTS)))
        assertFalse(game.acceptsOnTableau(1, Card(12, Suit.SPADES)), "same color")
        assertFalse(game.acceptsOnTableau(1, Card(11, Suit.HEARTS)), "wrong rank")
        assertFalse(game.acceptsOnTableau(3, Card(6, Suit.HEARTS)), "cannot land on a face down card")
    }

    @Test
    fun `illegal moves return null and legal ones count`() {
        val game = fixture()
        assertEquals(
            listOf(Card(1, Suit.SPADES)),
            assertNotNull(game.move(Pile.Waste, 0, Pile.Foundation(0))).foundations[0],
        )
        assertNull(game.move(Pile.Waste, 0, Pile.Foundation(1)), "hearts foundation wants a two of hearts")
        assertNull(game.move(Pile.Stock, 0, Pile.Tableau(0)), "the stock is not a source")
        assertNull(game.move(Pile.Tableau(1), 0, Pile.Tableau(1)), "a pile is not its own target")
        assertEquals(game.moves + 1, assertNotNull(game.move(Pile.Waste, 0, Pile.Foundation(0))).moves)
    }

    // ------------------------------------------------------------ runs

    @Test
    fun `a run must descend in alternating colors and be face up`() {
        assertTrue(Variant.KLONDIKE.canPickUp(run(Card(9, Suit.SPADES), Card(8, Suit.HEARTS), Card(7, Suit.CLUBS))))
        assertFalse(Variant.KLONDIKE.canPickUp(run(Card(9, Suit.SPADES), Card(8, Suit.CLUBS))), "same color")
        assertFalse(Variant.KLONDIKE.canPickUp(run(Card(9, Suit.SPADES), Card(7, Suit.HEARTS))), "rank skip")
        assertFalse(Variant.KLONDIKE.canPickUp(listOf(TableauCard(Card(9, Suit.SPADES), faceUp = false))))
        assertFalse(Variant.KLONDIKE.canPickUp(emptyList()))
    }

    @Test
    fun `a run moves as one unit and never onto a foundation`() {
        val game = Game(
            stock = emptyList(),
            waste = emptyList(),
            foundations = List(4) { emptyList() },
            tableau = listOf(
                run(Card(9, Suit.SPADES), Card(8, Suit.HEARTS), Card(7, Suit.CLUBS)),
                run(Card(10, Suit.DIAMONDS)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            ),
        )
        assertTrue(game.isDraggable(Pile.Tableau(0), 0))
        assertTrue(game.isDraggable(Pile.Tableau(0), 1), "you can pick up mid run")
        assertFalse(game.isDraggable(Pile.Stock, 0))
        assertEquals(
            listOf(Card(8, Suit.HEARTS), Card(7, Suit.CLUBS)),
            game.cardsAt(Pile.Tableau(0), 1),
        )

        val moved = assertNotNull(game.move(Pile.Tableau(0), 0, Pile.Tableau(1)))
        assertEquals(4, moved.tableau[1].size)
        assertTrue(moved.tableau[0].isEmpty())
        assertNull(game.move(Pile.Tableau(0), 0, Pile.Foundation(0)))
    }

    // ------------------------------------------------------------ tapping

    @Test
    fun `taking the last card off a column turns over the one underneath`() {
        val game = Game(
            stock = emptyList(),
            waste = emptyList(),
            foundations = List(4) { emptyList() },
            tableau = listOf(
                listOf(TableauCard(Card(5, Suit.CLUBS), false), TableauCard(Card(1, Suit.SPADES), true)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            ),
        )
        val after = assertNotNull(game.autoMove(Pile.Tableau(0), 1))
        assertTrue(after.foundations.any { it.isNotEmpty() }, "the ace goes home")
        assertTrue(after.tableau[0].single().faceUp, "the five turns over")
        assertNull(game.autoMove(Pile.Tableau(0), 0), "a buried face down card does nothing")
    }

    @Test
    fun `a tap prefers the foundation and refuses pointless moves`() {
        val game = Game(
            stock = emptyList(),
            waste = listOf(Card(2, Suit.HEARTS)),
            foundations = listOf(listOf(Card(1, Suit.HEARTS)), emptyList(), emptyList(), emptyList()),
            tableau = listOf(run(Card(3, Suit.SPADES)), emptyList(), emptyList(), emptyList(),
                emptyList(), emptyList(), emptyList()),
        )
        assertEquals(2, assertNotNull(game.autoMove(Pile.Waste, 0)).foundations[0].size)
        assertNull(game.autoMove(Pile.Foundation(0), 0), "tapping a foundation never unstacks it")

        val kingOnly = Game(
            stock = emptyList(),
            waste = emptyList(),
            foundations = List(4) { emptyList() },
            tableau = listOf(run(Card(13, Suit.SPADES)), emptyList(), emptyList(), emptyList(),
                emptyList(), emptyList(), emptyList()),
        )
        assertNull(kingOnly.autoMove(Pile.Tableau(0), 0), "a lone king stays put")
        assertNotNull(kingOnly.move(Pile.Tableau(0), 0, Pile.Tableau(1)), "but you can still drag it")
    }

    // ------------------------------------------------------------ winning

    @Test
    fun `a full set of foundations is a win`() {
        val won = Game(
            stock = emptyList(),
            waste = emptyList(),
            foundations = Suit.entries.map { suit -> (1..13).map { Card(it, suit) } },
            tableau = List(7) { emptyList() },
        )
        assertTrue(won.isWon)
        assertFalse(Game.deal(Variant.KLONDIKE, 1).isWon)
    }

    // ------------------------------------------------------------ playouts

    @Test
    fun `two hundred deals stay consistent through a full greedy playout`() {
        var wins = 0
        for (seed in 1L..200L) {
            val game = playOut(seed)
            game.assertInvariants("playout $seed")
            if (game.isWon) wins++
        }
        // No lookahead, so most deals are lost. Zero wins would mean something is stuck.
        assertTrue(wins > 0, "greedy play won nothing across 200 deals")
    }

    // ------------------------------------------------------------ helpers

    private fun fixture() = Game(
        stock = emptyList(),
        waste = listOf(Card(1, Suit.SPADES)),
        foundations = listOf(emptyList(), listOf(Card(1, Suit.HEARTS)), emptyList(), emptyList()),
        tableau = listOf(
            emptyList(),
            run(Card(13, Suit.CLUBS)),
            run(Card(7, Suit.HEARTS)),
            listOf(TableauCard(Card(7, Suit.CLUBS), faceUp = false)),
            emptyList(), emptyList(), emptyList(),
        ),
    )

    private fun run(vararg cards: Card) = cards.map { TableauCard(it, faceUp = true) }

    private fun Game.assertInvariants(label: String) {
        val all = stock + waste + foundations.flatten() + tableau.flatten().map { it.card }
        assertEquals(52, all.size, "$label: card count")
        assertEquals(52, all.toSet().size, "$label: duplicate cards")

        foundations.forEachIndexed { i, pile ->
            assertTrue(pile.map { it.suit }.toSet().size <= 1, "$label: foundation $i mixes suits")
            assertTrue(
                pile.withIndex().all { it.value.rank == it.index + 1 },
                "$label: foundation $i is out of order",
            )
        }
        tableau.forEachIndexed { i, column ->
            val firstUp = column.indexOfFirst { it.faceUp }
            if (firstUp != -1) {
                assertTrue(
                    column.drop(firstUp).all { it.faceUp },
                    "$label: column $i hides a card above a face up one",
                )
                assertTrue(Variant.KLONDIKE.canPickUp(column.drop(firstUp)), "$label: column $i is not a legal run")
            }
        }
    }

    /**
     * Plays a deal with a plain greedy policy: turn cards over, bank what the
     * foundations take, shift a run only when it uncovers something, then draw.
     * Every state is remembered, so going in a circle ends the game.
     */
    private fun playOut(seed: Long): Game {
        var game = Game.deal(Variant.KLONDIKE, seed)
        val seen = HashSet<Game>()
        seen.add(game)
        var banked = 0
        var sinceProgress = 0

        repeat(20_000) {
            if (game.isWon) return game
            val next = greedyStep(game, seen) ?: return game
            game = next
            seen.add(game)
            game.assertInvariants("playout $seed")

            val onFoundations = game.foundations.sumOf { it.size }
            if (onFoundations > banked) {
                banked = onFoundations
                sinceProgress = 0
            } else if (++sinceProgress > 250) {
                return game
            }
        }
        return game
    }

    private fun greedyStep(game: Game, seen: Set<Game>): Game? {
        for (col in 0 until Game.COLUMNS) {
            game.flipTop(col)?.let { return it }
        }
        for (col in 0 until Game.COLUMNS) {
            val column = game.tableau[col]
            val top = column.lastOrNull() ?: continue
            if (!top.faceUp) continue
            for (f in 0 until Game.FOUNDATIONS) {
                if (game.acceptsOnFoundation(f, top.card)) {
                    return game.move(Pile.Tableau(col), column.lastIndex, Pile.Foundation(f))
                }
            }
        }
        game.waste.lastOrNull()?.let { card ->
            for (f in 0 until Game.FOUNDATIONS) {
                if (game.acceptsOnFoundation(f, card)) {
                    return game.move(Pile.Waste, game.waste.lastIndex, Pile.Foundation(f))
                }
            }
        }
        for (col in 0 until Game.COLUMNS) {
            val column = game.tableau[col]
            val firstUp = column.indexOfFirst { it.faceUp }
            if (firstUp <= 0) continue
            for (dest in 0 until Game.COLUMNS) {
                if (dest == col) continue
                if (!game.acceptsOnTableau(dest, column[firstUp].card)) continue
                val next = game.move(Pile.Tableau(col), firstUp, Pile.Tableau(dest)) ?: continue
                if (next !in seen) return next
            }
        }
        game.waste.lastOrNull()?.let { card ->
            for (dest in 0 until Game.COLUMNS) {
                if (!game.acceptsOnTableau(dest, card)) continue
                val next = game.move(Pile.Waste, game.waste.lastIndex, Pile.Tableau(dest)) ?: continue
                if (next !in seen) return next
            }
        }
        val drawn = game.draw()
        return if (drawn != null && drawn !in seen) drawn else null
    }
}
