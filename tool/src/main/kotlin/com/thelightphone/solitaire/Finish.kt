package com.thelightphone.solitaire

/**
 * Playing out a game that is already decided.
 *
 * Once no card on the table is face down there is nothing left to find out. In
 * Klondike what remains is usually clerical: bank the aces, bank the twos, keep
 * going. Making somebody tap forty times through a foregone conclusion is not a
 * game, so the screen offers to do it for them.
 *
 * The offer is only ever taken up on a line that has been found in full. Nothing
 * here plays a move on the hope that the rest works out — a finish that gets
 * stuck halfway, having rearranged the board on the way, would be worse than no
 * finish at all.
 */

/**
 * The longest finish the screen will play.
 *
 * A win needs fifty-two foundation moves at the very least, and the draws and
 * shuffling around them make up the rest. The cap is not really about search
 * cost: the player watches every one of these go past, and a line long enough
 * to want skipping was not worth offering.
 */
private const val MAX_MOVES = 220

/**
 * Positions to try before giving up looking. Well under [Solver.DEFAULT_BUDGET]
 * — this runs with somebody's finger still on the screen, and it is looking for
 * an easy win rather than settling the question.
 */
private const val DEFAULT_BUDGET = 30_000

/**
 * A complete line of play from here to a win, or null if none was found.
 *
 * Two attempts, cheapest first.
 *
 * The sweep is the one that answers a Klondike endgame. It plays cards to the
 * foundations and nothing else, lowest rank first, drawing through the stock
 * when the table has nothing to give. Once the columns have come apart that is
 * all that is left, and it costs a few dozen steps to find.
 *
 * Otherwise a depth limited search — a different question from the one [Solver]
 * answers, wanting a different kind of answer. [Solver] asks whether a win
 * exists at all, and will happily follow a four thousand move path to prove that
 * one does. Here a four thousand move path is no answer, because the line gets
 * played out in front of somebody. So this is capped at [maxMoves], never pulls
 * a card back off a foundation, and gives up early. A null therefore means "no
 * short win from here", which is not a claim that the deal is lost: [Solver] is
 * still the only thing that says that, and it says it in its own time.
 */
fun Game.finishingLine(
    maxMoves: Int = MAX_MOVES,
    budget: Int = DEFAULT_BUDGET,
): List<Action>? {
    if (isWon) return emptyList()
    foundationSweep(maxMoves)?.let { return it }
    return searchForWin(maxMoves, budget)
}

/**
 * Bank every card, touching nothing else. Null unless it reaches a win.
 *
 * Lowest rank first so the foundations climb together: sending a seven up while
 * the three of another suit is still buried is how an autoplay strands a card it
 * needed somewhere to put.
 */
private fun Game.foundationSweep(maxMoves: Int): List<Action>? {
    val line = ArrayList<Action>(52)
    var game = this
    // A whole cycle of the stock with nothing banked means nothing can be.
    var drawsSinceProgress = 0

    while (!game.isWon && line.size < maxMoves) {
        val bank = game.lowestBankable()
        if (bank != null) {
            game = game.perform(bank) ?: return null
            line += bank
            drawsSinceProgress = 0
            continue
        }
        if (drawsSinceProgress > game.stock.size + game.waste.size) return null
        game = game.draw() ?: return null
        line += Action.Draw
        drawsSinceProgress++
    }

    return if (game.isWon) line else null
}

/** The lowest card that can go straight to a foundation, from the waste or a column top. */
private fun Game.lowestBankable(): Action.Shift? {
    var best: Action.Shift? = null
    var bestRank = Int.MAX_VALUE

    fun consider(card: Card, source: Pile, cardIndex: Int) {
        if (card.rank >= bestRank) return
        for (f in 0 until Game.FOUNDATIONS) {
            if (acceptsOnFoundation(f, card)) {
                best = Action.Shift(source, cardIndex, Pile.Foundation(f))
                bestRank = card.rank
                return
            }
        }
    }

    waste.lastOrNull()?.let { consider(it, Pile.Waste, waste.lastIndex) }
    for (c in 0 until Game.COLUMNS) {
        val top = tableau[c].lastOrNull() ?: continue
        if (!top.faceUp) continue
        consider(top.card, Pile.Tableau(c), tableau[c].lastIndex)
    }
    return best
}

/**
 * Depth first, foundations first, capped.
 *
 * The visited table records the depth a position was first reached at rather
 * than a bare "seen". Under a depth cap those are different things: a position
 * first met at move 190 and abandoned there would stay abandoned when it turned
 * up again at move 30, with all the room it needed and no way to use it.
 */
private fun Game.searchForWin(maxMoves: Int, budget: Int): List<Action>? {
    val bestDepth = HashMap<String, Int>()
    val line = ArrayList<Action>(maxMoves)
    var visited = 0

    // hints() is already everything worth trying here, in the right order:
    // foundation moves first, then the ones that uncover something, and never a
    // card pulled back off a foundation.
    fun descend(game: Game, depth: Int): Boolean {
        if (game.isWon) return true
        if (depth == maxMoves) return false

        for (action in game.hints()) {
            if (visited >= budget) return false
            val next = game.perform(action) ?: continue
            visited++
            val key = next.stateKey()
            if ((bestDepth[key] ?: Int.MAX_VALUE) <= depth + 1) continue
            bestDepth[key] = depth + 1
            line += action
            if (descend(next, depth + 1)) return true
            line.removeAt(line.lastIndex)
        }
        return false
    }

    return if (descend(this, 0)) line.toList() else null
}
