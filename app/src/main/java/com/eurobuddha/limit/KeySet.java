package com.eurobuddha.limit;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Robust node key-set loader shared by {@link MainActivity} and {@link LimitService}. {@code Order.isMine}
 * hinges entirely on this set containing ALL the node's keys — a single silent {@code keys} failure used to
 * collapse it to just the current getaddress default, hiding the user's own orders in the UI (no cancel)
 * while the background renewer, whose own load had succeeded, kept renewing them.
 *
 * Hardening:
 *   1. SEED synchronously from a prefs cache so isMine works from the very first render;
 *   2. refresh from the node with RETRY + backoff — an error/empty response never shrinks the set;
 *   3. a fresh successful load REPLACES set + cache (self-heals a reseeded node);
 *   4. the callback fires on every successful load so the UI re-renders with the real keys
 *      (the old code never re-rendered when the async keys landed).
 */
public class KeySet {

    public interface Fresh { void onKeysFresh(); }

    private static final long[] BACKOFF_MS = { 10_000, 30_000, 90_000, 300_000 };

    private final NodeApi node;
    private final TradeStore store;
    private final Set<String> keys;                      // the host's live set (shared reference)
    private final Handler h = new Handler(Looper.getMainLooper());
    private String extraPk = "";
    private int attempt = 0;
    private boolean fresh = false;                       // a live `keys` load succeeded this process
    private boolean retryScheduled = false;

    public KeySet(NodeApi node, TradeStore store, Set<String> hostKeys) {
        this.node = node; this.store = store; this.keys = hostKeys;
        keys.addAll(store.nodeKeys());                   // seed from cache — isMine works immediately
    }

    /** The current getaddress default — always unioned in (matches the old fallback behavior). */
    public void setExtraPk(String pk) {
        extraPk = pk == null ? "" : pk;
        if (!extraPk.isEmpty()) keys.add(extraPk);
    }

    /** Usable for isMine/processing: fresh from the node, or at least a non-empty cache. */
    public boolean ready() { return fresh || !keys.isEmpty(); }

    /** Load {@code keys} from the node; retries with backoff on error/empty. Never shrinks the set on failure. */
    public void refresh(Fresh cb) {
        node.cmd("keys", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                Object resp = json.opt("response");
                JSONArray arr = resp instanceof JSONArray ? (JSONArray) resp
                        : resp instanceof JSONObject ? ((JSONObject) resp).optJSONArray("keys") : null;
                Set<String> loaded = new HashSet<>();
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject k = arr.optJSONObject(i);
                    if (k != null) { String pk = k.optString("publickey", ""); if (!pk.isEmpty()) loaded.add(pk); }
                }
                if (loaded.isEmpty()) { scheduleRetry(cb); return; }   // truncated/odd response — keep what we have
                if (!extraPk.isEmpty()) loaded.add(extraPk);
                keys.clear();                                          // full replace: self-heals a reseeded node
                keys.addAll(loaded);
                store.putNodeKeys(loaded);
                fresh = true; attempt = 0;
                if (cb != null) cb.onKeysFresh();
            }
            @Override public void onError(String message) { scheduleRetry(cb); }
        });
    }

    private void scheduleRetry(Fresh cb) {
        if (retryScheduled) return;
        retryScheduled = true;
        long delay = BACKOFF_MS[Math.min(attempt++, BACKOFF_MS.length - 1)];
        h.postDelayed(() -> { retryScheduled = false; refresh(cb); }, delay);
    }
}
