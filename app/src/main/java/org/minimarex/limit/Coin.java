package org.minimarex.limit;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * A single UTXO as returned by the node's "coins" command. Unlike the wallet's Coin, the casino
 * cares about the per-coin {@code state} array (the commit-reveal game state) and the block it was
 * created in (for timeout / @COINAGE checks).
 */
public class Coin {

    public String coinid;
    public String address;
    public String miniaddress;
    public String tokenid;
    public String amount;            // human-readable Minima amount
    public boolean sendable = false; // set from "coins relevant:true sendable:true"
    public long created = -1;        // block height the coin was created in (for coin age)

    /** state port -> value, as strings exactly as stored on-chain. */
    public final Map<Integer, String> state = new HashMap<>();

    public static Coin from(JSONObject c) {
        Coin x = new Coin();
        x.coinid      = c.optString("coinid", "");
        x.address     = c.optString("address", "");
        x.miniaddress = c.optString("miniaddress", "");
        x.tokenid     = c.optString("tokenid", Util.MINIMA_TOKENID);

        boolean minima = Util.isMinima(x.tokenid);
        x.amount = minima ? c.optString("amount", "0")
                          : c.optString("tokenamount", c.optString("amount", "0"));

        // "created" is the block the coin first appeared in; coin age = tip - created.
        String created = c.optString("created", "");
        if (!created.isEmpty()) {
            try { x.created = Long.parseLong(created); } catch (NumberFormatException ignored) {}
        }

        // state is an array of {"port":N,"data":"..."} entries.
        JSONArray st = c.optJSONArray("state");
        if (st != null) {
            for (int i = 0; i < st.length(); i++) {
                JSONObject e = st.optJSONObject(i);
                if (e == null) continue;
                int port = e.optInt("port", -1);
                if (port < 0) continue;
                x.state.put(port, e.optString("data", ""));
            }
        }
        return x;
    }

    public String stateAt(int port) {
        String v = state.get(port);
        return v == null ? "" : v;
    }

    public boolean hasState() {
        return !state.isEmpty();
    }
}
