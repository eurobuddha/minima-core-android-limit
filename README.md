# Zero Edge Casino — native Android APK

A fully-native Java clone of the **Zero Edge Casino** Minima MiniDapp
(`~/Projects/Ideas/universal-casino/`). Trustless, fair-odds, on-chain gambling using a 3-phase
commit-reveal contract: FLIP (1:1), DICE (5:1), ROULETTE (35:1), zero house edge.

## How it works
- Talks to the local **Minima Core** node over the broadcast-Intent IPC (`MinimaAPI`), same as
  `minima-core-android-utxo`. No HTTP/MDS server needed (the Android node has those off).
- Registers the **identical** contract script as the dapp, so it resolves to the same address
  `0xD65A…65A6F` → **interoperable**: bets created in the dapp are takeable here and vice-versa.
- Gambling secrets live in `EncryptedSharedPreferences` (replaces `MDS.keypair`).
- A foreground `CasinoService` (+ WorkManager fallback) auto-reveals (as house) and auto-resolves
  (as player) on each new block, even when the app is closed.

## Build
```
./gradlew assembleDebug   # -> app/build/outputs/apk/debug/app-debug.apk
```
Requires JDK 21 (Android Studio's JBR). Pinned in `gradle.properties` via `org.gradle.java.home`
because the system default JDK (26) is too new for Gradle 8.11.

## Install & pair
1. Install the Minima Core node app (`org.minimarex.minimacore`) on the device.
2. Sideload this APK; open Minima Core → Apps → enable **Zero Edge Casino** (the in-app banner
   clears once enabled).

## Tabs
- **PLAY** — open bets from others; pick a side and Take Bet.
- **HOUSE** — choose a game + amount, Create Bet.
- **MY BETS** — your active games; auto reveal/resolve, manual Cancel / Resolve / Claim Timeout.
- **HISTORY** — last 50 resolved bets with P&L.

## Status / follow-ups
- v0.1.0, versionCode 1. Releases archived under `releases/` (never overwrite — bump semver).
- House-side history is recorded when *this* device resolves; pure-house results settled by the
  counterparty aren't yet back-filled from chain (a `NEWBALANCE` reconciliation pass — TODO).
- Win/lose feedback is SFX + confetti + toast; a full result modal/overlay is a later polish.
- Per-card resolve animations are wired but currently fire the result overlay directly.
