package com.thelightphone.solitaire

/**
 * A compact text encoding of a deal so the game survives leaving the tool.
 *
 * One line, seven sections. A card is rank then suit initial ("1S", "13H"); a
 * tableau card adds "+" face up or "-" face down.
 *
 *   2|13S,4H|2C|1H,2H;;;|1S+,7D-;13C+|41|K
 *   ^ ^      ^  ^         ^            ^  ^
 *   | stock  |  foundations, ; between  |  which game
 *   version  waste          tableau     moves
 *                           columns, ; between
 *
 * Version 2 added the last field. A version 1 line is still read, as Klondike,
 * because that is what every one of them is and throwing them away would end
 * somebody's game to save a branch.
 */
object SaveState {

    private const val VERSION = 2

    /** The oldest layout this can still read. */
    private const val OLDEST_VERSION = 1

    fun encode(game: Game): String = listOf(
        VERSION.toString(),
        game.stock.joinToString(",") { it.encode() },
        game.waste.joinToString(",") { it.encode() },
        game.foundations.joinToString(";") { pile -> pile.joinToString(",") { it.encode() } },
        game.tableau.joinToString(";") { column ->
            column.joinToString(",") { it.card.encode() + if (it.faceUp) "+" else "-" }
        },
        game.moves.toString(),
        game.variant.token.toString(),
    ).joinToString("|")

    /** Returns null for anything malformed, so a bad save just starts a new deal. */
    fun decode(text: String?): Game? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            val parts = text.split("|")
            val version = parts[0].toIntOrNull() ?: return null
            if (version < OLDEST_VERSION || version > VERSION) return null
            if (parts.size != if (version >= 2) 7 else 6) return null

            val stock = parts[1].splitCards()
            val waste = parts[2].splitCards()
            val foundations = parts[3].split(";").map { it.splitCards() }
            val tableau = parts[4].split(";").map { column ->
                column.split(",").filter { it.isNotBlank() }.map { token ->
                    val faceUp = when (token.last()) {
                        '+' -> true
                        '-' -> false
                        else -> return null
                    }
                    TableauCard(token.dropLast(1).decodeCard() ?: return null, faceUp)
                }
            }
            val moves = parts[5].toIntOrNull() ?: return null
            val variant = if (version >= 2) {
                val token = parts[6].singleOrNull() ?: return null
                Variant.entries.firstOrNull { it.token == token } ?: return null
            } else {
                Variant.KLONDIKE
            }

            if (foundations.size != Game.FOUNDATIONS) return null
            if (tableau.size != Game.COLUMNS) return null

            val game = Game(stock, waste, foundations, tableau, moves, variant)
            // Refuse anything that isn't a real deal rather than resuming a corrupt board.
            val all = stock + waste + foundations.flatten() + tableau.flatten().map { it.card }
            if (all.size != 52 || all.toSet().size != 52) return null
            game
        }.getOrNull()
    }

    private fun String.splitCards(): List<Card> =
        split(",").filter { it.isNotBlank() }.map { it.decodeCard() ?: return emptyList() }

    private fun Card.encode(): String = "$rank${suit.name.first()}"

    private fun String.decodeCard(): Card? {
        if (length < 2) return null
        val rank = dropLast(1).toIntOrNull() ?: return null
        if (rank !in 1..13) return null
        val suit = Suit.entries.firstOrNull { it.name.first() == last() } ?: return null
        return Card(rank, suit)
    }
}
