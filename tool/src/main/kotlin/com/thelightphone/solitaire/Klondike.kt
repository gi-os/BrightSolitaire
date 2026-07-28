package com.thelightphone.solitaire

/**
 * Klondike solitaire, draw one, unlimited redeals.
 *
 * [Game] is immutable. Every legal action returns a new [Game] or null if the
 * action is not legal. That makes undo a list of old states and nothing else.
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
) {
    val isWon: Boolean get() = foundations.sumOf { it.size } == 52

    companion object {
        const val COLUMNS = 7
        const val FOUNDATIONS = 4

        fun deal(seed: Long): Game {
            val deck = shuffledDeck(seed).toMutableList()
            val columns = ArrayList<List<TableauCard>>(COLUMNS)
            for (col in 0 until COLUMNS) {
                val cards = ArrayList<TableauCard>(col + 1)
                for (row in 0..col) {
                    val card = deck.removeAt(deck.lastIndex)
                    cards.add(TableauCard(card, faceUp = row == col))
                }
                columns.add(cards)
            }
            return Game(
                stock = deck.toList(),
                waste = emptyList(),
                foundations = List(FOUNDATIONS) { emptyList() },
                tableau = columns,
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

/** A movable run is face up and descends in alternating colors. */
fun isValidRun(cards: List<TableauCard>): Boolean {
    if (cards.isEmpty()) return false
    if (cards.any { !it.faceUp }) return false
    for (i in 0 until cards.lastIndex) {
        val a = cards[i].card
        val b = cards[i + 1].card
        if (a.rank != b.rank + 1 || a.isRed == b.isRed) return false
    }
    return true
}

/** True if [cardIndex] in column [index] is the head of a run the player can pick up. */
fun Game.isDraggable(pile: Pile, cardIndex: Int): Boolean = when (pile) {
    Pile.Stock -> false
    Pile.Waste -> waste.isNotEmpty() && cardIndex == waste.lastIndex
    is Pile.Foundation -> foundations[pile.index].isNotEmpty() &&
        cardIndex == foundations[pile.index].lastIndex
    is Pile.Tableau -> {
        val column = tableau[pile.index]
        cardIndex in column.indices && isValidRun(column.drop(cardIndex))
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
 */
fun Game.autoMove(source: Pile, cardIndex: Int): Game? = when (source) {
    Pile.Stock -> draw()

    Pile.Waste -> {
        val card = waste.lastOrNull()
        if (card == null) null else bestDestination(card, single = true, fromColumn = -1)
            ?.let { move(source, waste.lastIndex, it) }
    }

    is Pile.Foundation -> null // pull cards back off a foundation by dragging

    is Pile.Tableau -> {
        val column = tableau[source.index]
        if (cardIndex !in column.indices) {
            null
        } else if (!column[cardIndex].faceUp) {
            if (cardIndex == column.lastIndex) flipTop(source.index) else null
        } else {
            val run = column.drop(cardIndex)
            if (!isValidRun(run)) {
                null
            } else {
                val head = run.first().card
                // Moving a whole column onto an empty column gains nothing.
                val allowEmpty = cardIndex > 0
                bestDestination(head, single = run.size == 1, fromColumn = source.index, allowEmpty = allowEmpty)
                    ?.let { move(source, cardIndex, it) }
            }
        }
    }
}

private fun Game.bestDestination(
    card: Card,
    single: Boolean,
    fromColumn: Int,
    allowEmpty: Boolean = true,
): Pile? {
    if (single) {
        for (i in 0 until Game.FOUNDATIONS) {
            if (acceptsOnFoundation(i, card)) return Pile.Foundation(i)
        }
    }
    for (i in 0 until Game.COLUMNS) {
        if (i == fromColumn) continue
        if (tableau[i].isEmpty() && !allowEmpty) continue
        if (acceptsOnTableau(i, card)) return Pile.Tableau(i)
    }
    return null
}

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
            if (!isValidRun(run)) {
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
