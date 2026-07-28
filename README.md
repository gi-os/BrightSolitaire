# LightSolitaire

Klondike solitaire for the Light Phone III. Draw one, unlimited redeals. A LightOS tool
built on the official [light-sdk](https://github.com/lightphone/light-sdk) with Kotlin,
Jetpack Compose, `LightScreen` and `LightViewModel`, themed with `sdk:ui`.

[Download the latest APK](https://github.com/gi-os/LightSolitaire/releases/latest). See
[INSTALL.md](INSTALL.md).

Part of the [gi-os Light App collection](#the-gi-os-light-app-collection).

## Reading a one-bit deck

The panel is black and white, so shape carries the suit color instead of hue.

- The app draws spades and clubs filled.
- It draws hearts and diamonds as outlines.

The alternating-color rule stays readable at a glance and needs no legend. A card face
uses the theme background with a hairline border. A card back is solid `content` with an
inset frame, so a face-down pile reads as a dark block from across the table. The whole
thing follows the LightOS theme and flips with light and dark mode.

## Controls

| Action | What happens |
| --- | --- |
| Tap a card | It goes to a foundation if it fits, otherwise to the leftmost legal column |
| Tap a face-down card | It turns over, if it sits at the bottom of its column |
| Tap the stock | Draw one. Tap the empty ring to redeal the waste. |
| Drag | Manual placement, including a multi-card run, or pulling a card back off a foundation |
| Hint | Suggests a move. Tap again for the next one. |
| New, Undo | Top bar. Undo remembers 120 moves. |

Tapping a foundation does nothing on purpose, so you cannot unstack a suit you already
banked. Auto-move also refuses to shuffle a lone king between two empty columns, which
would only burn a move.

A tapped card travels to where it lands. The move takes 170 ms, which is long enough to
show you where the card went and short enough to stay out of the way. A drag does not
animate, because your finger already did.

The game saves to the tool DataStore after every move and on pause, so leaving and
coming back drops you on the same board. The app throws away a save that does not
decode to a legal 52-card deal, and deals you a fresh game instead of a corrupt one.

Layout measures from the real screen size rather than a hardcoded number, so it survives
an emulator at another resolution. On an LP3, with about 411 x 472 dp of usable space,
that works out to a 53 dp card. A long column compresses its fan spacing instead of
running off the bottom, and the bottom 44 dp stays clear for the LightOS back button.

## Hints, and when a deal is dead

A hint draws the card to move inverted, and puts a heavier outline on the square it goes
to. The app ranks suggestions: bank a card if it can, otherwise uncover a face-down card,
otherwise empty a column, otherwise play off the waste. Tap Hint again for the next one.
It never suggests taking a card back off a foundation. It suggests a draw when the table
really has nothing.

Two different messages can appear at the bottom of the board. The difference matters.

**No moves left** is exact and immediate. The app checks the table, then walks every card
in the stock for anything playable. When it shows this, the deal is over. Undo, or deal
again.

**This deal cannot be won** is a proof, and it appears only when the proof is cheap. Hint
also starts a search in the background. The search walks positions you can still reach,
and keys each one so that no two positions can be confused for each other. If it runs out
of positions before it runs out of budget, then it visited every position you can reach
and none of them wins. The deal is lost.

If the search hits the budget first, the app says nothing.

Klondike is hard to solve in general, so an honest tool has to be able to shrug. This
message lands on a board that is nearly dead, where little space is left. A fresh deal
that happens to be unwinnable usually gets no flag, because the proof would cost more
time than a phone should spend. The app never claims a deal is lost when it is not.

## Layout of this repository

This is the light-sdk tree. The game lives in the `tool/` module that the SDK reserves
for exactly that. Everything else stays upstream and untouched, so a rebase stays
cheap.

| Path | What it is |
| --- | --- |
| `tool/src/main/kotlin/com/thelightphone/solitaire/Klondike.kt` | Every rule. Immutable `Game`, no Android imports. |
| `tool/src/main/kotlin/com/thelightphone/solitaire/Moves.kt` | Moves as data: the generator, hint ranking, the dead-end check |
| `tool/src/main/kotlin/com/thelightphone/solitaire/Solver.kt` | Can this still be won? Winnable, unwinnable, or unknown. |
| `tool/src/main/kotlin/com/thelightphone/solitaire/Cards.kt` | Suits, cards, seeded deck |
| `tool/src/main/kotlin/com/thelightphone/solitaire/CardView.kt` | Canvas suit glyphs, card face, card back |
| `tool/src/main/kotlin/com/thelightphone/solitaire/HomeScreen.kt` | `@InitialScreen`, view model, table geometry, tap and drag |
| `tool/src/main/kotlin/com/thelightphone/solitaire/SaveState.kt` | One-line text encoding of a deal |
| `tool/src/main/kotlin/com/thelightphone/solitaire/SolitaireStore.kt` | DataStore read and write |
| `tool/src/test/kotlin/com/thelightphone/solitaire/` | JVM unit tests, which CI runs before it builds anything |
| `tool/lighttool.toml` | Tool identity and version. The release workflow checks the tag against it. |
| `sdk/`, `plugin/`, `examples/`, `docs/` | Upstream light-sdk |

## Build

```sh
git clone https://github.com/gi-os/LightSolitaire.git
cd LightSolitaire
./gradlew :tool:testDebugUnitTest :tool:assembleDebug
```

This repo vendors the SDK, so the build resolves nothing from GitHub Packages.

To run against the LightOS emulator, set
`serverPackage = "com.thelightphone.sdk.emulator"` in `tool/lighttool.toml`. Set it back
to `com.lightos` before a device build.

## Tests

The rules carry no Android dependency, so all of this runs as a JVM unit test. CI builds
no APK until it passes.

`KlondikeTest` covers the deal, the stock cycle and redeal order, what a foundation and a
column accept, run validity, what you can pick up, and win detection. It then plays 200
deals with a greedy policy. After every single position it asserts that all 52 cards are
present, that none repeats, that each foundation climbs in one suit, that no face-down
card sits above a face-up one, and that every face-up run descends in alternating colors.

`SaveStateTest` round-trips a fresh board, a board in progress and a finished one. A
truncated, duplicated, wrong-version or otherwise damaged save must decode to nothing
rather than to a broken game.

`MovesTest` asserts that the app can play every move it offers, and that a tap and the
move it reports are the same move. The animation would lie otherwise.

`SolverTest` guards the unwinnable claim. The state key must be one to one: a collision
would drop a branch that wins and still let the search report that it looked everywhere.
The test asserts distinct keys across 400 deals, and that the key ignores the move
counter. It then cross-checks the verdict against a second, independent search. That one
goes breadth first instead of depth first, keys on `SaveState` instead of the solver key,
and explores the whole space instead of stopping at the first win. A full deal is too
large to walk, so both searches run over cut-down decks of a few suits and a few ranks,
where finishing is possible. About 150 positions get compared on each run, near two
thirds winnable and one third not, and the two searches must agree on every one. Each
winnable verdict then has its line replayed move by move, and the line must end in a win.

## Origin and credits

- **[lightphone/light-sdk](https://github.com/lightphone/light-sdk)** by The Light Phone
  is the base of this repository. This repo vendors the whole tree. The SDK client, the UI kit, the Gradle
  plugin, the lint rules and the builder are their work, released under MIT before the
  platform was even public. Thank you.
- **[gi-os/LightNYCSubway](https://github.com/gi-os/LightNYCSubway)** set the repository
  pattern this one copies. Fork light-sdk, write the tool into the module the SDK
  reserves for it, leave upstream alone, and let a workflow build the APK against a
  version check.
- Klondike itself belongs to nobody. The rules here follow the standard draw-one variant
  with unlimited redeals.

## The gi-os Light App collection

Eight tools for the Light Phone III, all open source, all built in one run.

| Tool | What it does | Built on |
| --- | --- | --- |
| [LightPass](https://github.com/gi-os/LightPass) | Photograph a movie ticket, keep the stub | Plain Android |
| [LightQR](https://github.com/gi-os/LightQR) | QR scanner, plus a browser generator | Plain Android |
| [LightRSS](https://github.com/gi-os/LightRSS) | RSS and Atom reader with images and QR subscribe | light-sdk, fork of [zachattack323/LightRSS](https://github.com/zachattack323/LightRSS) |
| [LightNYCSubway](https://github.com/gi-os/LightNYCSubway) | Live MTA subway arrivals | light-sdk fork |
| [chat](https://github.com/gi-os/chat) | iMessage over a self-hosted BlueBubbles server | Fork of [craigeley/chat](https://github.com/craigeley/chat) |
| [LightFog](https://github.com/gi-os/LightFog) | Fog of World companion, GPS recorder and fog map | Fork of [garado/light-topographic](https://github.com/garado/light-topographic) |
| [LightNonogram](https://github.com/gi-os/LightNonogram) | Picross, plus a generator that only ships solvable puzzles | Kotlin generator, light-sdk tool |
| **LightSolitaire** (this repo) | Klondike, draw one, unlimited redeals | light-sdk |

The Light Phone does not sponsor or endorse any of these.

## License

MIT, the same as upstream light-sdk. See [LICENSE](LICENSE).
