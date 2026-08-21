package com.thelightphone.solitaire

/**
 * The two games this tool deals.
 *
 * They share a table, a deck and a set of foundations, so they share [Game] and
 * everything built on it — the solver, the hints, the save format and the
 * screen. Only the three things below actually differ, and each of them is
 * asked for by name rather than inferred from the shape of the board, so a new
 * code path cannot quietly get Klondike's answer for a Yukon deal.
 *
 * @property token what a save file calls this game. Written out rather than
 *   taken from [ordinal], so that reordering these entries cannot silently
 *   reinterpret somebody's saved board as the other game.
 */
enum class Variant(val label: String, val token: Char) {

    /** Draw one, unlimited redeals. Twenty-eight cards down, twenty-four in the stock. */
    KLONDIKE("Klondike", 'K'),

    /**
     * The whole deck is on the table from the first move: no stock, five face up
     * cards in every column but the first, and any face up card can be picked up
     * with whatever is sitting on top of it.
     */
    YUKON("Yukon", 'Y');

    /** Yukon deals every card onto the table, so there is nothing left to turn. */
    val hasStock: Boolean get() = this == KLONDIKE

    /**
     * Whether the player can pick these cards up as one.
     *
     * Klondike only carries a group it could have built: face up, descending,
     * alternating colours. Yukon carries any face up group at all, and that one
     * difference is the game — what you drag is usually nonsense, and the reason
     * to drag it is the card underneath.
     */
    fun canPickUp(cards: List<TableauCard>): Boolean {
        if (cards.isEmpty()) return false
        if (cards.any { !it.faceUp }) return false
        return when (this) {
            KLONDIKE -> isOrderedRun(cards)
            YUKON -> true
        }
    }
}

/** Descending in rank and alternating in colour, which is what Klondike carries. */
fun isOrderedRun(cards: List<TableauCard>): Boolean {
    for (i in 0 until cards.lastIndex) {
        val a = cards[i].card
        val b = cards[i + 1].card
        if (a.rank != b.rank + 1 || a.isRed == b.isRed) return false
    }
    return true
}
