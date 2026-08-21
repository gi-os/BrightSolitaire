package com.thelightphone.solitaire

/**
 * A game of solitaire on a seven column table: Klondike or Yukon, see [Variant].
 *
 * [Game] is immutable. Every legal action returns a new [Game] or null if the
 * action is not legal. That makes undo a list of old states and nothing else.
 *
 * One type covers both games rather than two that mostly agree, because
 * everything downstream — the solver, the hints, the save format, the screen —
 * would otherwise need writing twice and could then disagree twice.
 */

sealed interface Pile {
    data object Stock : Pile
    data object Waste : Pile
    data class Foundation(val index: Int) : Pile
    data class Tableau(val index: Int) : Pile
}

data class Game(
    val stock: List<Card>,
    val waste: List<Card>,
    val foundations: List<List<Card>>,
    val tableau: List<List<TableauCard>>,
    val moves: Int = 0,
    val variant: Variant = Variant.KLONDIKE,
) {
    /**
     * Cards only ever leave the table for a foundation, so an empty table is a
     * win. Stated this way rather than counting to 52 so that tests can run the
     * same rules over a cut-down deck.
     */
    val isWon: Boolean
        get() = stock.isEmpty() && waste.isEmpty() && tableau.all { it.isEmpty() }

    /**
     * Nothing on the table is hidden any more. Not the same as won, and in
     * Yukon not even the same as decided — see [finishingLine].
     */
    val isFullyRevealed: Boolean
        get() = tableau.all { column -> column.all { it.faceUp } }

    companion object {
        const val COLUMNS = 7
        const val FOUNDATIONS = 4

        /**
         * Cards each Yukon column gets face up, on top of its face down ones.
         * The first column is the exception and gets one card, face up.
         */
        private const val YUKON_FACE_UP = 5

        fun deal(variant: Variant, seed: Long): Game {
            val deck = shuffledDeck(seed).toMutableList()
            val columns = ArrayList<List<TableauCard>>(COLUMNS)
            for (col in 0 until COLUMNS) {
                val faceDown = if (variant == Variant.YUKON && col == 0) 0 else col
                val faceUp = when {
                    variant == Variant.KLONDIKE -> 1
                    col == 0 -> 1
                    else -> YUKON_FACE_UP
                }
                val cards = ArrayList<TableauCard>(faceDown + faceUp)
                repeat(faceDown) { cards.add(TableauCard(deck.removeAt(deck.lastIndex), faceUp = false)) }
                repeat(faceUp) { cards.add(TableauCard(deck.removeAt(deck.lastIndex), faceUp = true)) }
                columns.add(cards)
            }
            return Game(
                // Yukon deals the whole pack onto the table, so the deck is empty
                // by here and the stock never gets a card for the rest of the game.
                stock = deck.toList(),
                waste = emptyList(),
                foundations = List(FOUNDATIONS) { emptyList() },
                tableau = columns,
                variant = variant,
            )
        }
    }
}

// ---------------------------------------------------------------- rules

fun Game.acceptsOnFoundation(index: Int, card: Card): Boolean {
    val pile = foundations[index]
    val top = pile.lastOrNull() ?: return card.rank == 1
    return top.suit == card.suit && card.rank == top.rank + 1
}

fun Game.acceptsOnTableau(index: Int, card: Card): Boolean {
    val top = tableau[index].lastOrNull() ?: return card.rank == 13
    if (!top.faceUp) return false
    return top.card.isRed != card.isRed && card.rank == top.card.rank - 1
}

/** True if [cardIndex] in column [index] is the head of a group the player can pick up. */
fun Game.isDraggable(pile: Pile, cardIndex: Int): Boolean = when (pile) {
    Pile.Stock -> false
    Pile.Waste -> waste.isNotEmpty() && cardIndex == waste.lastIndex
    is Pile.Foundation -> foundations[pile.index].isNotEmpty() &&
        cardIndex == foundations[pile.index].lastIndex
    is Pile.Tableau -> {
        val column = tableau[pile.index]
        cardIndex in column.indices && variant.canPickUp(column.drop(cardIndex))
    }
}

/** The cards the player would pick up from [pile] starting at [cardIndex]. */
fun Game.cardsAt(pile: Pile, cardIndex: Int): List<Card> = when (pile) {
    Pile.Stock -> emptyList()
    Pile.Waste -> waste.lastOrNull()?.let { listOf(it) } ?: emptyList()
    is Pile.Foundation -> foundations[pile.index].lastOrNull()?.let { listOf(it) } ?: emptyList()
    is Pile.Tableau -> tableau[pile.index].drop(cardIndex).map { it.card }
}

// ---------------------------------------------------------------- actions

fun Game.draw(): Game? {
    if (stock.isEmpty()) {
        if (waste.isEmpty()) return null
        return copy(stock = waste.reversed(), waste = emptyList(), moves = moves + 1)
    }
    return copy(
        stock = stock.dropLast(1),
        waste = waste + stock.last(),
        moves = moves + 1,
    )
}

fun Game.flipTop(column: Int): Game? {
    val cards = tableau[column]
    val top = cards.lastOrNull() ?: return null
    if (top.faceUp) return null
    return copy(tableau = tableau.replaceAt(column, cards.dropLast(1) + top.copy(faceUp = true)))
        .copy(moves = moves + 1)
}

fun Game.move(source: Pile, cardIndex: Int, destination: Pile): Game? {
    if (source == destination) return null
    val (afterRemove, cards) = removeFrom(source, cardIndex) ?: return null
    val afterPlace = afterRemove.placeOn(destination, cards) ?: return null
    return afterPlace.flipExposed().copy(moves = moves + 1)
}

/**
 * One tap. Sends the card wherever it obviously wants to go: foundation first
 * for a single card, then the leftmost legal tableau column.
 *
 * The choice itself lives in [autoAction] so the UI can animate the move it is
 * about to make rather than guessing what happened.
 */
fun Game.autoMove(source: Pile, cardIndex: Int): Game? =
    autoAction(source, cardIndex)?.let { perform(it) }

// ---------------------------------------------------------------- internals

private fun Game.removeFrom(source: Pile, cardIndex: Int): Pair<Game, List<Card>>? = when (source) {
    Pile.Stock -> null

    Pile.Waste -> {
        val card = waste.lastOrNull()
        if (card == null) null else copy(waste = waste.dropLast(1)) to listOf(card)
    }

    is Pile.Foundation -> {
        val card = foundations[source.index].lastOrNull()
        if (card == null) {
            null
        } else {
            val trimmed = foundations[source.index].dropLast(1)
            copy(foundations = foundations.replaceAt(source.index, trimmed)) to listOf(card)
        }
    }

    is Pile.Tableau -> {
        val column = tableau[source.index]
        if (cardIndex !in column.indices) {
            null
        } else {
            val run = column.drop(cardIndex)
            if (!variant.canPickUp(run)) {
                null
            } else {
                copy(tableau = tableau.replaceAt(source.index, column.take(cardIndex))) to run.map { it.card }
            }
        }
    }
}

private fun Game.placeOn(destination: Pile, cards: List<Card>): Game? = when (destination) {
    Pile.Stock, Pile.Waste -> null

    is Pile.Foundation -> {
        val card = cards.singleOrNull()
        if (card == null || !acceptsOnFoundation(destination.index, card)) {
            null
        } else {
            copy(foundations = foundations.replaceAt(destination.index, foundations[destination.index] + card))
        }
    }

    is Pile.Tableau -> {
        val head = cards.firstOrNull()
        if (head == null || !acceptsOnTableau(destination.index, head)) {
            null
        } else {
            val added = cards.map { TableauCard(it, faceUp = true) }
            copy(tableau = tableau.replaceAt(destination.index, tableau[destination.index] + added))
        }
    }
}

/** Turn over any column whose new top card is face down. */
private fun Game.flipExposed(): Game {
    var columns = tableau
    for (i in columns.indices) {
        val top = columns[i].lastOrNull() ?: continue
        if (!top.faceUp) {
            columns = columns.replaceAt(i, columns[i].dropLast(1) + top.copy(faceUp = true))
        }
    }
    return if (columns === tableau) this else copy(tableau = columns)
}

private fun <T> List<T>.replaceAt(index: Int, value: T): List<T> =
    toMutableList().also { it[index] = value }
