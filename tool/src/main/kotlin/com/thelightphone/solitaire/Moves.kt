package com.thelightphone.solitaire

/**
 * Moves as data, so the same list can drive hints, the solver and the tap
 * animation instead of each one reimplementing the rules.
 */
sealed interface Action {

    /** Turn one card from the stock, or redeal the waste when the stock is out. */
    data object Draw : Action

    /** Move [cardIndex] and everything on top of it from [source] to [destination]. */
    data class Shift(val source: Pile, val cardIndex: Int, val destination: Pile) : Action

    /**
     * Turn over a face down card at the bottom of a column. Only reachable from an
     * odd starting position: every move already turns over what it exposes.
     */
    data class TurnOver(val column: Int) : Action
}

fun Game.perform(action: Action): Game? = when (action) {
    Action.Draw -> draw()
    is Action.Shift -> move(action.source, action.cardIndex, action.destination)
    is Action.TurnOver -> flipTop(action.column)
}

/**
 * Every legal action, including ones that only move cards sideways. Used by the
 * solver, so it has to be complete: leaving a legal action out would turn
 * "searched everything" into a lie.
 *
 * The one thing left out is moving a whole face up column onto an empty column.
 * That produces the same board with two columns swapped, so skipping it cannot
 * hide a win.
 */
fun Game.legalActions(): List<Action> {
    val actions = ArrayList<Action>(24)

    waste.lastOrNull()?.let { card ->
        for (f in 0 until Game.FOUNDATIONS) {
            if (acceptsOnFoundation(f, card)) actions += Action.Shift(Pile.Waste, waste.lastIndex, Pile.Foundation(f))
        }
        for (t in 0 until Game.COLUMNS) {
            if (acceptsOnTableau(t, card)) actions += Action.Shift(Pile.Waste, waste.lastIndex, Pile.Tableau(t))
        }
    }

    for (c in 0 until Game.COLUMNS) {
        val column = tableau[c]
        val firstUp = column.indexOfFirst { it.faceUp }
        if (firstUp == -1) continue
        for (i in firstUp..column.lastIndex) {
            if (!variant.canPickUp(column.drop(i))) continue
            val head = column[i].card

            if (i == column.lastIndex) {
                for (f in 0 until Game.FOUNDATIONS) {
                    if (acceptsOnFoundation(f, head)) {
                        actions += Action.Shift(Pile.Tableau(c), i, Pile.Foundation(f))
                    }
                }
            }
            for (t in 0 until Game.COLUMNS) {
                if (t == c) continue
                // Relabelling the board, not a move.
                if (i == 0 && tableau[t].isEmpty()) continue
                if (acceptsOnTableau(t, head)) {
                    actions += Action.Shift(Pile.Tableau(c), i, Pile.Tableau(t))
                }
            }
        }
    }

    // Pulling a card back off a foundation is legal and occasionally the only way
    // through, so the search has to consider it.
    for (f in 0 until Game.FOUNDATIONS) {
        val pile = foundations[f]
        val card = pile.lastOrNull() ?: continue
        for (t in 0 until Game.COLUMNS) {
            if (acceptsOnTableau(t, card)) {
                actions += Action.Shift(Pile.Foundation(f), pile.lastIndex, Pile.Tableau(t))
            }
        }
    }

    // Yukon deals every card onto the table, so both of these stay empty for the
    // whole game and it never gets a draw. Nothing here needs to ask which game
    // it is.
    if (stock.isNotEmpty() || waste.isNotEmpty()) actions += Action.Draw

    return actions
}

/** Foundation moves first, then reveals, then everything else. Finds wins sooner. */
internal fun Game.searchActions(): List<Action> = legalActions().sortedByDescending { score(it) }

/**
 * What to suggest, best first. Unlike [legalActions] this leaves out anything a
 * player would not want offered: pulling cards back off a foundation, and moves
 * that shuffle cards with nothing to show for it are ranked last rather than hidden.
 */
fun Game.hints(): List<Action> {
    val shifts = legalActions()
        .filterIsInstance<Action.Shift>()
        .filter { it.source !is Pile.Foundation }
        .sortedByDescending { score(it) }

    // Drawing is a real suggestion when nothing on the table can move.
    val draw = if (stock.isNotEmpty() || waste.isNotEmpty()) listOf(Action.Draw) else emptyList()
    return shifts + draw
}

/** True when neither the table nor a full trip through the stock offers a move. */
fun Game.isDeadEnd(): Boolean {
    if (hasShift()) return false
    var probe = this
    // stock + waste is one full cycle; the extra step covers the redeal itself.
    repeat(stock.size + waste.size + 1) {
        probe = probe.draw() ?: return true
        if (probe.hasShift()) return false
    }
    return true
}

private fun Game.hasShift(): Boolean = legalActions().any { it is Action.Shift }

private fun Game.score(action: Action): Int = when (action) {
    Action.Draw -> 0

    is Action.TurnOver -> 90

    is Action.Shift -> when {
        // Banking a card, low ranks first.
        action.destination is Pile.Foundation -> 100 + (14 - cardRank(action))

        // Uncovering a face down card is the only thing that opens up a Klondike deal.
        action.source is Pile.Tableau && revealsFaceDownCard(action) -> 80

        // Emptying a column leaves somewhere to put a king.
        action.source is Pile.Tableau && action.cardIndex == 0 -> 60

        action.source is Pile.Waste -> 40

        // Legal, but only rearranges what is already visible.
        else -> 10
    }
}

private fun Game.cardRank(shift: Action.Shift): Int = when (val source = shift.source) {
    Pile.Waste -> waste.lastOrNull()?.rank ?: 0
    is Pile.Foundation -> foundations[source.index].lastOrNull()?.rank ?: 0
    is Pile.Tableau -> tableau[source.index].getOrNull(shift.cardIndex)?.card?.rank ?: 0
    Pile.Stock -> 0
}

private fun Game.revealsFaceDownCard(shift: Action.Shift): Boolean {
    val source = shift.source as? Pile.Tableau ?: return false
    val below = tableau[source.index].getOrNull(shift.cardIndex - 1) ?: return false
    return !below.faceUp
}

/**
 * The move a tap makes. Foundation first for a single card, then the leftmost
 * legal column.
 */
fun Game.autoAction(source: Pile, cardIndex: Int): Action? = when (source) {
    Pile.Stock -> if (variant.hasStock) Action.Draw else null

    Pile.Waste -> waste.lastOrNull()?.let { card ->
        destinationFor(card, single = true, fromColumn = -1)
            ?.let { Action.Shift(source, waste.lastIndex, it) }
    }

    // Tapping a foundation never unstacks it. Drag if you mean it.
    is Pile.Foundation -> null

    is Pile.Tableau -> {
        val column = tableau[source.index]
        when {
            cardIndex !in column.indices -> null
            !column[cardIndex].faceUp ->
                if (cardIndex == column.lastIndex) Action.TurnOver(source.index) else null
            else -> {
                val run = column.drop(cardIndex)
                if (!variant.canPickUp(run)) {
                    null
                } else {
                    destinationFor(
                        card = run.first().card,
                        single = run.size == 1,
                        fromColumn = source.index,
                        // Moving a whole column to an empty one gains nothing.
                        allowEmptyColumn = cardIndex > 0,
                    )?.let { Action.Shift(source, cardIndex, it) }
                }
            }
        }
    }
}

private fun Game.destinationFor(
    card: Card,
    single: Boolean,
    fromColumn: Int,
    allowEmptyColumn: Boolean = true,
): Pile? {
    if (single) {
        for (i in 0 until Game.FOUNDATIONS) {
            if (acceptsOnFoundation(i, card)) return Pile.Foundation(i)
        }
    }
    for (i in 0 until Game.COLUMNS) {
        if (i == fromColumn) continue
        if (tableau[i].isEmpty() && !allowEmptyColumn) continue
        if (acceptsOnTableau(i, card)) return Pile.Tableau(i)
    }
    return null
}

/**
 * Which card of a pile to draw while cards are in the air, or null to draw none.
 *
 * A card being carried across the screen must not also be sitting on the pile it
 * is headed for. Columns stack visibly, so the cards that landed are dropped and
 * whatever they cover shows through on its own. The stock, waste and foundations
 * only ever draw their top card, so those have to step down to the card
 * underneath or the pile looks empty for as long as the move takes.
 *
 * Lives here rather than next to the drawing code so it can be tested without a
 * screen.
 *
 * @param firstLandedIndex index of the first card that just landed on [landing].
 */
fun visibleCardIndex(
    pile: Pile,
    cardIndex: Int,
    landing: Pile?,
    firstLandedIndex: Int,
): Int? {
    if (landing == null || pile != landing) return cardIndex
    return when (pile) {
        is Pile.Tableau -> if (cardIndex >= firstLandedIndex) null else cardIndex
        else -> firstLandedIndex - 1
    }
}

/** The cards an action picks up, for the animation to carry across the screen. */
fun Game.cardsMovedBy(action: Action): List<Card> = when (action) {
    // A redeal moves the whole waste at once, so nothing flies across the screen.
    Action.Draw -> stock.lastOrNull()?.let { listOf(it) } ?: emptyList()
    is Action.Shift -> cardsAt(action.source, action.cardIndex)
    is Action.TurnOver -> emptyList()
}
