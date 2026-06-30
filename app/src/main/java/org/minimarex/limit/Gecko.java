package org.minimarex.limit;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** CoinGecko price feed — display/stats only (NOT a contract oracle). Runs on a worker thread.
 *  Results are cached (TTL) so reopening tabs / frequent reloads don't hit CoinGecko's rate limit. */
public final class Gecko {

    private Gecko() {}

    private static double spotCache = 0; private static long spotAt = 0;
    private static List<double[]> mktCache; private static long mktAt = 0;
    private static final long SPOT_TTL = 60_000, MKT_TTL = 300_000;

    /** Current MINIMA/USD spot, or 0 on failure (cached for 60s). */
    public static double spot() {
        if (spotCache > 0 && System.currentTimeMillis() - spotAt < SPOT_TTL) return spotCache;
        try {
            JSONObject o = new JSONObject(get("https://api.coingecko.com/api/v3/simple/price?ids=minima&vs_currencies=usd"));
            double v = o.optJSONObject("minima").optDouble("usd", 0);
            if (v > 0) { spotCache = v; spotAt = System.currentTimeMillis(); }
            return v;
        } catch (Exception e) { return spotCache; }
    }

    /** 7-day MINIMA/USD price points [ms, price], or empty on failure (cached for 5min). */
    public static List<double[]> market7d() {
        if (mktCache != null && !mktCache.isEmpty() && System.currentTimeMillis() - mktAt < MKT_TTL) return mktCache;
        List<double[]> out = new ArrayList<>();
        try {
            JSONObject o = new JSONObject(get("https://api.coingecko.com/api/v3/coins/minima/market_chart?vs_currency=usd&days=7"));
            JSONArray prices = o.optJSONArray("prices");
            if (prices != null) for (int i = 0; i < prices.length(); i++) {
                JSONArray p = prices.optJSONArray(i);
                if (p != null && p.length() >= 2) out.add(new double[]{ p.optDouble(0), p.optDouble(1) });
            }
        } catch (Exception ignored) {}
        if (!out.isEmpty()) { mktCache = out; mktAt = System.currentTimeMillis(); }
        return out;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(12000);
        con.setInstanceFollowRedirects(true);
        con.setRequestProperty("Accept", "application/json");
        if (con.getResponseCode() != 200) { con.disconnect(); throw new Exception("HTTP " + con.getResponseCode()); }
        InputStream in = con.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        in.close(); con.disconnect();
        return bos.toString("UTF-8");
    }
}
