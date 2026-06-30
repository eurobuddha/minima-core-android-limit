package org.minimarex.limit;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Local trade history (SharedPreferences-backed JSON). Keeps the most recent 200 trades. */
public class TradeStore {

    private static final String PREFS = "limit_trades";
    private static final String KEY = "trades";
    private static final String KEY_MINE = "my_orders";   // persisted snapshot of my live orders
    private static final String KEY_RENEW = "gtc_renewals"; // persisted in-flight GTC renewals
    private final SharedPreferences prefs;

    public TradeStore(Context c) { prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** Persisted snapshot of my open orders (so maker-fill detection survives app/service restarts). */
    public JSONArray myOrdersRaw() {
        try { return new JSONArray(prefs.getString(KEY_MINE, "[]")); } catch (Exception e) { return new JSONArray(); }
    }
    public void putMyOrdersRaw(JSONArray a) {
        prefs.edit().putString(KEY_MINE, a == null ? "[]" : a.toString()).apply();
    }

    /** Persisted in-flight GTC renewals (cancel posted, recreate pending) — so a service restart mid-renewal
     *  resumes the re-place instead of stranding the order's funds in the wallet. */
    public JSONArray renewalsRaw() {
        try { return new JSONArray(prefs.getString(KEY_RENEW, "[]")); } catch (Exception e) { return new JSONArray(); }
    }
    public void putRenewalsRaw(JSONArray a) {
        prefs.edit().putString(KEY_RENEW, a == null ? "[]" : a.toString()).apply();
    }

    public List<Trade> all() {
        List<Trade> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(Trade.from(o));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public void add(Trade t) {
        List<Trade> list = all();
        list.add(0, t);
        while (list.size() > 200) list.remove(list.size() - 1);
        JSONArray arr = new JSONArray();
        for (Trade x : list) arr.put(x.toJson());
        prefs.edit().putString(KEY, arr.toString()).apply();
    }

    /** True if a trade settling this coin/order is already recorded. */
    public boolean hasCoin(String coinid) {
        if (coinid == null || coinid.isEmpty()) return false;
        for (Trade t : all()) if (coinid.equals(t.coinid)) return true;
        return false;
    }

    /** Record a coin-bound trade once. The foreground activity and the background service both detect
     *  the same maker fill (each NEWBLOCK reaches both while the app is alive-but-backgrounded), so we
     *  de-dupe by coinid — exactly as the casino does with its history store. Returns true iff it was
     *  newly added (callers gate the system notification on this so it fires exactly once). */
    public boolean addOncePerCoin(Trade t) {
        if (t.coinid != null && !t.coinid.isEmpty() && hasCoin(t.coinid)) return false;
        add(t);
        return true;
    }
}
