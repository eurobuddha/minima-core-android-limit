package org.minimarex.limit;

import java.math.BigDecimal;
import java.util.Set;

import static org.minimarex.limit.LimitContract.*;

/**
 * One open order = a coin at a Limit script address carrying the order state (ports 0-6). The maker
 * locked one asset (the coin's token/amount) and wants {@code wantAmt} of {@code wantTok} back at
 * {@code wantAddr}. A BUY order locks USDT and wants Minima; a SELL order locks Minima and wants USDT.
 */
public class Order {

    public final Coin coin;

    public Order(Coin coin) { this.coin = coin; }

    // ---- raw state ----
    public String ownerPk()  { return coin.stateAt(P_OWNER_PK); }
    public String wantAddr() { return coin.stateAt(P_WANT_ADDR); }
    public String wantAmt()  { return coin.stateAt(P_WANT_AMT); }
    public String wantTok()  { return tok(coin.stateAt(P_WANT_TOK)); }
    public String orderId()  { return coin.stateAt(P_ORDER_ID); }
    public String sideRaw()  { return coin.stateAt(P_SIDE); }
    public String priceRaw() { return coin.stateAt(P_PRICE); }

    public String coinid()       { return coin.coinid; }
    public String lockedTok()    { return tok(coin.tokenid); }
    public String lockedAmount() { return coin.amount; }       // Coin handles tokenamount (USDT) vs amount (Minima)
    public long   createdBlock() { return coin.created; }

    // ---- derived ----
    // Match the web (lint.js): side "0" = buy, anything else = sell. Tolerant so legacy/3rd-party
    // orders whose port-5 value isn't a clean "0"/"1" aren't silently dropped from the book.
    public boolean isBuy()  { return "0".equals(sideRaw().trim()); }
    public boolean isSell() { return !isBuy(); }

    /** Amount of MINIMA this order is about. SELL locks Minima; BUY wants Minima. */
    public BigDecimal minimaAmount() { return Util.dec(isSell() ? lockedAmount() : wantAmt()); }
    /** Amount of USDT this order is about. SELL wants USDT; BUY locks USDT. */
    public BigDecimal usdtAmount()   { return Util.dec(isSell() ? wantAmt() : lockedAmount()); }
    /** Price (USDT per MINIMA) — ALWAYS derived from the contract-enforced amounts (wantAmt/lockedAmount),
     *  never the maker-controlled display port 6 (which isn't enforced and could otherwise game the book). */
    public BigDecimal price() {
        BigDecimal m = minimaAmount();
        return m.signum() > 0 ? usdtAmount().divide(m, 8, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO;
    }

    public long ageBlocks(int tip) { return coin.created < 0 ? 0 : Math.max(0, tip - coin.created); }
    public boolean expired(int tip) { return ageBlocks(tip) > EXPIRY_BLOCKS; }
    public long blocksLeft(int tip) { return Math.max(0, EXPIRY_BLOCKS - ageBlocks(tip)); }

    public boolean isMine(Set<String> myKeys) {
        String pk = ownerPk();
        return pk != null && !pk.isEmpty() && myKeys.contains(pk);
    }

    /** A well-formed Limit order: has owner+want addr, positive amounts, and only Minima/USDT tokens. */
    public boolean valid() {
        if (ownerPk().isEmpty() || wantAddr().isEmpty()) return false;
        if (Util.dec(wantAmt()).signum() <= 0) return false;
        if (Util.dec(lockedAmount()).signum() <= 0) return false;
        if (!okTok(coin.tokenid) || !okTok(wantTok())) return false;   // token whitelist (defence in depth)
        return true;   // side is always buy-or-sell now (tolerant, matches the web)
    }

    private static boolean okTok(String t) { return Util.isMinima(t) || USDT_ID.equalsIgnoreCase(t); }

    /** Normalise a token id: empty/0x00 → "0x00". */
    private static String tok(String t) { return (t == null || t.isEmpty()) ? Util.MINIMA_TOKENID : t; }
}
