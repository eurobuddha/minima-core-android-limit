package org.minimarex.limit;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.tabs.TabLayout;

import org.json.JSONArray;
import org.json.JSONObject;
import org.minimarex.minimaapi.MinimaAPI;
import org.minimarex.minimaapi.MinimaAPIMessages;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Native Limit DEX. Tabs: ORDER BOOK / NEW ORDER / CHART / HISTORY. Talks to the local Minima Core node
 * over the broadcast-Intent IPC ({@link NodeApi}) and runs the same VERIFYOUT order-book contract(s) as
 * the dapp (identical pinned addresses), so the native book is the SAME book as the dapp.
 */
public class MainActivity extends AppCompatActivity {

    public static final int TAB_BOOK = 0, TAB_NEW = 1, TAB_CHART = 2, TAB_HISTORY = 3;
    public static volatile boolean FOREGROUND = false;

    private NodeApi node;
    private LimitTxn txn;
    private LimitProcessor proc;
    private TradeStore trades;
    private boolean serviceStarted = false;

    private BaseView[] views;
    private ViewPager pager;
    private TextView balanceTv, blockTv, tickerTv, geckoTv;
    private View pairingBanner, liveDot;
    private BroadcastReceiver notifyReceiver;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable reloadTask = this::reload;

    // identity
    private String myPubkey = "", myHexAddr = "";
    private final Set<String> myKeys = new HashSet<>();
    private boolean identityReady = false;

    // chain / book state
    private int chainBlock = 0;
    private String minimaBal = "0", usdtBal = "0";
    private double geckoPrice = 0;            // CoinGecko MINIMA/USD (display/stats only)
    private final List<Order> orders = new ArrayList<>();   // valid open V4 orders
    private final Set<String> filling = new HashSet<>();    // order coinids with a posted-but-unconfirmed fill

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Theme.load(this);
        setContentView(R.layout.activity_main);

        View root = findViewById(R.id.main);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets b = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            v.setPadding(b.left, b.top, b.right, b.bottom);
            return insets;
        });

        balanceTv = findViewById(R.id.balance);
        blockTv = findViewById(R.id.blockNo);
        tickerTv = findViewById(R.id.ticker);
        geckoTv = findViewById(R.id.geckoPrice);
        liveDot = findViewById(R.id.liveDot);
        pairingBanner = findViewById(R.id.pairingBanner);
        ((Button) findViewById(R.id.btnSound)).setOnClickListener(v -> requestReload());
        ((Button) findViewById(R.id.btnExit)).setOnClickListener(v -> finish());
        findViewById(R.id.btnBridge).setOnClickListener(v -> openUrl("https://mxusd.global"));
        // Real build version in the footer (was hardcoded) so the installed build is verifiable.
        ((TextView) findViewById(R.id.footer)).setText(
                "Limit v" + BuildConfig.VERSION_NAME + "  |  VERIFYOUT Smart Contract  |  Bridge USDT  |  GitHub");

        trades = new TradeStore(this);
        node = new NodeApi(this, this::setPaired);
        LimitContract.registerAll(node);

        views = new BaseView[]{ new OrderBookView(this), new NewOrderView(this), new ChartView(this), new HistoryView(this) };
        pager = findViewById(R.id.pager);
        pager.setAdapter(new MainPager(views, new String[]{"ORDER BOOK", "NEW ORDER", "CHART", "HISTORY"}));
        pager.setOffscreenPageLimit(3);
        ((TabLayout) findViewById(R.id.tabs)).setupWithViewPager(pager);
        pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override public void onPageSelected(int p) { inputFocused = false; views[p].onShown(); }
        });

        notifyReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                if (!MinimaAPI.checkMinimaID(MainActivity.this, intent)) return;
                String data = intent.getStringExtra(MinimaAPIMessages.MINIMA_API_NOTIFY_DATA);
                if (data == null) return;
                try {
                    String event = new JSONObject(data).optString("event", "");
                    if ("NEWBLOCK".equals(event) || "NEWBALANCE".equals(event)) requestReload();
                } catch (Exception ignored) {}
            }
        };
        ContextCompat.registerReceiver(this, notifyReceiver,
                new IntentFilter(MinimaAPIMessages.MINIMA_API_NOTIFY), ContextCompat.RECEIVER_EXPORTED);

        log("Starting up…", LOG_INFO);
        requestNotifPermission();
        fetchGecko();
        loadIdentity();
    }

    @Override protected void onResume() { super.onResume(); FOREGROUND = true; inputFocused = false; requestReload(); }
    @Override protected void onPause() { super.onPause(); FOREGROUND = false; }
    @Override protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(reloadTask);
        if (node != null) node.onDestroy();
        if (notifyReceiver != null) try { unregisterReceiver(notifyReceiver); } catch (Exception ignored) {}
    }

    // ===== identity =====
    private void loadIdentity() {
        node.cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                setPaired(true);
                JSONObject r = json.optJSONObject("response");
                if (r != null) { myPubkey = r.optString("publickey", ""); myHexAddr = r.optString("address", ""); }
                loadKeys();
            }
            @Override public void onError(String message) { handleErr(message); }
        });
    }

    private void loadKeys() {
        node.cmd("keys", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                myKeys.clear();
                Object resp = json.opt("response");
                JSONArray arr = resp instanceof JSONArray ? (JSONArray) resp
                        : resp instanceof JSONObject ? ((JSONObject) resp).optJSONArray("keys") : null;
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject k = arr.optJSONObject(i);
                    if (k != null) { String pk = k.optString("publickey", ""); if (!pk.isEmpty()) myKeys.add(pk); }
                }
                if (!myPubkey.isEmpty()) myKeys.add(myPubkey);
                identityReady = !myPubkey.isEmpty() && !myHexAddr.isEmpty();
                txn = new LimitTxn(node, myPubkey, myHexAddr);
                proc = new LimitProcessor(txn, trades);
                if (identityReady) log("Connected · " + Util.shorten(myHexAddr), LOG_OK);
                reload();
            }
            @Override public void onError(String message) { handleErr(message); }
        });
    }

    // ===== loading =====
    public void reload() {
        // Re-check at fire time, not just when scheduled: a reload posted just before a field gained focus
        // (e.g. hopping price→amount) must not run mid-typing. Resumes via onResume/onPageSelected/blur.
        if (inputFocused) return;
        node.cmd("balance", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONArray arr = json.optJSONArray("response");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject b = arr.optJSONObject(i);
                    if (b == null) continue;
                    String tid = b.optString("tokenid", "0x00");
                    String s = b.optString("sendable", "0");
                    if (Util.isMinima(tid)) minimaBal = s;
                    else if (LimitContract.USDT_ID.equalsIgnoreCase(tid)) usdtBal = s;
                }
                balanceTv.setText(Util.tidyAmount(minimaBal) + " MINIMA · " + Util.tidyAmount(usdtBal) + " USDT");
            }
            @Override public void onError(String message) {}
        });

        node.cmd("block", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                setPaired(true);
                JSONObject r = json.optJSONObject("response");
                if (r != null) {
                    String b = r.optString("block", "");
                    if (b.isEmpty()) { JSONObject h = r.optJSONObject("header"); if (h != null) b = h.optString("block", ""); }
                    int prev = chainBlock;
                    try { chainBlock = Integer.parseInt(b); } catch (Exception ignored) {}
                    blockTv.setText("#" + chainBlock);
                    if (chainBlock != prev) pulseDot();
                }
                scanBook();
            }
            @Override public void onError(String message) { handleErr(message); }
        });
    }

    /** Scan the shared order book across V1–V4 (like the dapp), per-block. Distinguishes an empty book
     *  from a transport failure: if the node truncated/errored AND we found nothing, keep the last good
     *  view and show a syncing hint rather than blanking it; otherwise adopt the freshly merged book. */
    public boolean bookTruncated = false;
    private void scanBook() {
        BookScanner.scan(node, (found, truncated) -> {
            setPaired(true);
            bookTruncated = truncated;
            if (found.isEmpty() && truncated && !orders.isEmpty()) {
                log("Syncing order book… (node response truncated)", LOG_WARN);
                refreshAll();
                return;                          // keep the last good book
            }
            Set<String> ids = new HashSet<>();
            for (Order o : found) ids.add(o.coinid());
            orders.clear();
            orders.addAll(found);
            filling.retainAll(ids);              // drop "filling" marks for orders that have left the book
            refreshAll();
            runProcessor();
            maybeStartService();
        });
    }

    /** Run the shared brain: detect maker fills (record + log) and auto-collect MY expired orders.
     *  Same {@link LimitProcessor} the background {@link LimitService} uses, so behaviour matches. */
    private void runProcessor() {
        if (!identityReady || proc == null) return;
        proc.process(new ArrayList<>(orders), new HashSet<>(myKeys), chainBlock, procListener);
    }

    private final LimitProcessor.Listener procListener = new LimitProcessor.Listener() {
        @Override public void onMakerFilled(LimitProcessor.OrderSnap o) {
            // Record once; only the writer that actually adds the row fires the system notification, so
            // the foreground activity + background service never double-notify the same fill.
            boolean fresh = trades.addOncePerCoin(LimitProcessor.makerTrade(o, chainBlock, geckoPrice));
            if (fresh) {
                Notifier.fill(MainActivity.this, o.sell, o.minima, o.usdt, o.price);
                log("Your " + (o.sell ? "SELL" : "BUY") + " order filled", LOG_OK);
                refreshAll();
            }
        }
        @Override public void onError(String message) {}
    };

    /** Start the foreground service (background auto-collect + maker-fill alerts) once we have identity. */
    private void maybeStartService() {
        if (serviceStarted || !identityReady) return;
        serviceStarted = true;
        try { ContextCompat.startForegroundService(this, new Intent(this, LimitService.class)); }
        catch (Exception ignored) {}
        try { LimitWorker.schedule(this); } catch (Exception ignored) {}
        requestBatteryExemption();
    }

    /** Ask once to be exempt from battery optimisation so the order-watcher service keeps detecting
     *  fills while the app is closed (otherwise the OS doze-kills it and fills go unrecorded). */
    private void requestBatteryExemption() {
        try {
            android.content.SharedPreferences p = getSharedPreferences("limit_app", MODE_PRIVATE);
            if (p.getBoolean("asked_batt", false)) return;
            p.edit().putBoolean("asked_batt", true).apply();
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:" + getPackageName())));
            }
        } catch (Exception ignored) {}
    }

    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    // ===== CoinGecko (display/stats only) =====
    private void fetchGecko() {
        new Thread(() -> {
            double p = Gecko.spot();
            if (p > 0) ui.post(() -> { geckoPrice = p; if (geckoTv != null) geckoTv.setText("$" + trimPrice(p)); refreshAll(); });
        }).start();
    }

    private static String trimPrice(double d) {
        return new java.math.BigDecimal(d).setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private void openUrl(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))); } catch (Exception ignored) {}
    }

    // ===== ui helpers =====
    private void refreshAll() { for (BaseView v : views) v.refresh(); }
    private void setPaired(boolean paired) { pairingBanner.setVisibility(paired ? View.GONE : View.VISIBLE); }
    private void handleErr(String message) { if (NodeApi.ERR_NOT_ENABLED.equals(message)) { setPaired(false); log("Enable Minima Limit in Minima Core → Apps", LOG_WARN); } }

    /** Set by NewOrderView while a price/amount field is focused — suspends the reload loop so background
     *  block/balance updates can't relayout the UI under the keyboard and disrupt typing. */
    public volatile boolean inputFocused = false;
    public void requestReload() {
        ui.removeCallbacks(reloadTask);
        if (inputFocused) return;               // don't churn the UI while the user is typing
        ui.postDelayed(reloadTask, 400);
    }
    public void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }
    public void switchTab(int tab) { if (pager != null) pager.setCurrentItem(tab, true); }

    private void pulseDot() {
        if (liveDot == null) return;
        liveDot.animate().cancel(); liveDot.setAlpha(1f);
        liveDot.animate().alpha(0.25f).setDuration(900).start();
    }

    // ===== activity log =====
    public static final int LOG_INFO = 0, LOG_OK = 1, LOG_WARN = 2, LOG_ERR = 3;
    public void log(String msg, int type) {
        if (tickerTv == null) return;
        tickerTv.setTextColor(type == LOG_OK ? Theme.green() : type == LOG_WARN ? Theme.amber()
                : type == LOG_ERR ? Theme.red() : Theme.cyan());
        tickerTv.setText((type == LOG_WARN ? "⏳ " : type == LOG_ERR ? "✕ " : type == LOG_OK ? "✓ " : "› ") + msg);
    }

    // ===== accessors for tab views =====
    public NodeApi node() { return node; }
    public LimitTxn txn() { return txn; }
    public TradeStore trades() { return trades; }
    public List<Order> orders() { return orders; }
    public Set<String> myKeys() { return myKeys; }
    public boolean isFilling(String coinid) { return filling.contains(coinid); }
    public void markFilling(String coinid) { filling.add(coinid); }
    public void unmarkFilling(String coinid) { filling.remove(coinid); }
    public int chainBlock() { return chainBlock; }
    public String myPubkey() { return myPubkey; }
    public String myHexAddr() { return myHexAddr; }
    public boolean identityReady() { return identityReady; }
    public String minimaBalance() { return minimaBal; }
    public String usdtBalance() { return usdtBal; }
    public double geckoPrice() { return geckoPrice; }
}
