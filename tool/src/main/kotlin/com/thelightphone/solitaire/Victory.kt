package com.thelightphone.solitaire

import kotlin.math.abs

/**
 * The payoff: cards launch off the foundations one at a time, bounce along the
 * bottom of the screen and slide out the sides.
 *
 * Plain numbers and no drawing code, so the whole thing can be stepped and
 * checked in a unit test. An animation that never finishes would leave the win
 * screen unreachable, so "it always ends" is a property worth asserting rather
 * than hoping for.
 *
 * Everything is in dp and seconds.
 */

/** Where the cards fly, measured the same way the table is laid out. */
data class CascadeBoard(
    val width: Float,
    val height: Float,
    val cardWidth: Float,
    val cardHeight: Float,
    val foundationX: List<Float>,
    val foundationY: Float,
)

data class FallingCard(
    val card: Card,
    val x: Float,
    val y: Float,
    val velocityX: Float,
    val velocityY: Float,
)

/** A card still waiting its turn, and the foundation it leaves behind. */
data class Launch(val foundation: Int, val card: Card)

data class Cascade(
    val waiting: List<Launch>,
    val flying: List<FallingCard> = emptyList(),
    /** How many cards have left each foundation, so the board can empty as it goes. */
    val launched: List<Int> = List(Game.FOUNDATIONS) { 0 },
    val elapsed: Float = 0f,
    val untilNextLaunch: Float = 0f,
) {
    val finished: Boolean get() = waiting.isEmpty() && flying.isEmpty()
}

object Victory {

    /** Gap between cards leaving. Slow enough to read, quick enough to not drag. */
    const val LAUNCH_INTERVAL = 0.09f

    const val GRAVITY = 1500f

    /** Upward kick, so a card arcs instead of just dropping. */
    const val LIFT = 260f

    /** Energy kept after hitting the bottom. */
    const val BOUNCE = 0.62f

    private const val MIN_SIDEWAYS = 120f
    private const val SIDEWAYS_SPREAD = 160f

    /** Longest the whole thing may run before the UI gives up and shows the panel. */
    const val PATIENCE = 14f

    /** Top card of every foundation first, then the next one down, and so on. */
    fun start(game: Game): Cascade {
        val deepest = game.foundations.maxOfOrNull { it.size } ?: 0
        val waiting = ArrayList<Launch>(52)
        for (row in deepest - 1 downTo 0) {
            for (foundation in game.foundations.indices) {
                game.foundations[foundation].getOrNull(row)?.let {
                    waiting += Launch(foundation, it)
                }
            }
        }
        return Cascade(waiting = waiting)
    }

    /**
     * Advances by [dt] seconds.
     *
     * A card only ever leaves through the sides, and it always keeps the sideways
     * speed it started with, so every card is guaranteed to exit. That is what
     * makes this terminate.
     */
    fun Cascade.step(dt: Float, board: CascadeBoard): Cascade {
        var waiting = this.waiting
        var flying = this.flying
        var launched = this.launched
        var untilNext = untilNextLaunch - dt

        if (untilNext <= 0f && waiting.isNotEmpty()) {
            val next = waiting.first()
            waiting = waiting.subList(1, waiting.size)
            flying = flying + FallingCard(
                card = next.card,
                x = board.foundationX.getOrElse(next.foundation) { 0f },
                y = board.foundationY,
                velocityX = sidewaysSpeed(next),
                velocityY = -LIFT,
            )
            launched = launched.toMutableList().also { counts ->
                if (next.foundation in counts.indices) counts[next.foundation]++
            }
            untilNext = LAUNCH_INTERVAL
        }

        val floor = board.height - board.cardHeight
        flying = flying.mapNotNull { card ->
            var velocityY = card.velocityY + GRAVITY * dt
            var y = card.y + velocityY * dt
            val x = card.x + card.velocityX * dt
            if (y >= floor) {
                y = floor
                velocityY = -abs(velocityY) * BOUNCE
            }
            val goneLeft = x + board.cardWidth < 0f
            val goneRight = x > board.width
            if (goneLeft || goneRight) null else card.copy(x = x, y = y, velocityY = velocityY)
        }

        return copy(
            waiting = waiting,
            flying = flying,
            launched = launched,
            elapsed = elapsed + dt,
            untilNextLaunch = untilNext,
        )
    }

    /**
     * Fans the cards out both ways. Derived from the card itself rather than a
     * random number, so a run can be replayed exactly in a test.
     */
    private fun sidewaysSpeed(launch: Launch): Float {
        val spread = (launch.card.rank * 17 + launch.card.suit.ordinal * 41) % SIDEWAYS_SPREAD.toInt()
        val speed = MIN_SIDEWAYS + spread
        return if (launch.foundation % 2 == 0) -speed else speed
    }
}
