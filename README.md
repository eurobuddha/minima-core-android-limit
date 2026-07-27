# Limit — native Android on-chain DEX

A fully-native Java Android port of the **Limit** Minima MiniDapp: an **on-chain limit-order-book DEX** for
**MINIMA ↔ mxUSDT**. Trustless and non-custodial — orders are covenant coins on the chain, matched peer-to-peer,
no operator holds funds. Package `com.eurobuddha.limit`.

It runs the **same VERIFYOUT order-book contract(s) as the web dapp** (identical pinned contract addresses), so the
native app trades the **same on-chain book** as the dapp — a maker order posted on one is takeable on the other.

## Tabs

- **ORDER BOOK** — the live aggregated bid/ask ladder (makers' resting orders), best price first; tap a level to take it.
- **NEW ORDER** — place a maker order (side, price in USDT/MINIMA, amount) as an on-chain covenant coin, or take an
  existing one.
- **CHART** — price history for the pair (with a CoinGecko reference price).
- **HISTORY** — your fills and order lifecycle.

## How it works

The app talks to the **local Minima Core node** over the broadcast-Intent IPC (`NodeApi` / `minimaapi`) — no internet
permission for trading, no custodian. A maker "locks" funds into a VERIFYOUT covenant that will only release them to
the maker's payout on a correctly-priced fill; a taker builds the matching transaction. `LimitContract` derives the
pinned covenant addresses (V1–V4 contract versions), `BookScanner` discovers resting orders, `LimitTxn` builds the
maker/taker transactions, and a foreground `LimitService` + `LimitWorker` keep the book fresh and progress fills while
the app is backgrounded.

## Build

Requires a **JDK 17/21** (the Android Studio JBR works):

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleRelease
```

Install, then enable **Limit** in Minima Core → Apps to authorize the IPC.

## Releases

Versioned APKs are published to the [PandaApps catalog](https://github.com/eurobuddha/minima-core-apks)
(`apks.json`). Current: **v0.3.1**.
