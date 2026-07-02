package org.minimarex.limit;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Limit background brain, shared by {@link MainActivity} (foreground) and {@link LimitService}
 * (background). On each block, against the current order book, it:
 *
 *   1. MAKER-FILL DETECTION — one of MY resting orders that left the book without expiring was taken by a
 *      counterparty → {@link Listener#onMakerFilled}.
 *   2. GTC AUTO-RENEWAL — for MY good-till-cancelled orders (state port 7) that reach {@code RENEW_AT},
 *      re-place them (cancel → recreate) so the coin age resets and the order never expires. A 2-step,
 *      persisted state machine survives the cancel→recreate gap across a service restart.
 *   3. AUTO-COLLECT EXPIRED — any order past 1500 (mine non-GTC, or anyone's abandoned) is pushed back to
 *      its maker via the trustless COINAGE path (book hygiene; the contract pins the output to the maker).
 *
 * State is PERSISTED via {@link TradeStore} (the my-orders snapshot for fill detection, and in-flight
 * renewals), so nothing is lost across the foreground↔background hand-off or a worker restart.
 */
public class LimitProcessor {

    public interface Listener {
        void onMakerFilled(OrderSnap order);
        /** A GTC order was cancelled for renewal but the re-lock kept failing — funds are safe in the
         *  wallet but the order is gone; the user should be told to re-place it. */
        void onRenewalStranded(OrderSnap order);
        void onError(String message);
    }

    private static final int MAX_COLLECT_PER_BLOCK = 2;
    private static final int MAX_RENEW_PER_BLOCK = 2;
    private static final int MAX_RECREATE_RETRIES = 5;
    private static final int RECREATE_WAIT_BLOCKS = 4;   // blocks to let a posted recreate mine before re-posting (anti double-create)
    private static final int FUNDS_MISSING_GIVEUP = 3;   // consecutive scans of "funds not back" before concluding the order was filled (avoids a false fill on a transient query lag)

    private final LimitTxn txn;
    private final TradeStore store;
    private final Set<String> collecting = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<String> renewing = Collections.newSetFromMap(new ConcurrentHashMap<>()); // old coinids mid-renewal
    private final Set<String> recreateInFlight = Collections.newSetFromMap(new ConcurrentHashMap<>()); // orderIds with a recreate send in progress (transient)
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();   // orderId -> in-flight renewal
    private Map<String, OrderSnap> prevMine;

    public LimitProcessor(LimitTxn txn, TradeStore store) {
        this.txn = txn;
        this.store = store;
        prevMine = loadSnapshot();
        loadRenewals();                                  // rebuilds `pending` + `renewing` after a restart
    }

    public void process(List<Order> book, Set<String> myKeys, int chainBlock, Listener l) {
        if (txn == null) return;

        // Re-read persisted state each scan so the foreground activity and the long-lived background
        // service (separate processor instances that now alternate, one active at a time) always act on
        // CURRENT state — otherwise the stale instance can stall an in-flight renewal started by the other.
        // Transient per-instance state (recreateInFlight, collecting) is deliberately kept in memory.
        reloadPersistedState();

        Map<String, OrderSnap> nowMine = new HashMap<>();
        Set<String> liveIds = new HashSet<>();
        Set<String> nowMineOrderIds = new HashSet<>();
        for (Order o : book) {
            liveIds.add(o.coinid());
            if (o.isMine(myKeys)) { nowMine.put(o.coinid(), OrderSnap.of(o)); nowMineOrderIds.add(o.orderId()); }
        }

        // 1) maker-fill detection — skip coins we're collecting or renewing (those aren't fills).
        for (Map.Entry<String, OrderSnap> e : prevMine.entrySet()) {
            String id = e.getKey();
            if (nowMine.containsKey(id)) continue;
            OrderSnap gone = e.getValue();
            if (gone.expired(chainBlock)) continue;
            if (collecting.contains(id) || renewing.contains(id)) continue;   // self-actions, not a fill
            if (l != null) l.onMakerFilled(gone);
        }
        prevMine = nowMine;
        persistSnapshot(nowMine);
        collecting.retainAll(liveIds);

        // 2) advance in-flight GTC renewals (cancel mined → recreate; new coin arrived → done).
        advanceRenewals(liveIds, nowMineOrderIds, chainBlock, l);

        // 3) start renewals (mine + GTC + due) and collect expired (mine non-GTC, or anyone's abandoned).
        int renews = 0, collects = 0;
        for (Order o : book) {
            final String id = o.coinid();
            if (o.isMine(myKeys) && o.isGtc()) {
                if (renews < MAX_RENEW_PER_BLOCK && o.renewDue(chainBlock)
                        && !pending.containsKey(o.orderId()) && !renewing.contains(id)) {
                    renews++;
                    startRenewal(o, l);
                }
                continue;                                 // never collect my GTC orders — they renew
            }
            if (collects < MAX_COLLECT_PER_BLOCK && o.expired(chainBlock)
                    && !renewing.contains(id) && collecting.add(id)) {
                collects++;
                txn.collectExpired(o, new LimitTxn.Result() {
                    @Override public void onPosted(String txpowid) {}
                    @Override public void onFailed(String message) { collecting.remove(id); if (l != null) l.onError(message); }
                });
            }
        }
    }

    // ----- GTC renewal state machine -----
    private void startRenewal(Order o, Listener l) {
        final Pending p = new Pending(o.orderId(), o.coinid(), o.lockedAmount(),
                PriceMath.isUsdt(o.lockedTok()), LimitTxn.gtcState(o), 0, false, 0, OrderSnap.of(o), 0);
        pending.put(p.orderId, p);
        renewing.add(p.oldCoinid);
        persistRenewals();
        txn.cancel(o, new LimitTxn.Result() {
            @Override public void onPosted(String txpowid) {}      // recreate fires once the cancel mines
            @Override public void onFailed(String message) {
                pending.remove(p.orderId);
                renewing.remove(p.oldCoinid);
                persistRenewals();
                if (l != null) l.onError(message);
            }
        });
    }

    private void advanceRenewals(Set<String> liveIds, Set<String> nowMineOrderIds, int chainBlock, Listener l) {
        for (final Pending p : new ArrayList<>(pending.values())) {
            if (liveIds.contains(p.oldCoinid)) continue;                 // cancel not mined yet → wait
            // The fresh coin shares the orderId, so it's only "done" once the OLD coin is gone AND the
            // orderId is back in the book (the new coin). This also makes a restart idempotent: if the
            // recreate already landed, we detect it here instead of posting a duplicate.
            if (nowMineOrderIds.contains(p.orderId)) { finish(p); continue; }
            if (recreateInFlight.contains(p.orderId)) continue;         // a recreate send is in progress
            // If a recreate was already posted, wait a few BLOCKS (not scans — process() runs many times
            // per block on NEWBALANCE bursts) for it to mine before posting another, so a slow recreate
            // can't be duplicated into a second order / double-lock.
            if (p.recreateSent && p.recreateBlock > 0 && chainBlock - p.recreateBlock < RECREATE_WAIT_BLOCKS) continue;
            recreateInFlight.add(p.orderId);                            // cancel mined, no new coin yet → re-place
            final int postedAt = chainBlock;
            txn.recreate(p.lockAmt, p.lockTokUsdt, p.state, new LimitTxn.RenewResult() {
                @Override public void onRelocked(String txpowid) {
                    recreateInFlight.remove(p.orderId);
                    p.recreateSent = true; p.recreateBlock = postedAt; p.fundsMissing = 0; persistRenewals();
                }
                @Override public void onFundsMissing() {
                    // The cancelled funds never returned (a cancel/collect would have returned them same
                    // block) → the order was FILLED. Grace a scan for indexing lag, then record the fill
                    // (which we'd otherwise miss, since `renewing` suppressed the maker-fill detector).
                    recreateInFlight.remove(p.orderId);
                    if (++p.fundsMissing >= FUNDS_MISSING_GIVEUP) {
                        if (l != null && p.snap != null) l.onMakerFilled(p.snap);
                        finish(p);
                    } else persistRenewals();
                }
                @Override public void onError(String message) {
                    // Funds ARE back but the re-lock failed — retry; on give-up the funds sit safely in the
                    // wallet and the order is gone, so tell the user to re-place it.
                    recreateInFlight.remove(p.orderId);
                    p.recreateSent = false; p.recreateBlock = 0; p.fundsMissing = 0;   // funds were present → break the missing-streak
                    if (++p.retries > MAX_RECREATE_RETRIES) {
                        if (l != null && p.snap != null) l.onRenewalStranded(p.snap);
                        finish(p);
                    }
                    persistRenewals();
                    if (l != null) l.onError(message);
                }
            });
        }
    }

    private void finish(Pending p) {
        renewing.remove(p.oldCoinid);
        pending.remove(p.orderId);
        persistRenewals();
    }

    /** Build the history entry for one of my orders that a taker filled. */
    public static Trade makerTrade(OrderSnap o, int block, double gecko) {
        Trade t = new Trade();
        t.time = System.currentTimeMillis();
        t.kind = o.sell ? "SELL" : "BUY";
        t.minima = o.minima; t.usdt = o.usdt; t.price = o.price;
        t.gecko = gecko; t.block = block; t.coinid = o.coinid;
        return t;
    }

    // ----- persistence -----
    private Map<String, OrderSnap> loadSnapshot() {
        Map<String, OrderSnap> m = new HashMap<>();
        if (store == null) return m;
        JSONArray a = store.myOrdersRaw();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            OrderSnap s = OrderSnap.from(o);
            if (s != null) m.put(s.coinid, s);
        }
        return m;
    }
    private void persistSnapshot(Map<String, OrderSnap> mine) {
        if (store == null) return;
        JSONArray a = new JSONArray();
        for (OrderSnap s : mine.values()) a.put(s.toJson());
        store.putMyOrdersRaw(a);
    }
    /** Re-read the shared persisted state (snapshot + renewals) so this instance is coherent with the
     *  other processor. Transient per-instance sets (recreateInFlight/collecting) are left untouched. */
    private void reloadPersistedState() {
        prevMine = loadSnapshot();
        pending.clear();
        renewing.clear();
        loadRenewals();
    }
    private void loadRenewals() {
        if (store == null) return;
        JSONArray a = store.renewalsRaw();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Pending p = Pending.from(o);
            if (p != null) { pending.put(p.orderId, p); renewing.add(p.oldCoinid); }
        }
    }
    private void persistRenewals() {
        if (store == null) return;
        JSONArray a = new JSONArray();
        for (Pending p : pending.values()) a.put(p.toJson());
        store.putRenewalsRaw(a);
    }

    /** One in-flight GTC renewal (cancel posted; recreate happens once the cancel mines). */
    static class Pending {
        final String orderId, oldCoinid, lockAmt, state;
        final boolean lockTokUsdt;
        int retries;
        boolean recreateSent;     // a recreate has been posted (persisted so a restart waits for it, not re-posts)
        int recreateBlock;        // block height the recreate was posted at (block-based wait; 0 = none)
        final OrderSnap snap;     // the order at renewal time — for a fill/stranded record
        int fundsMissing;         // consecutive scans the returned funds were absent (→ order was filled)
        Pending(String orderId, String oldCoinid, String lockAmt, boolean lockTokUsdt, String state,
                int retries, boolean recreateSent, int recreateBlock, OrderSnap snap, int fundsMissing) {
            this.orderId = orderId; this.oldCoinid = oldCoinid; this.lockAmt = lockAmt;
            this.lockTokUsdt = lockTokUsdt; this.state = state; this.retries = retries;
            this.recreateSent = recreateSent; this.recreateBlock = recreateBlock;
            this.snap = snap; this.fundsMissing = fundsMissing;
        }
        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("orderId", orderId); o.put("oldCoinid", oldCoinid); o.put("lockAmt", lockAmt);
                o.put("lockTokUsdt", lockTokUsdt); o.put("state", state); o.put("retries", retries);
                o.put("recreateSent", recreateSent); o.put("recreateBlock", recreateBlock);
                o.put("fundsMissing", fundsMissing);
                if (snap != null) o.put("snap", snap.toJson());
            } catch (Exception ignored) {}
            return o;
        }
        static Pending from(JSONObject o) {
            String oid = o.optString("orderId", "");
            if (oid.isEmpty()) return null;
            OrderSnap snap = o.optJSONObject("snap") != null ? OrderSnap.from(o.optJSONObject("snap")) : null;
            return new Pending(oid, o.optString("oldCoinid", ""), o.optString("lockAmt", "0"),
                    o.optBoolean("lockTokUsdt", false), o.optString("state", ""), o.optInt("retries", 0),
                    o.optBoolean("recreateSent", false), o.optInt("recreateBlock", 0), snap, o.optInt("fundsMissing", 0));
        }
    }

    /** A compact, persistable view of one of my orders (for fill detection / notifications). */
    public static class OrderSnap {
        public final String coinid;
        public final boolean sell;
        public final String minima, usdt, price;
        public final long created;
        OrderSnap(String coinid, boolean sell, String minima, String usdt, String price, long created) {
            this.coinid = coinid; this.sell = sell; this.minima = minima;
            this.usdt = usdt; this.price = price; this.created = created;
        }
        static OrderSnap of(Order o) {
            return new OrderSnap(o.coinid(), o.isSell(),
                    PriceMath.fmtDisplay(o.minimaAmount()), PriceMath.fmtDisplay(o.usdtAmount()),
                    PriceMath.fmtPrice(o.price()), o.createdBlock());
        }
        boolean expired(int tip) { return created >= 0 && (tip - created) > LimitContract.EXPIRY_BLOCKS; }
        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("coinid", coinid); o.put("sell", sell); o.put("minima", minima);
                o.put("usdt", usdt); o.put("price", price); o.put("created", created);
            } catch (Exception ignored) {}
            return o;
        }
        static OrderSnap from(JSONObject o) {
            String id = o.optString("coinid", "");
            if (id.isEmpty()) return null;
            return new OrderSnap(id, o.optBoolean("sell", false), o.optString("minima", "0"),
                    o.optString("usdt", "0"), o.optString("price", "0"), o.optLong("created", -1));
        }
    }
}
