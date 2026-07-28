package com.thelightphone.solitaire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SaveStateTest {

    @Test
    fun `a fresh deal survives a round trip`() {
        for (seed in 1L..50L) {
            val game = Game.deal(seed)
            assertEquals(game, SaveState.decode(SaveState.encode(game)), "seed $seed")
        }
    }

    @Test
    fun `a game in progress survives a round trip`() {
        var game = Game.deal(3)
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
        assertNull(SaveState.decode("2|||||0"), "wrong version")
        assertNull(SaveState.decode("1|1S|||;;;;;;|0"), "not 52 cards")
        assertNull(SaveState.decode("1|99Z|||;;;;;;|0"), "bad card")
        assertNull(SaveState.decode(SaveState.encode(Game.deal(1)).dropLast(2)), "truncated")
        assertNull(
            SaveState.decode(SaveState.encode(Game.deal(1)).replace("+", "?")),
            "bad face up marker",
        )
    }

    @Test
    fun `a duplicated card is rejected`() {
        val game = Game.deal(11)
        val doubled = game.copy(stock = game.stock.dropLast(1) + game.stock.first())
        assertNull(SaveState.decode(SaveState.encode(doubled)))
    }
}
