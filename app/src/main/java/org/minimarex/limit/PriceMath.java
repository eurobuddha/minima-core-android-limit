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

    /** Display price: 6 dp if <0.01, 5 dp if <1, else 4 dp. */
    public static String fmtPrice(BigDecimal p) {
        if (p == null) return "0";
        int dp = p.compareTo(new BigDecimal("0.01")) < 0 ? 6 : p.compareTo(BigDecimal.ONE) < 0 ? 5 : 4;
        return p.setScale(dp, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /** Tidy display amount: cap 6 dp, drop trailing zeros. */
    public static String fmtDisplay(BigDecimal v) {
        if (v == null) return "0";
        BigDecimal t = v.scale() > 6 ? v.setScale(6, RoundingMode.DOWN) : v;
        return t.stripTrailingZeros().toPlainString();
    }

    public static boolean isUsdt(String tokenid) { return LimitContract.USDT_ID.equalsIgnoreCase(tokenid); }
}
