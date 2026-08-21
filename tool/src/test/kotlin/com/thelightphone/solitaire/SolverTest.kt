package com.thelightphone.solitaire

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SolverTest {

    // ------------------------------------------------------------ the state key

    @Test
    fun `two different positions never share a key`() {
        val keys = HashSet<String>()
        for (seed in 1L..400L) {
            assertTrue(keys.add(Game.deal(Variant.KLONDIKE, seed).stateKey()), "seed $seed collided")
        }
    }

    @Test
    fun `the key ignores the move counter`() {
        val game = Game.deal(Variant.KLONDIKE, 9)
        assertEquals(game.stateKey(), game.copy(moves = 500).stateKey())
    }

    @Test
    fun `the key separates piles rather than running them together`() {
        // Same 52 cards, one card moved from the stock into the waste.
        val a = Game(
            stock = listOf(Card(1, Suit.SPADES), Card(2, Suit.SPADES)),
            waste = emptyList(),
            foundations = List(4) { emptyList() },
            tableau = List(7) { emptyList() },
        )
        val b = a.copy(stock = listOf(Card(1, Suit.SPADES)), waste = listOf(Card(2, Suit.SPADES)))
        assertTrue(a.stateKey() != b.stateKey())

        // Face up and face down are different positions too.
        val up = a.copy(tableau = listOf(listOf(TableauCard(Card(3, Suit.HEARTS), true))) + List(6) { emptyList() })
        val down = a.copy(tableau = listOf(listOf(TableauCard(Card(3, Suit.HEARTS), false))) + List(6) { emptyList() })
        assertTrue(up.stateKey() != down.stateKey())
    }

    // ------------------------------------------------------------ verdicts

    @Test
    fun `a board with nothing left to do is proven unwinnable`() {
        val dead = Game(
            stock = emptyList(),
            waste = emptyList(),
            foundations = List(4) { emptyList() },
            tableau = listOf(
                listOf(TableauCard(Card(13, Suit.SPADES), true)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            ),
        )
        val analysis = Solver.analyze(dead)
        assertEquals(Verdict.UNWINNABLE, analysis.verdict)
        assertEquals(0, analysis.positionsVisited, "there was nothing to visit")
    }

    @Test
    fun `one move from a win is winnable and names the move`() {
        val almost = wonGame().let { won ->
            // Take the king of clubs back off its foundation and onto an empty column.
            val clubs = won.foundations[Suit.CLUBS.ordinal]
            won.copy(
                foundations = won.foundations.toMutableList().also {
                    it[Suit.CLUBS.ordinal] = clubs.dropLast(1)
                },
                tableau = listOf(listOf(TableauCard(Card(13, Suit.CLUBS), true))) + List(6) { emptyList() },
            )
        }
        val analysis = Solver.analyze(almost)
        assertEquals(Verdict.WINNABLE, analysis.verdict)
        assertEquals(
            Action.Shift(Pile.Tableau(0), 0, Pile.Foundation(Suit.CLUBS.ordinal)),
            assertNotNull(analysis.firstMove),
        )
    }

    @Test
    fun `the reported line actually reaches a win`() {
        var game = nearlyWonGame()
        val analysis = Solver.analyze(game)
        assertEquals(Verdict.WINNABLE, analysis.verdict)
        assertTrue(analysis.line.isNotEmpty())
        for (move in analysis.line) {
            game = assertNotNull(game.perform(move), "the line contained an illegal $move")
        }
        assertTrue(game.isWon, "the line ran out without winning")
    }

    /**
     * Why the whole line is returned instead of just the next move: from a
     * position that can be won, moving a card to a foundation and pulling it
     * straight back are both first moves of some winning path. Asking for one
     * move at a time can bounce between the two forever.
     */
    @Test
    fun `a single move is not enough to play on`() {
        val analysis = Solver.analyze(nearlyWonGame())
        assertEquals(analysis.line.firstOrNull(), analysis.firstMove)
        assertTrue(analysis.line.size > 1, "an endgame takes more than one move")
    }

    @Test
    fun `a fresh deal on a tiny budget is honest about not knowing`() {
        val analysis = Solver.analyze(Game.deal(Variant.KLONDIKE, 4), budget = 200)
        assertEquals(Verdict.UNKNOWN, analysis.verdict)
        assertNull(analysis.firstMove, "an unknown verdict must not suggest a line")
        assertTrue(analysis.positionsVisited > 200)
    }

    @Test
    fun `an already won board needs no search`() {
        val analysis = Solver.analyze(wonGame())
        assertEquals(Verdict.WINNABLE, analysis.verdict)
        assertEquals(0, analysis.positionsVisited)
    }

    // ------------------------------------------------------------ cross-check

    /**
     * The verdict is only worth trusting if a second, independent search agrees.
     * This one is breadth first, keyed on [SaveState] rather than [stateKey], and
     * it explores the whole space rather than stopping at the first win.
     *
     * A full deal is far too big for an exhaustive second opinion, so the rules
     * are run over cut-down decks instead. Same engine, small enough that both
     * searches finish and any disagreement shows up.
     */
    @Test
    fun `an independent search agrees on every position it can finish`() {
        var compared = 0
        var winnable = 0
        for (position in tinyPositions() + smallPositions()) {
            val mine = Solver.analyze(position, budget = 12_000)
            val theirs = exhaustiveSearch(position, limit = 12_000)
            if (mine.verdict == Verdict.UNKNOWN || theirs == null) continue
            assertEquals(
                theirs,
                mine.verdict == Verdict.WINNABLE,
                "the two searches disagreed on\n${SaveState.encode(position)}",
            )
            if (theirs) winnable++
            compared++
        }
        assertTrue(compared >= 120, "only cross-checked $compared positions")
        // Both verdicts have to be represented or the agreement means nothing.
        assertTrue(winnable > 20, "only $winnable of $compared were winnable")
        assertTrue(compared - winnable > 20, "only ${compared - winnable} were unwinnable")
    }

    @Test
    fun `every winnable verdict comes with a line that wins`() {
        var replayed = 0
        for (position in tinyPositions()) {
            val analysis = Solver.analyze(position, budget = 12_000)
            if (analysis.verdict != Verdict.WINNABLE) continue
            var game = position
            for (move in analysis.line) {
                game = assertNotNull(game.perform(move), "the line contained an illegal $move")
            }
            assertTrue(game.isWon, "a winnable verdict handed back a line that does not win")
            replayed++
        }
        assertTrue(replayed >= 50, "only replayed $replayed lines")
    }

    /** Returns true or false when the space was fully explored, null if it gave up. */
    private fun exhaustiveSearch(start: Game, limit: Int): Boolean? {
        if (start.isWon) return true
        val seen = HashSet<String>()
        seen.add(SaveState.encode(start.copy(moves = 0)))
        val queue = ArrayDeque<Game>()
        queue.addLast(start)
        var visited = 0

        while (queue.isNotEmpty()) {
            val game = queue.removeFirst()
            for (action in game.legalActions()) {
                val next = game.perform(action) ?: continue
                if (next.isWon) return true
                if (++visited > limit) return null
                if (!seen.add(SaveState.encode(next.copy(moves = 0)))) continue
                queue.addLast(next)
            }
        }
        return false
    }

    // ------------------------------------------------------------ fixtures

    private fun wonGame() = Game(
        stock = emptyList(),
        waste = emptyList(),
        foundations = Suit.entries.map { suit -> (1..13).map { Card(it, suit) } },
        tableau = List(7) { emptyList() },
    )

    /** All four kings pulled back onto the table, plus their queens underneath. */
    private fun nearlyWonGame(): Game {
        val foundations = Suit.entries.map { suit -> (1..11).map { Card(it, suit) } }
        return Game(
            stock = emptyList(),
            waste = emptyList(),
            foundations = foundations,
            tableau = listOf(
                listOf(TableauCard(Card(13, Suit.SPADES), true), TableauCard(Card(12, Suit.HEARTS), true)),
                listOf(TableauCard(Card(13, Suit.HEARTS), true), TableauCard(Card(12, Suit.SPADES), true)),
                listOf(TableauCard(Card(13, Suit.DIAMONDS), true), TableauCard(Card(12, Suit.CLUBS), true)),
                listOf(TableauCard(Card(13, Suit.CLUBS), true), TableauCard(Card(12, Suit.DIAMONDS), true)),
                emptyList(), emptyList(), emptyList(),
            ),
        )
    }

    /**
     * The same rules over cut-down decks, dealt at random. A full deal has far
     * too many positions for a second search to walk exhaustively, but a few
     * suits and a few ranks stays small enough to check completely, and produces
     * a healthy mix of winnable and hopeless boards.
     */
    private fun tinyPositions(): List<Game> {
        val random = Random(20260728)
        val positions = ArrayList<Game>(250)
        val shapes = listOf(2 to 3, 2 to 4, 3 to 3, 4 to 2, 4 to 3)

        for ((suits, topRank) in shapes) {
            val deck = Suit.entries.take(suits).flatMap { suit -> (1..topRank).map { Card(it, suit) } }
            repeat(50) {
                val shuffled = deck.shuffled(random)
                val depth = random.nextInt(2, 5)
                val columns = ArrayList<List<TableauCard>>(Game.COLUMNS)
                var at = 0
                for (col in 0 until Game.COLUMNS) {
                    val remaining = shuffled.size - at
                    val take = if (col == Game.COLUMNS - 1) {
                        0 // leave one column open
                    } else {
                        random.nextInt(0, minOf(depth, remaining + 1))
                    }
                    // Last card of a column faces up, the rest are buried.
                    columns += (0 until take).map { i ->
                        TableauCard(shuffled[at + i], faceUp = i == take - 1)
                    }
                    at += take
                }
                positions += Game(
                    stock = shuffled.drop(at),
                    waste = emptyList(),
                    foundations = List(Game.FOUNDATIONS) { emptyList() },
                    tableau = columns,
                )
            }
        }
        return positions
    }

    /**
     * Positions with a small enough reachable space that both searches can finish:
     * end games built by taking the top cards back off the foundations, plus a few
     * genuinely stuck boards.
     */
    private fun smallPositions(): List<Game> {
        val positions = ArrayList<Game>()
        positions += nearlyWonGame()

        // Endgames: top n ranks of every suit sitting on the table instead of banked.
        for (depth in 1..3) {
            val top = 13 - depth + 1
            val foundations = Suit.entries.map { suit -> (1 until top).map { Card(it, suit) } }
            val loose = Suit.entries.flatMap { suit -> (top..13).map { Card(it, suit) } }
            positions += Game(
                stock = loose.drop(4),
                waste = emptyList(),
                foundations = foundations,
                tableau = loose.take(4).mapIndexed { i, card ->
                    if (i < 4) listOf(TableauCard(card, true)) else emptyList()
                } + List(3) { emptyList() },
            )
        }

        // Stuck boards: kings sitting on their own queens with nowhere to go.
        for (banked in 0..2) {
            val foundations = Suit.entries.map { suit -> (1..banked).map { Card(it, suit) } }
            val used = foundations.flatten().toSet()
            val rest = freshDeck().filterNot { it in used }
            positions += Game(
                stock = rest.drop(7),
                waste = emptyList(),
                foundations = foundations,
                tableau = List(7) { i -> listOf(TableauCard(rest[i], i == 6)) },
            )
        }

        // A handful of real deals played a few moves in, for variety.
        for (seed in 1L..20L) {
            var game = Game.deal(Variant.KLONDIKE, seed)
            repeat(6) { game = game.draw() ?: game }
            positions += game
        }
        return positions
    }
}
