package org.minimarex.limit;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.minimarex.limit.LimitContract.*;

/**
 * Builds, signs and posts the Limit DEX transactions over {@link NodeApi}, mirroring the dapp
 * (`lint.js`) command-for-command so on-chain behaviour is identical. Native = full signing rights, so
 * we sign→basics→post inline (no dapp "restricted/pending" flow).
 *
 * The fill is a trustless two-asset swap the TAKER builds: the order coin is input 0, so the
 * maker-payment output must be output 0 (the contract's VERIFYOUT keys on @INPUT).
 */
public class LimitTxn {

    public interface Result { void onPosted(String txpowid); void onFailed(String message); }

    private final NodeApi node;
    private final String myPubkey, myHexAddr;
    // funding coins reserved by a posted-but-unconfirmed fill, so a second fill can't double-spend them.
    private final Set<String> inflightCoins = ConcurrentHashMap.newKeySet();

    public LimitTxn(NodeApi node, String myPubkey, String myHexAddr) {
        this.node = node; this.myPubkey = myPubkey; this.myHexAddr = myHexAddr;
    }

    // ===================================================================== create (always V4)
    /** Place an order. buy = lock USDT want Minima; sell = lock Minima want USDT. */
    public void createOrder(boolean buy, BigDecimal minimaAmount, BigDecimal price, Result cb) {
        BigDecimal usdt = PriceMath.total(minimaAmount, price);
        String lockAmt, lockTokArg, wantAmt, wantTok;
        if (buy) {
            lockAmt = PriceMath.fmtAmt(usdt);        lockTokArg = " tokenid:" + USDT_ID;
            wantAmt = PriceMath.fmtAmt(minimaAmount); wantTok = "0x00";
        } else {
            lockAmt = PriceMath.fmtAmt(minimaAmount); lockTokArg = "";
            wantAmt = PriceMath.fmtAmt(usdt);         wantTok = USDT_ID;
        }
        String orderId = "0x" + Long.toHexString(System.currentTimeMillis()).toUpperCase();
        String side = buy ? "0" : "1";
        String state = "{\"0\":\"" + myPubkey + "\",\"1\":\"" + myHexAddr + "\",\"2\":\"" + wantAmt
                + "\",\"3\":\"" + wantTok + "\",\"4\":\"" + orderId + "\",\"5\":\"" + side
                + "\",\"6\":\"" + price.toPlainString() + "\"}";
        node.cmd("send amount:" + lockAmt + " address:" + ADDR_V4 + lockTokArg + " state:" + state,
                new NodeApi.Cb() {
            @Override public void onResult(JSONObject s) {
                if (s.optBoolean("status", false) || s.optBoolean("pending", false))
                    cb.onPosted(Util.extractTxpowid(s, ""));
                else cb.onFailed("Send failed" + err(s));
            }
            @Override public void onError(String m) { cb.onFailed(m); }
        });
    }

    // ===================================================================== fill (taker swap)
    public void fill(final Order order, final Result cb) {
        final String payTok = order.wantTok();                 // taker pays the maker's wanted token
        final boolean payUsdt = PriceMath.isUsdt(payTok);
        final String payTokId = payUsdt ? USDT_ID : Util.MINIMA_TOKENID;
        final BigDecimal payAmt = Util.dec(order.wantAmt());
        findCoins(payTokId, payAmt, order.coinid(), new CoinsCb() {
            @Override public void onCoins(List<Coin> funds, BigDecimal sum) {
                buildFill(order, funds, sum, payAmt, payTokId, cb);
            }
            @Override public void onNone() {
                cb.onFailed("Insufficient " + (payUsdt ? "USDT" : "Minima")
                        + " (need " + PriceMath.fmtDisplay(payAmt) + ")");
            }
        });
    }

    private void buildFill(Order order, List<Coin> funds, BigDecimal sum, BigDecimal payAmt,
                           String payTokId, Result cb) {
        String txid = "fill_" + tag();
        String payTokArg  = PriceMath.isUsdt(payTokId) ? " tokenid:" + USDT_ID : "";
        String recvTokArg = PriceMath.isUsdt(order.lockedTok()) ? " tokenid:" + USDT_ID : "";

        List<String> cmds = new ArrayList<>();
        cmds.add("txncreate id:" + txid);
        cmds.add("txninput id:" + txid + " coinid:" + order.coinid());                 // input 0 = order coin
        for (Coin f : funds) cmds.add("txninput id:" + txid + " coinid:" + f.coinid);  // taker funding
        // output 0 = maker payment (the VERIFYOUT-checked slot). Use the order's exact want-amount string.
        cmds.add("txnoutput id:" + txid + " amount:" + order.wantAmt() + " address:" + order.wantAddr()
                + payTokArg + " storestate:false");
        // output 1 = the locked asset to the taker (me)
        cmds.add("txnoutput id:" + txid + " amount:" + order.lockedAmount() + " address:" + myHexAddr
                + recvTokArg + " storestate:false");
        // output 2 = change of the pay token
        BigDecimal change = sum.subtract(payAmt);
        if (change.compareTo(PriceMath.DUST) > 0) {
            cmds.add("txnoutput id:" + txid + " amount:" + PriceMath.fmtAmt(change) + " address:" + myHexAddr
                    + payTokArg + " storestate:false");
        }
        cmds.add("txnsign id:" + txid + " publickey:auto");   // signs the taker's funding coins (order coin spends via VERIFYOUT)
        cmds.add("txnbasics id:" + txid);
        cmds.add("txnpost id:" + txid);

        for (Coin f : funds) inflightCoins.add(f.coinid);
        post(cmds, txid, new Result() {
            @Override public void onPosted(String txpowid) { cb.onPosted(txpowid); }
            @Override public void onFailed(String message) {
                for (Coin f : funds) inflightCoins.remove(f.coinid);
                cb.onFailed(message);
            }
        });
    }

    // ===================================================================== cancel (maker, SIGNEDBY path)
    public void cancel(Order order, Result cb) {
        String txid = "cancel_" + tag();
        String tokArg = PriceMath.isUsdt(order.lockedTok()) ? " tokenid:" + USDT_ID : "";
        List<String> cmds = new ArrayList<>();
        cmds.add("txncreate id:" + txid);
        cmds.add("txninput id:" + txid + " coinid:" + order.coinid());
        cmds.add("txnoutput id:" + txid + " amount:" + order.lockedAmount() + " address:" + order.wantAddr()
                + tokArg + " storestate:false");
        cmds.add("txnsign id:" + txid + " publickey:" + order.ownerPk());
        cmds.add("txnbasics id:" + txid);
        cmds.add("txnpost id:" + txid);
        post(cmds, txid, cb);
    }

    // ===================================================================== collect expired (COINAGE path)
    public void collectExpired(Order order, Result cb) {
        String txid = "collect_" + tag();
        String tokArg = PriceMath.isUsdt(order.lockedTok()) ? " tokenid:" + USDT_ID : "";
        List<String> cmds = new ArrayList<>();
        cmds.add("txncreate id:" + txid);
        cmds.add("txninput id:" + txid + " coinid:" + order.coinid());
        cmds.add("txnoutput id:" + txid + " amount:" + order.lockedAmount() + " address:" + order.wantAddr()
                + tokArg + " storestate:false");
        cmds.add("txnsign id:" + txid + " publickey:auto");
        cmds.add("txnbasics id:" + txid);
        cmds.add("txnpost id:" + txid);
        post(cmds, txid, cb);
    }

    // ----------------------------------------------------------------- helpers

    private void post(List<String> cmds, String txid, Result cb) {
        CmdChain.run(node, cmds, "txndelete id:" + txid, new CmdChain.Done() {
            @Override public void ok(JSONObject last) { cb.onPosted(Util.extractTxpowid(last, txid)); }
            @Override public void fail(String message) { cb.onFailed(message); }
        });
    }

    private static String err(JSONObject j) { String e = j.optString("error", ""); return e.isEmpty() ? "" : " : " + e; }

    private static String tag() {
        return System.currentTimeMillis() + "_" + Integer.toHexString((int) (System.nanoTime() & 0xffffff));
    }

    // ---- coin selection (token-parameterised port of the dapp's findCoins) ----
    private interface CoinsCb { void onCoins(List<Coin> coins, BigDecimal sum); void onNone(); }

    private void findCoins(String tokenid, BigDecimal need, String excludeCoinid, CoinsCb cb) {
        node.cmd("coins relevant:true sendable:true tokenid:" + tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONArray arr = json.optJSONArray("response");
                if (arr == null || arr.length() == 0) { cb.onNone(); return; }
                Set<String> present = new HashSet<>();
                List<Coin> avail = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject jc = arr.optJSONObject(i);
                    if (jc == null) continue;
                    Coin c = Coin.from(jc);
                    present.add(c.coinid);
                    if (c.hasState()) continue;                    // plain (non-script) coins only
                    if (c.coinid.equals(excludeCoinid)) continue;
                    if (inflightCoins.contains(c.coinid)) continue;
                    avail.add(c);
                }
                inflightCoins.retainAll(present);
                if (avail.isEmpty()) { cb.onNone(); return; }
                avail.sort((a, b) -> Util.dec(b.amount).compareTo(Util.dec(a.amount)));
                List<Coin> sel = new ArrayList<>();
                BigDecimal sum = BigDecimal.ZERO;
                for (Coin c : avail) {
                    sel.add(c);
                    sum = sum.add(Util.dec(c.amount));
                    if (sum.compareTo(need) >= 0) { cb.onCoins(sel, sum); return; }
                }
                cb.onNone();
            }
            @Override public void onError(String message) { cb.onNone(); }
        });
    }
}
