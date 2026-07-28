# Installing Solitaire on a Light Phone III

## Get the APK

Grab `LightSolitaire-<version>.apk` from the
[latest release](https://github.com/gi-os/LightSolitaire/releases/latest).

## Check what you downloaded

Every release ships a `.sha256` sidecar:

```bash
sha256sum -c LightSolitaire-1.0.0.apk.sha256
```

The APK is signed with a personal sideload key. Its certificate fingerprint is:

```
SHA-256: 38:6E:DB:70:91:94:77:07:A8:12:58:7A:F8:C3:7B:08:73:BB:2C:DE:88:96:3E:95:20:57:4C:C4:5B:C0:12:8C
```

Check it yourself with `apksigner verify --print-certs LightSolitaire-1.0.0.apk`. If that
fingerprint ever changes, the APK was not built from this repository's key — don't install it.

## Install

Turn on Developer Mode in the [Light dashboard](https://dashboard.thelightphone.com), then:

```bash
adb install -r LightSolitaire-1.0.0.apk
```

Solitaire shows up in the LightOS toolbox. To get updates automatically, point
[Obtainium](https://github.com/ImranR98/Obtainium) at `https://github.com/gi-os/LightSolitaire`
with the APK filter `LightSolitaire-.*\.apk` — that skips the `.sha256` sidecar.

## What Light will and won't support

This is signed by a personal key, not by Light. In LightOS terms it needs the **Any tools** setting,
which is the tier where you own getting things installed and uninstalled. It's built entirely with
the official SDK and asks for no permissions at all, so it should be a candidate for the Tool
Library once submissions open — at which point Light would sign it themselves and this key stops
mattering.

Android only accepts an update signed by the same key that installed the app. If the signing key
ever has to change, you'll have to uninstall first, which wipes the saved game.

## Uninstall

```bash
adb uninstall com.thelightphone.solitaire
```
