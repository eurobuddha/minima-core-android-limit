package org.minimarex.limit;

import org.json.JSONArray;
import org.json.JSONObject;

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
 *   1. MAKER-FILL DETECTION — one of MY resting orders that has left the book WITHOUT having expired
 *      was taken by a counterparty, so the maker has been paid. Fire {@link Listener#onMakerFilled}
 *      so the host can notify + record the trade.
 *   2. AUTO-COLLECT EXPIRED — for MY orders past the 1500-block expiry, post the COINAGE refund path
 *      (capped + de-duped per block) to reclaim the locked funds, exactly as the dapp.
 *
 * My open orders are PERSISTED (via {@link TradeStore}) and the processor seeds its previous-set from
 * that snapshot on construction, so detection survives a service restart and the foreground↔background
 * hand-off — no "skip the first scan" blind spot. Orders that vanish while past expiry are treated as
 * expiry refunds, not fills.
 */
public class LimitProcessor {

    public interface Listener {
        /** One of my resting orders was filled by a taker (maker got paid). */
        void onMakerFilled(OrderSnap order);
        void onError(String message);
    }

    private static final int MAX_COLLECT_PER_BLOCK = 2;

    private final LimitTxn txn;
    private final TradeStore store;
    private final Set<String> collecting = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private Map<String, OrderSnap> prevMine;   // my orders seen last scan (seeded from persistence)

    public LimitProcessor(LimitTxn txn, TradeStore store) {
        this.txn = txn;
        this.store = store;
        prevMine = loadSnapshot();
    }

    public void process(List<Order> book, Set<String> myKeys, int chainBlock, Listener l) {
        if (txn == null) return;

        Map<String, OrderSnap> nowMine = new HashMap<>();
        Set<String> liveIds = new HashSet<>();
        for (Order o : book) {
            liveIds.add(o.coinid());
            if (o.isMine(myKeys)) nowMine.put(o.coinid(), OrderSnap.of(o));
        }

        // 1) maker-fill detection: my orders resting last scan that have now left the book.
        for (Map.Entry<String, OrderSnap> e : prevMine.entrySet()) {
            String id = e.getKey();
            if (nowMine.containsKey(id)) continue;          // still resting
            OrderSnap gone = e.getValue();
            if (gone.expired(chainBlock)) continue;         // expiry refund (by me or anyone), not a fill
            if (collecting.contains(id)) continue;          // we reclaimed it ourselves
            if (l != null) l.onMakerFilled(gone);
        }
        prevMine = nowMine;
        persistSnapshot(nowMine);
        collecting.retainAll(liveIds);                      // bounded; drop collects that have settled

        // 2) auto-collect MY expired orders (anyone may collect; it refunds the maker).
        int done = 0;
        for (Order o : book) {
            if (done >= MAX_COLLECT_PER_BLOCK) break;
            if (!o.expired(chainBlock) || !o.isMine(myKeys)) continue;
            final String id = o.coinid();
            if (!collecting.add(id)) continue;
            done++;
            txn.collectExpired(o, new LimitTxn.Result() {
                @Override public void onPosted(String txpowid) {}
                @Override public void onFailed(String message) {
                    collecting.remove(id);
                    if (l != null) l.onError(message);
                }
            });
        }
    }

    /** Build the history entry for one of my orders that a taker filled. */
    public static Trade makerTrade(OrderSnap o, int block, double gecko) {
        Trade t = new Trade();
        t.time = System.currentTimeMillis();
        t.kind = o.sell ? "SELL" : "BUY";   // my resting side (SELL sold MINIMA, BUY bought MINIMA)
        t.minima = o.minima;
        t.usdt = o.usdt;
        t.price = o.price;
        t.gecko = gecko;
        t.block = block;
        t.coinid = o.coinid;
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

    /** A compact, persistable view of one of my orders — enough to detect a fill, classify expiry,
     *  and build the resulting trade/notification without holding the full {@link Coin}. */
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

        boolean expired(int tip) {
            return created >= 0 && (tip - created) > LimitContract.EXPIRY_BLOCKS;
        }

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
