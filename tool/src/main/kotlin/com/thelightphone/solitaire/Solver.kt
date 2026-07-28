package com.thelightphone.solitaire

/**
 * Answers "can this still be won?" honestly.
 *
 * Depth first search over every legal action with a table of positions already
 * seen. Three outcomes, and the difference between the last two matters:
 *
 *  - [Verdict.WINNABLE]   a win was found, so one exists.
 *  - [Verdict.UNWINNABLE] the search ran out of positions to try before it ran
 *                         out of budget. Every position reachable from here has
 *                         been visited and none of them wins. That is a proof,
 *                         not a guess.
 *  - [Verdict.UNKNOWN]    the budget ran out first. Says nothing either way.
 *
 * Klondike solvability is hard in general, so [Verdict.UNKNOWN] is the common
 * answer from a fresh deal. Positions that are actually lost tend to have a
 * small reachable space, which is exactly when the proof lands.
 */
enum class Verdict { WINNABLE, UNWINNABLE, UNKNOWN }

data class Analysis(
    val verdict: Verdict,
    val positionsVisited: Int,
    /**
     * The moves that win, in order, when [verdict] is [Verdict.WINNABLE].
     *
     * The whole line is returned rather than just the next move because a single
     * move is not enough to play on. Two positions can each be one move from the
     * other on some winning path, so repeatedly asking for "the next move" can
     * bounce between them forever. Following the line does not.
     */
    val line: List<Action> = emptyList(),
) {
    val firstMove: Action? get() = line.firstOrNull()
}

object Solver {

    /**
     * Positions to try before giving up. Each one costs a state key and a move
     * list, so this also caps memory at a few megabytes.
     */
    const val DEFAULT_BUDGET: Int = 60_000

    fun analyze(start: Game, budget: Int = DEFAULT_BUDGET): Analysis {
        if (start.isWon) return Analysis(Verdict.WINNABLE, 0)

        val seen = HashSet<String>()
        seen.add(start.stateKey())

        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(start, start.searchActions()))
        var visited = 0

        while (stack.isNotEmpty()) {
            val frame = stack.last()
            if (frame.next >= frame.actions.size) {
                stack.removeLast()
                continue
            }
            val action = frame.actions[frame.next++]
            val next = frame.game.perform(action) ?: continue

            if (++visited > budget) return Analysis(Verdict.UNKNOWN, visited)

            // The stack is the path from the start, so each frame's last taken
            // action spells out the line, ending with the one just played.
            if (next.isWon) {
                return Analysis(Verdict.WINNABLE, visited, stack.map { it.actions[it.next - 1] })
            }
            if (!seen.add(next.stateKey())) continue

            stack.addLast(Frame(next, next.searchActions()))
        }

        return Analysis(Verdict.UNWINNABLE, visited)
    }

    private class Frame(
        val game: Game,
        val actions: List<Action>,
    ) {
        var next: Int = 0
    }
}

/**
 * A position as a string, one character per card.
 *
 * The mapping is one to one, so two different positions can never collide. The
 * proof of unwinnability depends on that: a hash with collisions could drop a
 * branch that wins and still claim it searched everything.
 *
 * The move counter is deliberately left out. It has no bearing on whether a
 * position can be won, and including it would make every position look new.
 */
internal fun Game.stateKey(): String {
    val out = StringBuilder(72)

    fun append(card: Card, faceUp: Boolean) {
        val code = card.suit.ordinal * 13 + card.rank // 1..52
        out.append((code + if (faceUp) FACE_UP_BASE else FACE_DOWN_BASE).toChar())
    }

    stock.forEach { append(it, true) }
    out.append(SEPARATOR)
    waste.forEach { append(it, true) }
    out.append(SEPARATOR)
    foundations.forEach { pile ->
        pile.forEach { append(it, true) }
        out.append(SEPARATOR)
    }
    tableau.forEach { column ->
        column.forEach { append(it.card, it.faceUp) }
        out.append(SEPARATOR)
    }
    return out.toString()
}

// Card codes sit well clear of the separator so the encoding stays unambiguous.
private const val FACE_UP_BASE = 200
private const val FACE_DOWN_BASE = 400
private const val SEPARATOR = '|'
