package org.minimarex.limit;

import org.json.JSONObject;

/** One recorded trade (a fill you made as taker, or one of your orders that got filled / expired). */
public class Trade {
    public long time;
    public String kind;     // "BUY" / "SELL" (your effective side) or "FILL"/"MAKER"/"EXPIRED"
    public String minima;   // MINIMA amount
    public String usdt;     // USDT amount
    public String price;    // USDT per MINIMA
    public double gecko;     // market price at the time (for P&L)
    public int block;
    public String coinid;   // the order/coin this trade settled (empty for taker fills); used to de-dupe

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("time", time); o.put("kind", kind); o.put("minima", minima);
            o.put("usdt", usdt); o.put("price", price); o.put("gecko", gecko); o.put("block", block);
            o.put("coinid", coinid == null ? "" : coinid);
        } catch (Exception ignored) {}
        return o;
    }

    public static Trade from(JSONObject o) {
        Trade t = new Trade();
        t.time = o.optLong("time", 0);
        t.kind = o.optString("kind", "");
        t.minima = o.optString("minima", "0");
        t.usdt = o.optString("usdt", "0");
        t.price = o.optString("price", "0");
        t.gecko = o.optDouble("gecko", 0);
        t.block = o.optInt("block", 0);
        t.coinid = o.optString("coinid", "");
        return t;
    }
}
