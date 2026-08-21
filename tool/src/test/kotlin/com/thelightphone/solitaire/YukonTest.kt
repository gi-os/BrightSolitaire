package com.thelightphone.solitaire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Yukon differs from Klondike in three places and agrees everywhere else, so
 * this covers the three and then re-runs the shared machinery — moves, hints,
 * the dead end check, the solver — over Yukon boards to prove none of it
 * quietly assumed the other game.
 */
class YukonTest {

    // ------------------------------------------------------------ the deal

    @Test
    fun `every deal puts the whole pack on the table`() {
        for (seed in 1L..200L) {
            val game = Game.deal(Variant.YUKON, seed)
            game.assertYukonInvariants("deal $seed")

            assertTrue(game.stock.isEmpty(), "seed $seed deals no stock")
            assertTrue(game.waste.isEmpty(), "seed $seed deals no waste")
            assertEquals(52, game.tableau.sumOf { it.size }, "seed $seed tableau")
            assertEquals(listOf(1, 6, 7, 8, 9, 10, 11), game.tableau.map { it.size }, "seed $seed columns")
            assertEquals(
                listOf(0, 1, 2, 3, 4, 5, 6),
                game.tableau.map { column -> column.count { !it.faceUp } },
                "seed $seed face down cards",
            )
            assertTrue(game.foundations.all { it.isEmpty() })
            assertEquals(Variant.YUKON, game.variant)
            assertEquals(0, game.moves)
        }
    }

    @Test
    fun `a seed means a different deal in each game`() {
        assertEquals(Game.deal(Variant.YUKON, 42), Game.deal(Variant.YUKON, 42))
        assertFalse(Game.deal(Variant.YUKON, 42) == Game.deal(Variant.KLONDIKE, 42))
    }

    @Test
    fun `the variant survives every move`() {
        var game = Game.deal(Variant.YUKON, 5)
        repeat(30) {
            val action = game.hints().firstOrNull() ?: return@repeat
            game = game.perform(action) ?: return@repeat
            assertEquals(Variant.YUKON, game.variant)
        }
    }

    // ------------------------------------------------------------ picking up

    @Test
    fun `any face up group moves, ordered or not`() {
        val jumble = listOf(Card(4, Suit.CLUBS), Card(12, Suit.CLUBS), Card(2, Suit.HEARTS)).faceUp()
        assertTrue(Variant.YUKON.canPickUp(jumble))
        assertFalse(Variant.KLONDIKE.canPickUp(jumble), "Klondike carries runs only")

        val ordered = listOf(Card(9, Suit.SPADES), Card(8, Suit.HEARTS), Card(7, Suit.CLUBS)).faceUp()
        assertTrue(Variant.YUKON.canPickUp(ordered))
        assertTrue(Variant.KLONDIKE.canPickUp(ordered))
    }

    @Test
    fun `a face down card is never picked up, in either game`() {
        val hidden = listOf(TableauCard(Card(9, Suit.SPADES), faceUp = false))
        for (variant in Variant.entries) {
            assertFalse(variant.canPickUp(hidden), "$variant picked up a face down card")
            assertFalse(variant.canPickUp(emptyList()), "$variant picked up nothing")
        }
    }

    @Test
    fun `a jumbled group still needs somewhere legal to land`() {
        val game = fixture()
        // 4C sits over a queen and a two: nonsense in Klondike, one unit in Yukon.
        assertTrue(game.isDraggable(Pile.Tableau(1), 1))

        // The 5 of hearts is red and one higher, so the black 4 goes there.
        val moved = assertNotNull(game.move(Pile.Tableau(1), 1, Pile.Tableau(2)))
        assertEquals(3, moved.tableau[2].size - 1, "the whole group travelled")
        assertEquals(listOf(4, 12, 2), moved.tableau[2].drop(1).map { it.card.rank })

        // 4C is black, and so is the 5 of spades.
        assertNull(game.move(Pile.Tableau(1), 1, Pile.Tableau(3)), "same colour")
        // An empty column takes a king and nothing else.
        assertNull(game.move(Pile.Tableau(1), 1, Pile.Tableau(4)), "not a king")
    }

    @Test
    fun `a king and its passengers can take an empty column`() {
        val game = fixture()
        val moved = assertNotNull(game.move(Pile.Tableau(5), 0, Pile.Tableau(4)))
        assertEquals(listOf(13, 3), moved.tableau[4].map { it.card.rank })
        assertTrue(moved.tableau[5].isEmpty())
    }

    // ------------------------------------------------------------ no stock

    @Test
    fun `there is nothing to draw and nothing offers one`() {
        val game = Game.deal(Variant.YUKON, 3)
        assertNull(game.draw(), "Yukon has no stock to turn")
        assertNull(game.autoAction(Pile.Stock, 0), "and tapping where it would be is not a move")
        assertFalse(game.legalActions().contains(Action.Draw))
        assertFalse(game.hints().contains(Action.Draw))
        assertFalse(game.variant.hasStock)
    }

    @Test
    fun `a board with no move left is a dead end straight away`() {
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
        assertTrue(stuck.isDeadEnd(), "no draw to fall back on")
        assertFalse(stuck.isWon)
    }

    // ------------------------------------------------------------ shared machinery

    @Test
    fun `every action the generator offers can be performed`() {
        for (seed in 1L..40L) {
            var game = Game.deal(Variant.YUKON, seed)
            repeat(40) {
                val actions = game.legalActions()
                for (action in actions) {
                    assertNotNull(game.perform(action), "seed $seed could not perform $action")
                }
                game = game.perform(actions.firstOrNull() ?: return@repeat) ?: return@repeat
                game.assertYukonInvariants("seed $seed")
            }
        }
    }

    @Test
    fun `a tap and the action it reports always agree`() {
        for (seed in 1L..40L) {
            var game = Game.deal(Variant.YUKON, seed)
            repeat(25) {
                for (col in 0 until Game.COLUMNS) {
                    for (i in game.tableau[col].indices) {
                        val action = game.autoAction(Pile.Tableau(col), i)
                        val tapped = game.autoMove(Pile.Tableau(col), i)
                        if (action == null) {
                            assertNull(tapped, "seed $seed reported no move but made one")
                        } else {
                            assertEquals(game.perform(action), tapped, "seed $seed")
                        }
                    }
                }
                game = game.perform(game.hints().firstOrNull() ?: return@repeat) ?: return@repeat
            }
        }
    }

    @Test
    fun `two hundred deals stay consistent through a greedy playout`() {
        for (seed in 1L..200L) {
            var game = Game.deal(Variant.YUKON, seed)
            val seen = HashSet<Game>()
            repeat(300) {
                if (game.isWon) return@repeat
                val next = game.hints().firstNotNullOfOrNull { action ->
                    game.perform(action)?.takeIf { it !in seen }
                } ?: return@repeat
                game = next
                seen.add(game)
                game.assertYukonInvariants("playout $seed")
            }
        }
    }

    // ------------------------------------------------------------ helpers

    /**
     * Column 1 is the jumble the Yukon rule exists for. Column 5 is a king with
     * a passenger, column 4 an empty column for it to take.
     */
    private fun fixture() = Game(
        stock = emptyList(),
        waste = emptyList(),
        foundations = List(Game.FOUNDATIONS) { emptyList() },
        tableau = listOf(
            emptyList(),
            listOf(Card(7, Suit.DIAMONDS), Card(4, Suit.CLUBS), Card(12, Suit.CLUBS), Card(2, Suit.HEARTS)).faceUp(),
            listOf(Card(5, Suit.HEARTS)).faceUp(),
            listOf(Card(5, Suit.SPADES)).faceUp(),
            emptyList(),
            listOf(Card(13, Suit.SPADES), Card(3, Suit.DIAMONDS)).faceUp(),
            emptyList(),
        ),
        variant = Variant.YUKON,
    )

    private fun List<Card>.faceUp() = map { TableauCard(it, faceUp = true) }

    /**
     * The Klondike invariant that a column is a legal run above its face down
     * cards does not hold here, and that is the point of the game. Everything
     * else does.
     */
    private fun Game.assertYukonInvariants(label: String) {
        val all = stock + waste + foundations.flatten() + tableau.flatten().map { it.card }
        assertEquals(all.size, all.toSet().size, "$label: duplicate cards")
        assertTrue(stock.isEmpty() && waste.isEmpty(), "$label: Yukon grew a stock")

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
            }
            assertTrue(column.lastOrNull()?.faceUp != false, "$label: column $i left a card face down on top")
        }
    }
}
