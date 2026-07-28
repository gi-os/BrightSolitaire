# Solitaire for the Light Phone III

Klondike, draw one, unlimited redeals. A LightOS tool built on the official
[light-sdk](https://github.com/lightphone/light-sdk) — Kotlin, Jetpack Compose, `LightScreen` /
`LightViewModel`, themed with `sdk:ui`.

[**Download the latest APK →**](https://github.com/gi-os/LightSolitaire/releases/latest) · see
[INSTALL.md](INSTALL.md)

## Reading a one bit deck

The LP3 panel is black and white, so suit color is carried by shape instead of hue:

- **Spades and clubs** are drawn filled.
- **Hearts and diamonds** are drawn as outlines.

The alternating-color rule stays readable at a glance without a legend. Card faces use the theme
background with a hairline border; card backs are solid `content` with an inset frame, so a face
down pile reads as a dark block from across the table. The whole thing follows the LightOS theme,
so it flips with light and dark mode.

## Controls

| Action | What happens |
| --- | --- |
| Tap a card | Goes to a foundation if it will go, otherwise the leftmost legal column |
| Tap a face down card | Turns it over, if it is the bottom of its column |
| Tap the stock | Draws one. Tap the empty ring to redeal the waste. |
| Drag | Manual placement, including multi-card runs and pulling a card back off a foundation |
| New / Undo | Top bar. Undo remembers 120 moves. |

Tapping a foundation does nothing on purpose, so you can't accidentally unstack a suit you already
banked. Auto-move also refuses to shuffle a lone king between two empty columns, which would only
burn a move.

The game saves itself to the tool's DataStore after every move and on pause, so leaving and coming
back drops you on the same board. A save that doesn't decode to a legal 52 card deal is discarded
and you get a fresh deal instead of a corrupt one.

Layout is measured from the real screen size rather than hardcoded, so it survives the emulator
running at other resolutions. On an LP3 (~411 x 472 dp of usable space) that works out to a 53 dp
card. Long columns compress their fan spacing instead of running off the bottom, and the bottom
44 dp stays clear for the LightOS back button.

## Layout of this repository

This is the light-sdk tree with the tool written into the `tool/` module that the SDK reserves for
exactly that. Everything else is upstream and untouched, so the SDK stays easy to rebase.

| Path | What it is |
| --- | --- |
| `tool/src/main/kotlin/com/thelightphone/solitaire/Klondike.kt` | Every rule. Immutable `Game`, no Android imports. |
| `tool/src/main/kotlin/com/thelightphone/solitaire/Cards.kt` | Suits, cards, seeded deck |
| `tool/src/main/kotlin/com/thelightphone/solitaire/CardView.kt` | Canvas suit glyphs, card face, card back |
| `tool/src/main/kotlin/com/thelightphone/solitaire/HomeScreen.kt` | `@InitialScreen`, view model, table geometry, tap and drag |
| `tool/src/main/kotlin/com/thelightphone/solitaire/SaveState.kt` | One-line text encoding of a deal |
| `tool/src/main/kotlin/com/thelightphone/solitaire/SolitaireStore.kt` | DataStore read and write |
| `tool/src/test/kotlin/com/thelightphone/solitaire/` | JVM unit tests, run by CI before anything is built |
| `tool/lighttool.toml` | Tool identity and version. The release tag is checked against it. |
| `sdk/`, `plugin/`, `examples/`, `docs/` | Upstream light-sdk |

## Building it yourself

```bash
git clone https://github.com/gi-os/LightSolitaire.git
cd LightSolitaire
./gradlew :tool:testDebugUnitTest :tool:assembleDebug
```

The SDK itself is vendored, so nothing has to be resolved from GitHub Packages to build the tool.
If a future SDK version pulls the Light Keyboard, export `GH_PACKAGES_USER` and `GH_PACKAGES_TOKEN`
(a PAT with `read:packages`), or put `gpr.user` and `gpr.key` in the ignored `local.properties`.
Never commit either.

To run against the LightOS emulator instead of hardware, set
`serverPackage = "com.thelightphone.sdk.emulator"` in `tool/lighttool.toml` and follow
`docs/system_app`.

## Tests

The rules engine deliberately has no Android dependency, so all of it runs as a plain JVM unit
test and CI will not build an APK until it passes.

`KlondikeTest` covers deal shape, stock cycling and redeal order, foundation and tableau
acceptance, run validity, pickup rules, turning over exposed cards, auto-move preference order,
and win detection. It then plays 200 full deals with a greedy policy and asserts after **every
single state** that all 52 cards are present, none are duplicated, foundations ascend in one suit,
no face down card sits above a face up one, and every face up section is a legal descending
alternating run. `SaveStateTest` round trips fresh, in-progress and finished boards, and checks
that truncated, versioned-off, duplicated and otherwise malformed saves decode to nothing rather
than to a broken game.

## Releasing

```bash
# bump versionName and versionCode in tool/lighttool.toml first
git tag v1.1.0 && git push origin v1.1.0
```

The release workflow refuses to run if the tag doesn't match `versionName`, then tests, lints,
builds a signed release APK, prints the signing certificate, and publishes it to a GitHub Release
with a `.sha256` sidecar. `versionCode` has to increase every time or Android will not install the
update over the existing app.

## Licence

MIT, same as the SDK it's built on. Light requires community tools to be open source before they
can be signed and listed, so this is ready to point their build pipeline at.
