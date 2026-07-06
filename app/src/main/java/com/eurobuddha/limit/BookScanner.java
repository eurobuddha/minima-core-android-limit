package com.eurobuddha.limit;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the shared order book the way the web dapp does (lint.js): one {@code coins address:} call per
 * contract version V1–V4, merged and de-duped by coinid. Reading the four addresses separately (rather
 * than the old single V4 call) matches the dapp and surfaces orders on the legacy contracts too.
 *
 * Crucially it distinguishes a genuinely empty book from a transport failure: if ANY address call comes
 * back as a non-array (the node's heavy-response / IPC size cap, or an error), {@code truncated} is set
 * so the caller can keep its last good view and show a "syncing" hint instead of blanking the book.
 * Returns ALL valid orders (including expired and the caller's own) — display-level filtering (dust,
 * expiry) is the view's job, and the auto-collector needs the expired ones.
 */
public final class BookScanner {

    public interface Cb { void onBook(List<Order> orders, boolean truncated); }

    // All four versions are read; new orders are only ever created on V4.
    private static final String[] ADDRS = {
            LimitContract.ADDR_V1, LimitContract.ADDR_V2, LimitContract.ADDR_V3, LimitContract.ADDR_V4
    };

    private BookScanner() {}

    public static void scan(NodeApi node, Cb cb) {
        // NodeApi delivers callbacks on the main thread, so these accumulators need no synchronization.
        final Map<String, Order> merged = new LinkedHashMap<>();
        final Set<String> seen = new HashSet<>();
        final Set<String> relevantIds = new HashSet<>();
        final int[] remaining = { ADDRS.length * 2 };
        final boolean[] truncated = { false };

        final Runnable done = () -> {
            if (--remaining[0] != 0) return;
            // Node-side ownership belt: mark orders the node itself reported as relevant (its wallet
            // matched a state var — the port-0 owner key — against its own keys). Survives reinstalls
            // and keys-load failures; Order.isMine honors it alongside the key-set match.
            for (String id : relevantIds) { Order o = merged.get(id); if (o != null) o.markRelevant(); }
            cb.onBook(new ArrayList<>(merged.values()), truncated[0]);
        };

        for (String addr : ADDRS) {
            node.cmd("coins address:" + addr, new NodeApi.Cb() {
                @Override public void onResult(JSONObject json) {
                    JSONArray arr = json.optJSONArray("response");
                    if (arr == null) {
                        truncated[0] = true;                 // empty/over-limit/non-array — flag, don't blank
                    } else {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject c = arr.optJSONObject(i);
                            if (c == null) continue;
                            Order o = new Order(Coin.from(c));
                            if (o.valid() && seen.add(o.coinid())) merged.put(o.coinid(), o);
                        }
                    }
                    done.run();
                }
                @Override public void onError(String message) { truncated[0] = true; done.run(); }
            });

            // Ownership belt query — BEST-EFFORT and safe by construction: `relevant:true` only returns coins
            // whose state carries one of THIS node's keys (port 0 = owner pk), so it can never false-positive on
            // a stranger's order, and when it returns nothing (unverified at a shared script address — some
            // nodes need coinnotify first) isMine falls back to the hardened key-set match. A failure here does
            // NOT mark the book truncated (the key-set match remains the PRIMARY ownership signal — the real fix).
            node.cmd("coins relevant:true address:" + addr, new NodeApi.Cb() {
                @Override public void onResult(JSONObject json) {
                    JSONArray arr = json.optJSONArray("response");
                    if (arr != null) for (int i = 0; i < arr.length(); i++) {
                        JSONObject c = arr.optJSONObject(i);
                        if (c != null) { String id = c.optString("coinid", ""); if (!id.isEmpty()) relevantIds.add(id); }
                    }
                    done.run();
                }
                @Override public void onError(String message) { done.run(); }
            });
        }
    }
}
