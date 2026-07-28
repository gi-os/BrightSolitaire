package com.thelightphone.solitaire

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private const val UNDO_LIMIT = 120

/** Room at the bottom of the display for the LightOS back button. */
private val BACK_BUTTON_INSET = 44.dp

class SolitaireViewModel(private val store: SolitaireStore) : LightViewModel<Unit>() {

    private val history = ArrayDeque<Game>()

    val game = MutableStateFlow(Game.deal(System.currentTimeMillis()))
    val canUndo = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            val saved = store.load()
            // Only resume if the player hasn't already started playing the fresh deal
            // that was dealt while the read was in flight.
            if (saved != null && game.value.moves == 0 && history.isEmpty()) {
                game.value = saved
            }
        }
    }

    fun newGame() {
        history.clear()
        canUndo.value = false
        game.value = Game.deal(System.currentTimeMillis())
        persist()
    }

    fun undo() {
        val previous = history.removeLastOrNull() ?: return
        game.value = previous
        canUndo.value = history.isNotEmpty()
        persist()
    }

    fun tap(pile: Pile, cardIndex: Int) = commit(game.value.autoMove(pile, cardIndex))

    fun drop(source: Pile, cardIndex: Int, destination: Pile) =
        commit(game.value.move(source, cardIndex, destination))

    override fun onAppPause() {
        super.onAppPause()
        persist()
    }

    private fun commit(next: Game?) {
        val current = game.value
        if (next == null || next == current) return
        history.addLast(current)
        while (history.size > UNDO_LIMIT) history.removeFirst()
        canUndo.value = true
        game.value = next
        persist()
    }

    private fun persist() {
        val snapshot = game.value
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
        val game by viewModel.game.collectAsState()
        val canUndo by viewModel.canUndo.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            val foreground = LightThemeTokens.colors.content
            val background = LightThemeTokens.colors.background
            val subdued = LightThemeTokens.colors.contentSecondary

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
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LightText(
                        text = "New",
                        variant = LightTextVariant.Detail,
                        modifier = Modifier.lightClickable { viewModel.newGame() },
                    )
                    Spacer(Modifier.weight(1f))
                    LightText(
                        text = game.moves.toString(),
                        variant = LightTextVariant.Detail,
                        lighten = true,
                    )
                    Spacer(Modifier.weight(1f))
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

                    for (slot in slots) {
                        val hidden = drag?.let { active ->
                            slot.pile == active.source && slot.cardIndex >= active.cardIndex
                        } == true
                        if (hidden) continue

                        val placement = Modifier.offset(slot.x, slot.y)
                        when (val pile = slot.pile) {
                            Pile.Stock ->
                                if (slot.cardIndex >= 0) {
                                    CardBack(geometry.cardW, geometry.cardH, foreground, background, placement)
                                } else {
                                    EmptySlot(geometry.cardW, geometry.cardH, subdued, placement) {
                                        Box(
                                            Modifier
                                                .size(geometry.cardW * 0.30f)
                                                .border(1.dp, subdued, CircleShape)
                                        )
                                    }
                                }

                            Pile.Waste ->
                                if (slot.cardIndex >= 0) {
                                    CardView(
                                        card = game.waste[slot.cardIndex],
                                        width = geometry.cardW,
                                        height = geometry.cardH,
                                        foreground = foreground,
                                        background = background,
                                        modifier = placement,
                                    )
                                } else {
                                    EmptySlot(geometry.cardW, geometry.cardH, subdued, placement)
                                }

                            is Pile.Foundation ->
                                if (slot.cardIndex >= 0) {
                                    CardView(
                                        card = game.foundations[pile.index][slot.cardIndex],
                                        width = geometry.cardW,
                                        height = geometry.cardH,
                                        foreground = foreground,
                                        background = background,
                                        modifier = placement,
                                    )
                                } else {
                                    EmptySlot(geometry.cardW, geometry.cardH, subdued, placement)
                                }

                            is Pile.Tableau -> {
                                val column = game.tableau[pile.index]
                                val entry = column.getOrNull(slot.cardIndex)
                                when {
                                    entry == null ->
                                        EmptySlot(geometry.cardW, geometry.cardH, subdued, placement)

                                    !entry.faceUp ->
                                        CardBack(geometry.cardW, geometry.cardH, foreground, background, placement)

                                    else ->
                                        CardView(
                                            card = entry.card,
                                            width = geometry.cardW,
                                            height = geometry.cardH,
                                            foreground = foreground,
                                            background = background,
                                            modifier = placement,
                                            showCenterGlyph = slot.cardIndex == column.lastIndex,
                                        )
                                }
                            }
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
                                        val state = viewModel.game.value
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
