package com.thelightphone.solitaire

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private const val UNDO_LIMIT = 120

/** Room at the bottom of the display for the LightOS back button. */
private val BACK_BUTTON_INSET = 44.dp

/** Short enough to stay out of the way, long enough to read as movement. */
private const val FLIGHT_MILLIS = 170

private const val HINT_MILLIS = 3_000L

enum class Notice { DEAD_END, UNWINNABLE }

/** A move in flight, for the tap animation. */
data class MoveAnimation(
    val id: Long,
    val cards: List<Card>,
    val source: Pile,
    val destination: Pile,
)

/**
 * Everything the screen draws, in one object. Keeping the board and the things
 * attached to it in a single flow means a move can never be rendered against a
 * stale animation or a hint that no longer applies.
 */
data class Table(
    val game: Game,
    val animation: MoveAnimation? = null,
    val hint: Action? = null,
    val notice: Notice? = null,
    val checking: Boolean = false,
)

class SolitaireViewModel(private val store: SolitaireStore) : LightViewModel<Unit>() {

    private val history = ArrayDeque<Game>()
    private var hintCursor = 0
    private var animationId = 0L
    private var analysis: Job? = null

    val table = MutableStateFlow(Table(Game.deal(System.currentTimeMillis())))
    val canUndo = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            val saved = store.load()
            val current = table.value
            // Only resume if the player hasn't already started on the fresh deal
            // that was dealt while the read was in flight.
            if (saved != null && current.game.moves == 0 && history.isEmpty()) {
                table.value = Table(saved, notice = noticeFor(saved))
            }
        }
    }

    fun newGame() {
        history.clear()
        canUndo.value = false
        hintCursor = 0
        table.value = Table(Game.deal(System.currentTimeMillis()))
        persist()
    }

    fun undo() {
        val previous = history.removeLastOrNull() ?: return
        canUndo.value = history.isNotEmpty()
        hintCursor = 0
        table.value = Table(previous, notice = noticeFor(previous))
        persist()
    }

    fun tap(pile: Pile, cardIndex: Int) {
        val action = table.value.game.autoAction(pile, cardIndex) ?: return
        commit(action)
    }

    fun drop(source: Pile, cardIndex: Int, destination: Pile) {
        commit(Action.Shift(source, cardIndex, destination), animate = false)
    }

    /**
     * Suggest a move, and quietly check in the background whether the deal is
     * already lost. Tapping again steps to the next best suggestion.
     */
    fun requestHint() {
        val current = table.value
        val hints = current.game.hints()
        if (hints.isEmpty()) {
            table.value = current.copy(hint = null, notice = Notice.DEAD_END)
            return
        }
        val index = ((hintCursor % hints.size) + hints.size) % hints.size
        hintCursor++
        table.value = current.copy(hint = hints[index], checking = analysis?.isActive != true)
        checkWinnable()
    }

    fun clearHint() {
        val current = table.value
        if (current.hint != null) table.value = current.copy(hint = null)
    }

    override fun onAppPause() {
        super.onAppPause()
        persist()
    }

    private fun commit(action: Action, animate: Boolean = true) {
        val current = table.value.game
        val next = current.perform(action) ?: return
        if (next == current) return

        val cards = if (animate) current.cardsMovedBy(action) else emptyList()
        history.addLast(current)
        while (history.size > UNDO_LIMIT) history.removeFirst()
        canUndo.value = true
        hintCursor = 0

        table.value = Table(
            game = next,
            animation = if (cards.isEmpty()) {
                null
            } else {
                MoveAnimation(++animationId, cards, sourceOf(action), destinationOf(action))
            },
            notice = noticeFor(next),
        )
        persist()
    }

    /** Cheap and exact: no move on the table and none anywhere in the stock. */
    private fun noticeFor(game: Game): Notice? =
        if (!game.isWon && game.isDeadEnd()) Notice.DEAD_END else null

    private fun checkWinnable() {
        if (analysis?.isActive == true) return
        val snapshot = table.value.game
        analysis = viewModelScope.launch(Dispatchers.Default) {
            val result = Solver.analyze(snapshot)
            val current = table.value
            // Say nothing if the board moved on while the search was running.
            if (current.game != snapshot) return@launch
            table.value = current.copy(
                checking = false,
                notice = if (result.verdict == Verdict.UNWINNABLE) Notice.UNWINNABLE else current.notice,
            )
        }
    }

    private fun sourceOf(action: Action): Pile = when (action) {
        Action.Draw -> Pile.Stock
        is Action.Shift -> action.source
        is Action.TurnOver -> Pile.Tableau(action.column)
    }

    private fun destinationOf(action: Action): Pile = when (action) {
        Action.Draw -> Pile.Waste
        is Action.Shift -> action.destination
        is Action.TurnOver -> Pile.Tableau(action.column)
    }

    private fun persist() {
        val snapshot = table.value.game
        viewModelScope.launch { store.save(snapshot) }
    }
}

@InitialScreen
class HomeScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<Unit, SolitaireViewModel>(sealedActivity) {

    override val viewModelClass: Class<SolitaireViewModel>
        get() = SolitaireViewModel::class.java

    override fun createViewModel(): SolitaireViewModel =
        SolitaireViewModel(SolitaireStore(lightContext.dataStore))

    @Composable
    override fun Content() {
        val table by viewModel.table.collectAsState()
        val canUndo by viewModel.canUndo.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()
        val game = table.game

        LightTheme(colors = themeColors) {
            val foreground = LightThemeTokens.colors.content
            val background = LightThemeTokens.colors.background
            val subdued = LightThemeTokens.colors.contentSecondary

            LaunchedEffect(table.hint) {
                if (table.hint != null) {
                    delay(HINT_MILLIS)
                    viewModel.clearHint()
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(background)
                    .padding(bottom = BACK_BUTTON_INSET),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LightText(
                        text = "New",
                        variant = LightTextVariant.Detail,
                        modifier = Modifier.lightClickable { viewModel.newGame() },
                    )
                    LightText(
                        text = "Hint",
                        variant = LightTextVariant.Detail,
                        modifier = Modifier.lightClickable { viewModel.requestHint() },
                    )
                    LightText(
                        text = game.moves.toString(),
                        variant = LightTextVariant.Detail,
                        lighten = true,
                    )
                    LightText(
                        text = "Undo",
                        variant = LightTextVariant.Detail,
                        lighten = !canUndo,
                        modifier = Modifier.lightClickable { viewModel.undo() },
                    )
                }

                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    val geometry = remember(maxWidth, maxHeight, game) {
                        geometryFor(maxWidth, maxHeight, game)
                    }
                    val slots = remember(geometry, game) { buildSlots(game, geometry) }
                    var drag by remember { mutableStateOf<DragState?>(null) }
                    var flight by remember { mutableStateOf<Flight?>(null) }
                    val progress = remember { Animatable(0f) }

                    LaunchedEffect(table.animation?.id) {
                        val animation = table.animation
                        if (animation == null) {
                            flight = null
                            return@LaunchedEffect
                        }
                        val next = flightFor(animation, game, geometry, slots)
                        if (next == null) {
                            flight = null
                            return@LaunchedEffect
                        }
                        flight = next
                        progress.snapTo(0f)
                        progress.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(FLIGHT_MILLIS, easing = FastOutSlowInEasing),
                        )
                        flight = null
                    }

                    val hint = table.hint

                    for (slot in slots) {
                        val dragged = drag?.let { active ->
                            slot.pile == active.source && slot.cardIndex >= active.cardIndex
                        } == true
                        val landing = flight?.let { active ->
                            slot.pile == active.destination && slot.cardIndex >= active.firstLandedIndex
                        } == true
                        if (dragged || landing) continue

                        val isHintSource = hint.marksSource(slot)
                        val isHintTarget = hint.marksTarget(slot, game)
                        // Inverted card for the card to move, heavier outline for where it goes.
                        val cardForeground = if (isHintSource) background else foreground
                        val cardBackground = if (isHintSource) foreground else background
                        val outline = if (isHintTarget) foreground else subdued
                        val border = if (isHintSource || isHintTarget) 2.dp else 1.dp
                        val placement = Modifier.offset(slot.x, slot.y)

                        when (val pile = slot.pile) {
                            Pile.Stock ->
                                if (slot.cardIndex >= 0) {
                                    CardBack(
                                        width = geometry.cardW,
                                        height = geometry.cardH,
                                        foreground = foreground,
                                        background = background,
                                        modifier = placement,
                                        emphasized = isHintSource,
                                    )
                                } else {
                                    EmptySlot(geometry.cardW, geometry.cardH, outline, placement, border) {
                                        Box(
                                            Modifier
                                                .size(geometry.cardW * 0.30f)
                                                .border(border, outline, CircleShape)
                                        )
                                    }
                                }

                            Pile.Waste ->
                                if (slot.cardIndex >= 0) {
                                    CardView(
                                        card = game.waste[slot.cardIndex],
                                        width = geometry.cardW,
                                        height = geometry.cardH,
                                        foreground = cardForeground,
                                        background = cardBackground,
                                        modifier = placement,
                                        borderWidth = border,
                                    )
                                } else {
                                    EmptySlot(geometry.cardW, geometry.cardH, outline, placement, border)
                                }

                            is Pile.Foundation ->
                                if (slot.cardIndex >= 0) {
                                    CardView(
                                        card = game.foundations[pile.index][slot.cardIndex],
                                        width = geometry.cardW,
                                        height = geometry.cardH,
                                        foreground = cardForeground,
                                        background = cardBackground,
                                        modifier = placement,
                                        borderWidth = border,
                                    )
                                } else {
                                    EmptySlot(geometry.cardW, geometry.cardH, outline, placement, border)
                                }

                            is Pile.Tableau -> {
                                val column = game.tableau[pile.index]
                                val entry = column.getOrNull(slot.cardIndex)
                                when {
                                    entry == null ->
                                        EmptySlot(geometry.cardW, geometry.cardH, outline, placement, border)

                                    !entry.faceUp ->
                                        CardBack(
                                            width = geometry.cardW,
                                            height = geometry.cardH,
                                            foreground = foreground,
                                            background = background,
                                            modifier = placement,
                                        )

                                    else ->
                                        CardView(
                                            card = entry.card,
                                            width = geometry.cardW,
                                            height = geometry.cardH,
                                            foreground = cardForeground,
                                            background = cardBackground,
                                            modifier = placement,
                                            showCenterGlyph = slot.cardIndex == column.lastIndex,
                                            borderWidth = border,
                                        )
                                }
                            }
                        }
                    }

                    flight?.let { active ->
                        val t = progress.value
                        val x = active.startX + (active.endX - active.startX) * t
                        val y = active.startY + (active.endY - active.startY) * t
                        active.cards.forEachIndexed { i, card ->
                            CardView(
                                card = card,
                                width = geometry.cardW,
                                height = geometry.cardH,
                                foreground = foreground,
                                background = background,
                                modifier = Modifier.offset(x, y + geometry.fanUp * i),
                                showCenterGlyph = i == active.cards.lastIndex,
                            )
                        }
                    }

                    drag?.let { active ->
                        active.cards.forEachIndexed { i, card ->
                            CardView(
                                card = card,
                                width = geometry.cardW,
                                height = geometry.cardH,
                                foreground = foreground,
                                background = background,
                                modifier = Modifier.offset(
                                    x = active.originX + active.dx,
                                    y = active.originY + active.dy + geometry.fanUp * i,
                                ),
                                showCenterGlyph = i == active.cards.lastIndex,
                            )
                        }
                    }

                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(slots) {
                                detectTapGestures { offset ->
                                    val slot = slots.hit(offset.x.toDp(), offset.y.toDp())
                                    if (slot != null) viewModel.tap(slot.pile, slot.cardIndex)
                                }
                            }
                            .pointerInput(slots) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        val slot = slots.hit(offset.x.toDp(), offset.y.toDp())
                                        val state = viewModel.table.value.game
                                        if (slot != null &&
                                            slot.cardIndex >= 0 &&
                                            state.isDraggable(slot.pile, slot.cardIndex)
                                        ) {
                                            drag = DragState(
                                                source = slot.pile,
                                                cardIndex = slot.cardIndex,
                                                cards = state.cardsAt(slot.pile, slot.cardIndex),
                                                originX = slot.x,
                                                originY = slot.y,
                                            )
                                        }
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        drag = drag?.let {
                                            it.copy(
                                                dx = it.dx + amount.x.toDp(),
                                                dy = it.dy + amount.y.toDp(),
                                            )
                                        }
                                    },
                                    onDragEnd = {
                                        val active = drag
                                        if (active != null) {
                                            val centerX = active.originX + active.dx + geometry.cardW / 2
                                            val centerY = active.originY + active.dy + geometry.cardH / 2
                                            val target = dropTarget(geometry, centerX, centerY)
                                            if (target != null) {
                                                viewModel.drop(active.source, active.cardIndex, target)
                                            }
                                        }
                                        drag = null
                                    },
                                    onDragCancel = { drag = null },
                                )
                            }
                    )

                    val notice = when {
                        table.notice == Notice.UNWINNABLE -> "This deal can't be won"
                        table.notice == Notice.DEAD_END -> "No moves left"
                        table.checking -> "Checking"
                        else -> null
                    }
                    if (notice != null && !game.isWon) {
                        LightText(
                            text = notice,
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .background(background)
                                .padding(horizontal = 10.dp, vertical = 2.dp),
                        )
                    }

                    if (game.isWon) {
                        Box(
                            modifier = Modifier.fillMaxSize().background(background),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                LightText(text = "You win", variant = LightTextVariant.Heading)
                                LightText(
                                    text = "${game.moves} moves",
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
                                )
                                LightText(
                                    text = "Deal again",
                                    variant = LightTextVariant.Copy,
                                    modifier = Modifier.lightClickable { viewModel.newGame() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- hint marking

private fun Action?.marksSource(slot: Slot): Boolean = when (this) {
    null -> false
    Action.Draw -> slot.pile == Pile.Stock
    is Action.Shift -> slot.pile == source && slot.cardIndex >= cardIndex
    is Action.TurnOver -> slot.pile == Pile.Tableau(column)
}

private fun Action?.marksTarget(slot: Slot, game: Game): Boolean {
    val shift = this as? Action.Shift ?: return false
    if (slot.pile != shift.destination) return false
    return slot.cardIndex == game.topIndexOf(shift.destination)
}

private fun Game.topIndexOf(pile: Pile): Int = when (pile) {
    Pile.Stock -> stock.lastIndex
    Pile.Waste -> waste.lastIndex
    is Pile.Foundation -> foundations[pile.index].lastIndex
    is Pile.Tableau -> tableau[pile.index].lastIndex
}

// ---------------------------------------------------------------- layout

private data class TableGeometry(
    val cardW: Dp,
    val cardH: Dp,
    val gap: Dp,
    val padding: Dp,
    val tableauY: Dp,
    val fanUp: Dp,
    val fanDown: Dp,
) {
    fun columnX(index: Int): Dp = padding + (cardW + gap) * index
}

private data class Slot(
    val pile: Pile,
    val cardIndex: Int,
    val x: Dp,
    val y: Dp,
    val w: Dp,
    val h: Dp,
)

private data class DragState(
    val source: Pile,
    val cardIndex: Int,
    val cards: List<Card>,
    val originX: Dp,
    val originY: Dp,
    val dx: Dp = 0.dp,
    val dy: Dp = 0.dp,
)

private data class Flight(
    val cards: List<Card>,
    val destination: Pile,
    val firstLandedIndex: Int,
    val startX: Dp,
    val startY: Dp,
    val endX: Dp,
    val endY: Dp,
)

/**
 * Where the moved cards came from and where they landed.
 *
 * The board has already been updated by the time this runs, which is fine: the
 * cards that left a column were on the end of it, so the gap they left is
 * exactly where the next card in that column would sit.
 */
private fun flightFor(
    animation: MoveAnimation,
    game: Game,
    geometry: TableGeometry,
    slots: List<Slot>,
): Flight? {
    if (animation.cards.isEmpty()) return null
    val landedFrom = game.pileSize(animation.destination) - animation.cards.size
    if (landedFrom < 0) return null
    val target = slots.firstOrNull {
        it.pile == animation.destination && it.cardIndex == landedFrom
    } ?: return null

    val startX: Dp
    val startY: Dp
    when (val source = animation.source) {
        is Pile.Tableau -> {
            startX = geometry.columnX(source.index)
            var y = geometry.tableauY
            for (entry in game.tableau[source.index]) {
                y += if (entry.faceUp) geometry.fanUp else geometry.fanDown
            }
            startY = y
        }

        else -> {
            val origin = slots.firstOrNull { it.pile == source } ?: return null
            startX = origin.x
            startY = origin.y
        }
    }

    return Flight(
        cards = animation.cards,
        destination = animation.destination,
        firstLandedIndex = landedFrom,
        startX = startX,
        startY = startY,
        endX = target.x,
        endY = target.y,
    )
}

private fun Game.pileSize(pile: Pile): Int = when (pile) {
    Pile.Stock -> stock.size
    Pile.Waste -> waste.size
    is Pile.Foundation -> foundations[pile.index].size
    is Pile.Tableau -> tableau[pile.index].size
}

private fun geometryFor(width: Dp, height: Dp, game: Game): TableGeometry {
    val padding = 6.dp
    val gap = 4.dp
    val cardW = (width - padding * 2 - gap * 6) / 7
    val cardH = cardW * 1.42f
    val tableauY = cardH + 14.dp

    var fanUp = cardH * 0.30f
    var fanDown = cardH * 0.15f

    val tallest = game.tableau.maxOfOrNull { column ->
        val down = column.count { !it.faceUp }
        val up = column.size - down
        if (up > 0) {
            down * fanDown.value + (up - 1) * fanUp.value
        } else {
            maxOf(0f, (down - 1) * fanDown.value)
        }
    } ?: 0f

    val room = (height - tableauY - cardH - 4.dp).value
    if (tallest > room && tallest > 0f) {
        val scale = (room / tallest).coerceIn(0.30f, 1f)
        fanUp *= scale
        fanDown *= scale
    }

    return TableGeometry(cardW, cardH, gap, padding, tableauY, fanUp, fanDown)
}

private fun buildSlots(game: Game, geometry: TableGeometry): List<Slot> {
    val slots = ArrayList<Slot>(64)
    fun add(pile: Pile, cardIndex: Int, x: Dp, y: Dp) {
        slots.add(Slot(pile, cardIndex, x, y, geometry.cardW, geometry.cardH))
    }

    add(Pile.Stock, game.stock.lastIndex, geometry.columnX(0), 0.dp)
    add(Pile.Waste, game.waste.lastIndex, geometry.columnX(1), 0.dp)
    for (i in 0 until Game.FOUNDATIONS) {
        add(Pile.Foundation(i), game.foundations[i].lastIndex, geometry.columnX(3 + i), 0.dp)
    }

    for (col in 0 until Game.COLUMNS) {
        val column = game.tableau[col]
        val x = geometry.columnX(col)
        if (column.isEmpty()) {
            add(Pile.Tableau(col), -1, x, geometry.tableauY)
            continue
        }
        var y = geometry.tableauY
        column.forEachIndexed { index, entry ->
            add(Pile.Tableau(col), index, x, y)
            y += if (entry.faceUp) geometry.fanUp else geometry.fanDown
        }
    }
    return slots
}

/** Topmost slot under the point. Slots are built back to front. */
private fun List<Slot>.hit(x: Dp, y: Dp): Slot? = lastOrNull {
    x >= it.x && x < it.x + it.w && y >= it.y && y < it.y + it.h
}

private fun dropTarget(geometry: TableGeometry, x: Dp, y: Dp): Pile? {
    val slack = geometry.gap / 2
    if (y < geometry.tableauY - 7.dp) {
        for (i in 0 until Game.FOUNDATIONS) {
            val left = geometry.columnX(3 + i)
            if (x >= left - slack && x < left + geometry.cardW + slack) return Pile.Foundation(i)
        }
        return null
    }
    for (i in 0 until Game.COLUMNS) {
        val left = geometry.columnX(i)
        if (x >= left - slack && x < left + geometry.cardW + slack) return Pile.Tableau(i)
    }
    return null
}
