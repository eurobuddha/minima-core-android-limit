package org.minimarex.limit;

import org.json.JSONObject;

/**
 * The Limit DEX on-chain contract(s) and constants — ported VERBATIM from the Limit MiniDapp
 * (`lint.js`) so the native app registers the IDENTICAL scripts and therefore resolves to the same
 * script addresses. That makes the native order book the SAME book as the dapp (interoperable).
 *
 * Limit is a VERIFYOUT single-coin escrow order book for MINIMA ⇄ USDT (mxUSDT):
 *   a maker LOCKS one asset at a script address with state describing what they want back; anyone may
 *   spend the order coin PROVIDED output[@INPUT] pays the maker their wanted amount/token (a trustless
 *   full-fill swap). Plus a SIGNEDBY cancel path and a @COINAGE GT 1500 expiry-refund path.
 *
 * Four contract versions exist in the live book (V1..V4). Read all four; CREATE only on the hardened
 * V4 (the 2026-04 theft exploited V1/V2's unguarded cancel — see the incident report). V4 also adds a
 * token whitelist so only Minima/USDT orders are valid.
 *
 * State ports (set on `send` create): 0 owner pubkey | 1 want address (0x) | 2 want amount |
 *   3 want tokenid | 4 order id | 5 side ("0" buy / "1" sell) | 6 price (display only).
 * VERIFYOUT keys on @INPUT, so the maker-payment output MUST be at the same index as the order
 * coin's input — we always make the order coin input 0 and the maker payment output 0.
 */
public final class LimitContract {

    private LimitContract() {}

    /** mxUSDT token id — the only non-Minima token the contract accepts. */
    public static final String USDT_ID =
        "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90";

    public static final int EXPIRY_BLOCKS = 1500;
    /** GTC orders are auto-renewed once they reach this age — well before EXPIRY_BLOCKS so the renewal
     *  (cancel→recreate) always lands before any client could COINAGE-collect the order at 1500. */
    public static final int RENEW_AT = 1300;
    public static final int MIN_ORDER_MINIMA_CENTI = 1;   // 0.01 MINIMA minimum (see PriceMath.MIN_ORDER)

    // state ports
    public static final int P_OWNER_PK = 0, P_WANT_ADDR = 1, P_WANT_AMT = 2, P_WANT_TOK = 3,
            P_ORDER_ID = 4, P_SIDE = 5, P_PRICE = 6,
            /** GTC marker ("1" = good-till-cancelled). The V4 script ignores ports >3, so this is an
             *  app-only flag that's invisible-but-harmless to the contract and the dapp. */
            P_GTC = 7;

    // ---- the four pinned scripts + addresses (verbatim from lint.js) ----

    public static final String SCRIPT_V1 =
        "IF SIGNEDBY(PREVSTATE(0)) THEN RETURN TRUE ENDIF ASSERT VERIFYOUT(@INPUT PREVSTATE(1) PREVSTATE(2) PREVSTATE(3) FALSE) RETURN TRUE";
    public static final String ADDR_V1 =
        "0x131609A5E510326354647E240F51C53825EFF8CA2B9DE07711EA56055E57672D";

    public static final String SCRIPT_V2 =
        "IF SIGNEDBY(PREVSTATE(0)) THEN RETURN TRUE ENDIF IF @COINAGE GT 1500 THEN ASSERT VERIFYOUT(@INPUT PREVSTATE(1) @AMOUNT @TOKENID FALSE) RETURN TRUE ENDIF ASSERT VERIFYOUT(@INPUT PREVSTATE(1) PREVSTATE(2) PREVSTATE(3) FALSE) RETURN TRUE";
    public static final String ADDR_V2 =
        "0xE4D3F27BB044500AF56EF775DAFF3A12187EE79A8460FBBBF321F76A660D7797";

    public static final String SCRIPT_V3 =
        "IF SIGNEDBY(PREVSTATE(0)) THEN ASSERT VERIFYOUT(@INPUT PREVSTATE(1) @AMOUNT @TOKENID FALSE) RETURN TRUE ENDIF IF @COINAGE GT 1500 THEN ASSERT VERIFYOUT(@INPUT PREVSTATE(1) @AMOUNT @TOKENID FALSE) RETURN TRUE ENDIF ASSERT VERIFYOUT(@INPUT PREVSTATE(1) PREVSTATE(2) PREVSTATE(3) FALSE) RETURN TRUE";
    public static final String ADDR_V3 =
        "0xE0325CC04B1BA1FC630D5E2B157976D01F76507D2049BD9D7D8029A318782BC7";

    /** CURRENT — all new orders are created on V4 (hardened cancel + USDT whitelist). */
    public static final String SCRIPT_V4 =
        "LET u=" + USDT_ID + " IF @TOKENID NEQ 0x00 THEN IF @TOKENID NEQ u THEN RETURN FALSE ENDIF ENDIF "
        + "IF PREVSTATE(3) NEQ 0x00 THEN IF PREVSTATE(3) NEQ u THEN RETURN FALSE ENDIF ENDIF "
        + "IF SIGNEDBY(PREVSTATE(0)) THEN ASSERT VERIFYOUT(@INPUT PREVSTATE(1) @AMOUNT @TOKENID FALSE) RETURN TRUE ENDIF "
        + "IF @COINAGE GT 1500 THEN ASSERT VERIFYOUT(@INPUT PREVSTATE(1) @AMOUNT @TOKENID FALSE) RETURN TRUE ENDIF "
        + "ASSERT VERIFYOUT(@INPUT PREVSTATE(1) PREVSTATE(2) PREVSTATE(3) FALSE) RETURN TRUE";
    public static final String ADDR_V4 =
        "0x94F2CB876903FAED64EA4C9C4B7FD602BAC5CD59F9EBC38AB0C4A0F0B346807F";

    /** Addresses scanned for the order book — **V4 only**. The legacy V1-V3 scripts/addresses are kept
     *  above for reference but are NOT tracked or scanned (their books are effectively dead, and scanning
     *  one address keeps the per-block `coins` response small). */
    public static final String[] ADDRS = { ADDR_V4 };

    /** Register the V4 script so the node tracks the address and can verify spends. Fire-and-forget. */
    public static void registerAll(NodeApi node) {
        node.cmd("newscript script:\"" + SCRIPT_V4 + "\" trackall:true", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {}
            @Override public void onError(String message) {}
        });
    }
}
