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
import kotlinx.coroutines.withContext

private const val UNDO_LIMIT = 120

/** Room at the bottom of the display for the LightOS back button. */
private val BACK_BUTTON_INSET = 44.dp

/** Short enough to stay out of the way, long enough to read as movement. */
private const val FLIGHT_MILLIS = 170

private const val HINT_MILLIS = 3_000L

/**
 * Between the moves of an automatic finish, and the length of each flight
 * inside it. Quicker than a move you made yourself, because a hundred of them
 * go past and none of them was your decision.
 *
 * The two are the same number on purpose: a card has to land before the next
 * one leaves, or every flight is cut off part way and the cascade reads as a
 * string of jumps rather than as cards being played.
 */
private const val FINISH_MILLIS = 90

enum class Notice { DEAD_END, UNWINNABLE, NO_FINISH }

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
    /** A finish is being looked for, or being played out. */
    val finishing: Boolean = false,
)

class SolitaireViewModel(private val store: SolitaireStore) : LightViewModel<Unit>() {

    private val history = ArrayDeque<Game>()
    private var hintCursor = 0
    private var animationId = 0L
    private var analysis: Job? = null
    private var finish: Job? = null
    private var hurry = false

    val table = MutableStateFlow(Table(Game.deal(Variant.KLONDIKE, System.currentTimeMillis())))
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

    /** Deals the game you are already playing unless you name another one. */
    fun newGame(variant: Variant = table.value.game.variant) {
        stopFinishing()
        history.clear()
        canUndo.value = false
        hintCursor = 0
        table.value = Table(Game.deal(variant, System.currentTimeMillis()))
        persist()
    }

    fun undo() {
        stopFinishing()
        val previous = history.removeLastOrNull() ?: return
        canUndo.value = history.isNotEmpty()
        hintCursor = 0
        table.value = Table(previous, notice = noticeFor(previous))
        persist()
    }

    fun tap(pile: Pile, cardIndex: Int) {
        if (table.value.finishing) return
        val action = table.value.game.autoAction(pile, cardIndex) ?: return
        commit(action)
    }

    fun drop(source: Pile, cardIndex: Int, destination: Pile) {
        if (table.value.finishing) return
        commit(Action.Shift(source, cardIndex, destination), animate = false)
    }

    /**
     * Play the rest of the game.
     *
     * The whole line is found before the first card moves. A finish that ran out
     * halfway would leave the board rearranged by moves nobody chose, which is
     * worse than being told it could not be done — so if there is no line, the
     * board is not touched at all.
     *
     * Finding it runs off the main thread because a Yukon board can take a
     * moment. Playing it runs back on, a move at a time, because watching it is
     * the point; a tap gives up on the watching and takes the rest at once.
     */
    fun finishGame() {
        if (finish?.isActive == true) return
        val snapshot = table.value.game
        if (snapshot.isWon) return

        hurry = false
        table.value = table.value.copy(hint = null, notice = null, finishing = true)

        finish = viewModelScope.launch {
            val line = withContext(Dispatchers.Default) { snapshot.finishingLine() }
            // Say nothing if the board moved on while the search was running.
            if (table.value.game != snapshot) return@launch

            if (line == null) {
                table.value = table.value.copy(finishing = false, notice = Notice.NO_FINISH)
                return@launch
            }
            for (action in line) {
                commit(action, save = false)
                if (!hurry) delay(FINISH_MILLIS.toLong())
            }
            table.value = table.value.copy(finishing = false)
            // One write at the end rather than one per move: nothing here is a
            // decision worth preserving halfway through, and onAppPause still
            // catches a finish interrupted by leaving the tool.
            persist()
        }
    }

    /** Stop watching and take the remaining moves at once. */
    fun hurryFinish() {
        hurry = true
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

    private fun stopFinishing() {
        finish?.cancel()
        finish = null
        hurry = false
        if (table.value.finishing) table.value = table.value.copy(finishing = false)
    }

    private fun commit(action: Action, animate: Boolean = true, save: Boolean = true) {
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
            finishing = table.value.finishing,
        )
        if (save) persist()
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
        var choosing by remember { mutableStateOf(false) }

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
                        modifier = Modifier.lightClickable { choosing = true },
                    )
                    LightText(
                        text = "Hint",
                        variant = LightTextVariant.Detail,
                        modifier = Modifier.lightClickable { viewModel.requestHint() },
                    )
                    // Only once there is nothing left to turn over. Before that
                    // it would be offering to play the part that is still a game.
                    if (game.isFullyRevealed && !game.isWon) {
                        LightText(
                            text = "Finish",
                            variant = LightTextVariant.Detail,
                            lighten = table.finishing,
                            modifier = Modifier.lightClickable { viewModel.finishGame() },
                        )
                    }
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
                    // Foundations empty out as the waterfall launches them.
                    var emptied by remember(game) { mutableStateOf(List(Game.FOUNDATIONS) { 0 }) }

                    val animation = table.animation
                    // Worked out during composition rather than in an effect. An
                    // effect runs after the frame it belongs to has been drawn, so
                    // starting the flight there draws the card at its destination
                    // for one frame first, which reads as a teleport followed by an
                    // animation. Deriving it here means the very first frame of the
                    // new board already has the card hidden and in flight.
                    val flightPlan = remember(animation?.id) {
                        animation?.let { flightFor(it, game, geometry, slots) }
                    }
                    val progress = remember(animation?.id) { Animatable(0f) }
                    var landed by remember(animation?.id) { mutableStateOf(false) }
                    val flight = if (landed) null else flightPlan

                    LaunchedEffect(animation?.id) {
                        if (flightPlan == null) return@LaunchedEffect
                        progress.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(
                                durationMillis = if (table.finishing) FINISH_MILLIS else FLIGHT_MILLIS,
                                easing = FastOutSlowInEasing,
                            ),
                        )
                        landed = true
                    }

                    val hint = table.hint

                    for (slot in slots) {
                        val dragged = drag?.let { active ->
                            slot.pile == active.source && slot.cardIndex >= active.cardIndex
                        } == true
                        if (dragged) continue

                        // A card in flight must not also be sitting on its
                        // destination. For the stock, waste and foundations only the
                        // top card is drawn, so step down to the one underneath
                        // instead of leaving the pile looking empty while the card
                        // travels.
                        val cardIndex = flight.visibleIndexFor(slot) ?: continue

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
                                if (cardIndex >= 0) {
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
                                if (cardIndex >= 0) {
                                    CardView(
                                        card = game.waste[cardIndex],
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

                            is Pile.Foundation -> {
                                val index = cardIndex - emptied.getOrElse(pile.index) { 0 }
                                if (index >= 0) {
                                    CardView(
                                        card = game.foundations[pile.index][index],
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
                            }

                            is Pile.Tableau -> {
                                val column = game.tableau[pile.index]
                                val entry = column.getOrNull(cardIndex)
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
                                            showCenterGlyph = cardIndex == column.lastIndex,
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
                            .pointerInput(slots, table.finishing) {
                                detectTapGestures { offset ->
                                    if (table.finishing) {
                                        viewModel.hurryFinish()
                                        return@detectTapGestures
                                    }
                                    val slot = slots.hit(offset.x.toDp(), offset.y.toDp())
                                    if (slot != null) viewModel.tap(slot.pile, slot.cardIndex)
                                }
                            }
                            .pointerInput(slots) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        val slot = slots.hit(offset.x.toDp(), offset.y.toDp())
                                        val state = viewModel.table.value.game
                                        if (!table.finishing &&
                                            slot != null &&
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
                        table.finishing -> "Finishing"
                        table.notice == Notice.UNWINNABLE -> "This deal can't be won"
                        table.notice == Notice.DEAD_END -> "No moves left"
                        // Not the same as unwinnable, and it must not read like
                        // it: the search that says a deal is lost is the other
                        // one, and it takes its time over it.
                        table.notice == Notice.NO_FINISH -> "No quick finish from here"
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
                        var waterfallDone by remember(game) { mutableStateOf(false) }

                        if (!waterfallDone) {
                            VictoryWaterfall(
                                game = game,
                                cardWidth = geometry.cardW,
                                cardHeight = geometry.cardH,
                                foundationX = remember(geometry) {
                                    (0 until Game.FOUNDATIONS).map { geometry.columnX(3 + it) }
                                },
                                foundationY = 0.dp,
                                boardWidth = maxWidth,
                                boardHeight = maxHeight,
                                foreground = foreground,
                                background = background,
                                onLaunchedChange = { emptied = it },
                                onFinished = { waterfallDone = true },
                            )
                            // Tap anywhere to cut it short.
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .lightClickable { waterfallDone = true }
                            )
                        } else {
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

                    // Dealing throws away the board you are on, so it is a
                    // decision either way. Making it name the game turns that
                    // into the one place either game can be reached from, which
                    // beats a fifth control in a bar four items wide.
                    if (choosing) {
                        Box(
                            modifier = Modifier.fillMaxSize().background(background),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                LightText(
                                    text = "New game",
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    modifier = Modifier.padding(bottom = 16.dp),
                                )
                                for (variant in Variant.entries) {
                                    LightText(
                                        text = variant.label,
                                        variant = LightTextVariant.Copy,
                                        modifier = Modifier
                                            .padding(vertical = 6.dp)
                                            .lightClickable {
                                                choosing = false
                                                viewModel.newGame(variant)
                                            },
                                    )
                                }
                                LightText(
                                    text = "Keep playing",
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    modifier = Modifier
                                        .padding(top = 20.dp)
                                        .lightClickable { choosing = false },
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

private fun Flight?.visibleIndexFor(slot: Slot): Int? = visibleCardIndex(
    pile = slot.pile,
    cardIndex = slot.cardIndex,
    landing = this?.destination,
    firstLandedIndex = this?.firstLandedIndex ?: 0,
)

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

    // Yukon has no stock, so it gets no stock and no waste slot rather than two
    // empty outlines that never do anything. Nothing else needs to know: a pile
    // with no slot is a pile nothing can be dropped on, drawn from or tapped.
    if (game.variant.hasStock) {
        add(Pile.Stock, game.stock.lastIndex, geometry.columnX(0), 0.dp)
        add(Pile.Waste, game.waste.lastIndex, geometry.columnX(1), 0.dp)
    }
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
