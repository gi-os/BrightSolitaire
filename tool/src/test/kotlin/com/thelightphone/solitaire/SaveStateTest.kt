package com.thelightphone.solitaire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SaveStateTest {

    @Test
    fun `a fresh deal survives a round trip`() {
        for (variant in Variant.entries) {
            for (seed in 1L..50L) {
                val game = Game.deal(variant, seed)
                assertEquals(game, SaveState.decode(SaveState.encode(game)), "$variant seed $seed")
            }
        }
    }

    @Test
    fun `which game it is comes back with it`() {
        for (variant in Variant.entries) {
            val game = Game.deal(variant, 8)
            assertEquals(variant, assertNotNull(SaveState.decode(SaveState.encode(game))).variant)
        }
        // Two boards that differ only in which game they are must not read alike.
        assertNotEquals(
            SaveState.encode(Game.deal(Variant.KLONDIKE, 8)),
            SaveState.encode(Game.deal(Variant.YUKON, 8)),
        )
    }

    @Test
    fun `a yukon game in progress survives a round trip`() {
        var game = Game.deal(Variant.YUKON, 4)
        repeat(10) { game = game.perform(game.hints().firstOrNull() ?: return@repeat) ?: game }

        val restored = assertNotNull(SaveState.decode(SaveState.encode(game)))
        assertEquals(game, restored)
        assertEquals(Variant.YUKON, restored.variant)
        assertEquals(
            game.tableau.map { column -> column.map { it.faceUp } },
            restored.tableau.map { column -> column.map { it.faceUp } },
        )
    }

    /**
     * Version 1 had no variant field because there was only one game. Those
     * lines are all Klondike, and reading them is the difference between an
     * update that keeps your board and one that quietly throws it away.
     */
    @Test
    fun `a save written before Yukon existed still reads, as Klondike`() {
        val game = Game.deal(Variant.KLONDIKE, 12)
        val version1 = SaveState.encode(game).let { "1" + it.drop(1).dropLast(2) }

        val restored = assertNotNull(SaveState.decode(version1))
        assertEquals(game, restored)
        assertEquals(Variant.KLONDIKE, restored.variant)
    }

    @Test
    fun `a game in progress survives a round trip`() {
        var game = Game.deal(Variant.KLONDIKE, 3)
        repeat(6) { game = game.draw() ?: game }
        game = game.autoMove(Pile.Waste, game.waste.lastIndex) ?: game
        game = game.autoMove(Pile.Tableau(6), game.tableau[6].lastIndex) ?: game

        val restored = assertNotNull(SaveState.decode(SaveState.encode(game)))
        assertEquals(game, restored)
        assertEquals(game.moves, restored.moves)
        assertEquals(game.tableau.map { column -> column.map { it.faceUp } },
            restored.tableau.map { column -> column.map { it.faceUp } })
    }

    @Test
    fun `an empty board round trips`() {
        val won = Game(
            stock = emptyList(),
            waste = emptyList(),
            foundations = Suit.entries.map { suit -> (1..13).map { Card(it, suit) } },
            tableau = List(7) { emptyList() },
            moves = 214,
        )
        assertEquals(won, SaveState.decode(SaveState.encode(won)))
    }

    @Test
    fun `garbage decodes to nothing instead of a broken board`() {
        assertNull(SaveState.decode(null))
        assertNull(SaveState.decode(""))
        assertNull(SaveState.decode("   "))
        assertNull(SaveState.decode("not a save"))
        assertNull(SaveState.decode("2|||||0"), "a version 2 line with no variant field")
        assertNull(SaveState.decode("3|||||0|K"), "a version from the future")
        assertNull(SaveState.decode("0|||||0"), "a version from before the first")
        assertNull(
            SaveState.decode(SaveState.encode(Game.deal(Variant.YUKON, 1)).dropLast(1) + "Q"),
            "a game nobody has heard of",
        )
        assertNull(SaveState.decode("1|1S|||;;;;;;|0"), "not 52 cards")
        assertNull(SaveState.decode("1|99Z|||;;;;;;|0"), "bad card")
        assertNull(SaveState.decode(SaveState.encode(Game.deal(Variant.KLONDIKE, 1)).dropLast(2)), "truncated")
        assertNull(
            SaveState.decode(SaveState.encode(Game.deal(Variant.KLONDIKE, 1)).replace("+", "?")),
            "bad face up marker",
        )
    }

    @Test
    fun `a duplicated card is rejected`() {
        val game = Game.deal(Variant.KLONDIKE, 11)
        val doubled = game.copy(stock = game.stock.dropLast(1) + game.stock.first())
        assertNull(SaveState.decode(SaveState.encode(doubled)))
    }
}
