package org.minimarex.limit;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.minimarex.minimaapi.MinimaAPI;
import org.minimarex.minimaapi.MinimaAPIMessages;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Foreground service that keeps Limit working when the app is closed: on each new block it auto-collects
 * the user's expired orders and detects maker fills (a resting order taken by a counterparty), notifying
 * and recording the trade so HistoryView shows it when the app reopens. Mirrors the casino's
 * {@code CasinoService}; the shared brain is {@link LimitProcessor}.
 */
public class LimitService extends Service {

    private static final String CH_FG = "limit_fg";
    private static final String CH_ALERT = "limit_alert";
    private static final int FG_ID = 1101;
    private int alertId = 2100;

    private NodeApi node;
    private LimitTxn txn;
    private TradeStore trades;
    private LimitProcessor proc;
    private BroadcastReceiver receiver;

    private String myPubkey = "", myHexAddr = "";
    private final Set<String> myKeys = new HashSet<>();
    private boolean ready = false;
    private int chainBlock = 0;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        createChannels();
        // If the OS refuses the foreground service (e.g. Android 14's dataSync time budget exhausted),
        // bail gracefully instead of crashing — detection resumes when the app is next opened / budget resets.
        if (!startForegroundCompat()) { stopSelf(); return; }

        trades = new TradeStore(this);
        node = new NodeApi(this, enabled -> {});
        LimitContract.registerAll(node);

        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                if (!MinimaAPI.checkMinimaID(LimitService.this, intent)) return;
                String data = intent.getStringExtra(MinimaAPIMessages.MINIMA_API_NOTIFY_DATA);
                if (data == null) return;
                try {
                    String event = new JSONObject(data).optString("event", "");
                    if ("NEWBLOCK".equals(event) || "NEWBALANCE".equals(event)) tick();
                } catch (Exception ignored) {}
            }
        };
        ContextCompat.registerReceiver(this, receiver,
                new IntentFilter(MinimaAPIMessages.MINIMA_API_NOTIFY), ContextCompat.RECEIVER_EXPORTED);

        loadIdentity();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    /** The user swiped the app off the recents list. Keep watching for fills: reschedule the worker and
     *  ask AlarmManager to bring us back in a couple of seconds (stopWithTask=false keeps us running, but
     *  some OEMs kill anyway — this is the belt-and-suspenders restart). */
    @Override public void onTaskRemoved(Intent rootIntent) {
        try { LimitWorker.schedule(getApplicationContext()); } catch (Exception ignored) {}
        try {
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            android.app.PendingIntent pi = android.app.PendingIntent.getForegroundService(
                    getApplicationContext(), 7, new Intent(getApplicationContext(), LimitService.class),
                    android.app.PendingIntent.FLAG_ONE_SHOT | android.app.PendingIntent.FLAG_IMMUTABLE);
            if (am != null) am.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 2000, pi);
        } catch (Exception ignored) {}
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        super.onDestroy();
        if (node != null) node.onDestroy();
        if (receiver != null) { try { unregisterReceiver(receiver); } catch (Exception ignored) {} }
    }

    // ----- identity -----
    private void loadIdentity() {
        node.cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject r = json.optJSONObject("response");
                if (r != null) { myPubkey = r.optString("publickey", ""); myHexAddr = r.optString("address", ""); }
                node.cmd("keys", new NodeApi.Cb() {
                    @Override public void onResult(JSONObject j2) {
                        Object resp = j2.opt("response");
                        JSONArray arr = resp instanceof JSONArray ? (JSONArray) resp
                                : (resp instanceof JSONObject ? ((JSONObject) resp).optJSONArray("keys") : null);
                        if (arr != null) for (int i = 0; i < arr.length(); i++) {
                            JSONObject k = arr.optJSONObject(i);
                            if (k != null) { String pk = k.optString("publickey", ""); if (!pk.isEmpty()) myKeys.add(pk); }
                        }
                        if (!myPubkey.isEmpty()) myKeys.add(myPubkey);
                        ready = !myPubkey.isEmpty() && !myHexAddr.isEmpty();
                        txn = new LimitTxn(node, myPubkey, myHexAddr);
                        proc = new LimitProcessor(txn, trades);
                        tick();
                    }
                    @Override public void onError(String m) {}
                });
            }
            @Override public void onError(String m) {}
        });
    }

    // ----- per-block processing -----
    private void tick() {
        if (!ready || proc == null) return;
        // The foreground Activity owns processing while it's visible — stand down so we don't both post
        // competing collect transactions for the same expired coin.
        if (MainActivity.FOREGROUND) return;
        node.cmd("block", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject r = json.optJSONObject("response");
                if (r != null) {
                    String b = r.optString("block", "");
                    if (b.isEmpty()) { JSONObject h = r.optJSONObject("header"); if (h != null) b = h.optString("block", ""); }
                    try { chainBlock = Integer.parseInt(b); } catch (Exception ignored) {}
                }
                scanAndProcess();
            }
            @Override public void onError(String m) { scanAndProcess(); }
        });
    }

    private void scanAndProcess() {
        BookScanner.scan(node, (book, truncated) -> {
            if (book.isEmpty() && truncated) return;   // transport failure — wait for the next block
            proc.process(book, new HashSet<>(myKeys), chainBlock, listener);
        });
    }

    private final LimitProcessor.Listener listener = new LimitProcessor.Listener() {
        @Override public void onMakerFilled(LimitProcessor.OrderSnap o) {
            // Record once; only the writer that actually adds the row notifies (de-dupes against the
            // foreground activity, which also ticks while the app is alive-but-backgrounded).
            if (trades.addOncePerCoin(LimitProcessor.makerTrade(o, chainBlock, 0))) {
                Notifier.fill(LimitService.this, o.sell, o.minima, o.usdt, o.price);
            }
        }
        @Override public void onError(String m) {}
    };

    // ----- notifications -----
    private void createChannels() { Notifier.ensureChannels(this); }

    /** Start as a foreground service, NEVER crashing. Returns false if the OS refused (the caller stops).
     *  Uses the {@code specialUse} FGS type on Android 14+ — a persistent chain-watcher doesn't fit
     *  {@code dataSync}, which Android 14 caps at ~6h/day and then crashes the service when exceeded. */
    private boolean startForegroundCompat() {
        Notification n = new NotificationCompat.Builder(this, Notifier.CH_FG)
                .setContentTitle("Minima Limit")
                .setContentText("Watching your orders & auto-renewing")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(FG_ID, n);
            }
            return true;
        } catch (Exception e) {
            return false;   // ForegroundServiceStartNotAllowedException etc. — don't crash
        }
    }

    /** Android 14+: the OS tells a time-limited FGS to stop. Stop gracefully instead of crashing with
     *  ForegroundServiceDidNotStopInTimeException. (specialUse isn't time-limited, but this is belt-and-braces.) */
    @Override public void onTimeout(int startId) { stopGracefully(); }
    @Override public void onTimeout(int startId, int fgsType) { stopGracefully(); }
    private void stopGracefully() {
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) {}
        stopSelf();
    }
}
