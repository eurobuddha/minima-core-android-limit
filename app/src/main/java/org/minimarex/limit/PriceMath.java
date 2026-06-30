package org.minimarex.limit;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Price/amount math for the DEX, matching the dapp's `fmtAmt` (.toFixed(8)) / `fmtPrice` so on-chain
 * amounts are byte-compatible and never emitted in scientific notation (which Minima rejects).
 */
public final class PriceMath {

    private PriceMath() {}

    public static final BigDecimal MIN_ORDER = new BigDecimal("0.01");   // minimum 0.01 MINIMA
    public static final BigDecimal DUST = new BigDecimal("0.000001");
    private static final int CHAIN_SCALE = 8;                            // dapp uses .toFixed(8)

    /** On-chain amount string: 8 dp, half-up, plain (no exponent). */
    public static String fmtAmt(BigDecimal v) {
        if (v == null) return "0";
        return v.setScale(CHAIN_SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    /** total = amount * price, at chain scale (used to compute the USDT leg of an order/fill). */
    public static BigDecimal total(BigDecimal amount, BigDecimal price) {
        return amount.multiply(price).setScale(CHAIN_SCALE, RoundingMode.HALF_UP);
    }

    /** Display price: ALWAYS 5 decimals (e.g. 0.00575, 0.50000) — consistent everywhere a price shows. */
    public static String fmtPrice(BigDecimal p) {
        if (p == null) return "0.00000";
        return p.setScale(5, RoundingMode.HALF_UP).toPlainString();
    }

    /** Tidy display amount: cap 6 dp, drop trailing zeros. */
    public static String fmtDisplay(BigDecimal v) {
        if (v == null) return "0";
        BigDecimal t = v.scale() > 6 ? v.setScale(6, RoundingMode.DOWN) : v;
        return t.stripTrailingZeros().toPlainString();
    }

    public static boolean isUsdt(String tokenid) { return LimitContract.USDT_ID.equalsIgnoreCase(tokenid); }
}
