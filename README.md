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
| New, Undo | Top bar. Undo remembers 120 moves. |

Tapping a foundation does nothing on purpose, so you cannot unstack a suit you already
banked. Auto-move also refuses to shuffle a lone king between two empty columns, which
would only burn a move.

The game saves to the tool DataStore after every move and on pause, so leaving and
coming back drops you on the same board. The app throws away a save that does not
decode to a legal 52-card deal, and deals you a fresh game instead of a corrupt one.

Layout measures from the real screen size rather than a hardcoded number, so it survives
an emulator at another resolution. On an LP3, with about 411 x 472 dp of usable space,
that works out to a 53 dp card. A long column compresses its fan spacing instead of
running off the bottom, and the bottom 44 dp stays clear for the LightOS back button.

## Layout of this repository

This is the light-sdk tree. The game lives in the `tool/` module that the SDK reserves
for exactly that. Everything else stays upstream and untouched, so a rebase stays
cheap.

| Path | What it is |
| --- | --- |
| `tool/src/main/kotlin/com/thelightphone/solitaire/Klondike.kt` | Every rule. Immutable `Game`, no Android imports. |
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
| [LightFog](https://github.com/gi-os/LightFog) | Fog of World companion, GPS recorder and fog map | Expo, [vandamd/light-template](https://github.com/vandamd/light-template) |
| [LightNonogram](https://github.com/gi-os/LightNonogram) | Picross, plus a generator that only ships solvable puzzles | Kotlin generator, light-sdk tool |
| **LightSolitaire** (this repo) | Klondike, draw one, unlimited redeals | light-sdk |

The Light Phone does not sponsor or endorse any of these.

## License

MIT, the same as upstream light-sdk. See [LICENSE](LICENSE).
