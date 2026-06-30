package org.minimarex.limit;

import android.content.Intent;
import android.net.Uri;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

/**
 * NEW ORDER tab — faithful rebuild of the web create view. The order FORM (toggle, price/amount inputs,
 * summary, place button, bridge banner) is built ONCE and kept alive; only the dynamic "Your Open Orders"
 * list re-renders on refresh. This is essential: refresh() fires on every NEWBLOCK/NEWBALANCE, and
 * rebuilding the EditTexts mid-typing would clobber whatever the user is entering (the "flick back" bug).
 */
public class NewOrderView extends BaseView {

    private final LinearLayout container;
    private boolean buy = true;        // BUY MINIMA (lock USDT) by default
    private EditText priceIn, amountIn;
    private TextView totalTv, buyBtn, sellBtn;
    private Button placeBtn;
    private LinearLayout ordersBox;    // the ONLY part rebuilt on refresh
    private boolean built = false;
    private final Set<String> cancelling = new HashSet<>();

    public NewOrderView(MainActivity a) {
        super(a, R.layout.view_container);
        container = find(R.id.container);
        buildOnce();
        refresh();
    }
    @Override public void onShown() { act.requestReload(); refresh(); }

    // ===== static form, built a single time =====
    private void buildOnce() {
        container.removeAllViews();

        container.addView(title("Place a Limit Order"));
        container.addView(desc("MINIMA/USDT — lock funds on-chain at your price. Full fill only."));

        // joined segmented toggle
        LinearLayout box = Ui.row(act);
        box.setBackground(Ui.rounded(Theme.surface(), Theme.border(), Ui.RADIUS, act));
        box.setClipToOutline(true);
        box.setElevation(Ui.dp(act, 1f));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.bottomMargin = Ui.dp(act, 20);
        box.setLayoutParams(blp);
        buyBtn = segBtn("BUY MINIMA", true);
        sellBtn = segBtn("SELL MINIMA", false);
        box.addView(buyBtn);
        box.addView(sellBtn);
        container.addView(box);

        container.addView(fieldLabel("Price per Minima (USDT)"));
        priceIn = input("0.00000");
        container.addView(priceIn);
        container.addView(fieldLabel("Amount of Minima"));
        amountIn = input("0.00");
        container.addView(amountIn);
        container.addView(spacer(4));

        totalTv = Ui.mono(act, "", Theme.text(), 14, true);
        LinearLayout summary = new LinearLayout(act);
        summary.setBackground(Ui.rounded(Theme.accentBg(), Theme.accentLight(), Ui.RADIUS, act));
        int sp = Ui.dp(act, 14);
        summary.setPadding(sp, sp, sp, sp);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(act, 4);
        summary.setLayoutParams(slp);
        summary.addView(totalTv);
        container.addView(summary);

        TextWatcher w = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) { updateTotal(); }
            public void afterTextChanged(Editable s) {}
        };
        priceIn.addTextChangedListener(w); amountIn.addTextChangedListener(w);

        placeBtn = Ui.button(act, "Place Buy Order", Theme.green(), Theme.onAccent());
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        plp.topMargin = Ui.dp(act, 14);
        placeBtn.setLayoutParams(plp);
        placeBtn.setPadding(0, Ui.dp(act, 14), 0, Ui.dp(act, 14));
        placeBtn.setOnClickListener(v -> submit());
        container.addView(placeBtn);

        container.addView(subtitle("Your Open Orders"));
        ordersBox = bookCard();
        container.addView(ordersBox);

        container.addView(bridgeBanner());

        built = true;
        styleToggle();
        updateTotal();
    }

    // ===== refresh: ONLY dynamic bits (never the inputs) =====
    @Override
    public void refresh() {
        if (!built) return;
        // While the user is typing in a price/amount field, do NOT mutate the view tree. Re-rendering the
        // orders list under a focused EditText disrupts the keyboard's input connection and scrambles /
        // truncates what's being typed (the recurring "0.00575 → garbage" bug). The live total still
        // updates via the field's own TextWatcher; everything re-syncs on blur or the next event.
        if ((priceIn != null && priceIn.hasFocus()) || (amountIn != null && amountIn.hasFocus())) return;
        styleToggle();
        updateTotal();
        renderOrders();
    }

    private void renderOrders() {
        ordersBox.removeAllViews();
        boolean any = false;
        for (Order o : act.orders()) {
            if (!o.isMine(act.myKeys())) continue;
            if (any) ordersBox.addView(divider());
            ordersBox.addView(myOrderRow(o));
            any = true;
        }
        if (!any) ordersBox.addView(empty("No orders placed"));
    }

    // ===== toggle =====
    private TextView segBtn(String label, boolean isBuy) {
        TextView t = Ui.mono(act, label, Theme.dim(), 14, true);
        t.setAllCaps(true);
        t.setLetterSpacing(0.06f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(act, 12), 0, Ui.dp(act, 12));
        t.setLayoutParams(Ui.lpRow(act, 1f));
        t.setOnClickListener(v -> { buy = isBuy; styleToggle(); updatePlaceBtn(); updateTotal(); });
        return t;
    }
    private void styleToggle() {
        styleSeg(buyBtn, true);
        styleSeg(sellBtn, false);
        updatePlaceBtn();
    }
    private void styleSeg(TextView t, boolean isBuy) {
        boolean active = (buy == isBuy);
        t.setBackgroundColor(active ? (isBuy ? Theme.greenLight() : Theme.redLight()) : Theme.surface());
        t.setTextColor(active ? (isBuy ? Theme.green() : Theme.red()) : Theme.dim());
    }
    private void updatePlaceBtn() {
        if (placeBtn == null) return;
        placeBtn.setText(buy ? "Place Buy Order" : "Place Sell Order");
        placeBtn.setBackground(Ui.rounded(buy ? Theme.green() : Theme.red(), buy ? Theme.green() : Theme.red(), Ui.RADIUS, act));
        placeBtn.setPadding(0, Ui.dp(act, 14), 0, Ui.dp(act, 14));
    }

    private void updateTotal() {
        if (totalTv == null) return;
        BigDecimal price = Util.dec(priceIn.getText().toString().trim()).max(BigDecimal.ZERO);
        BigDecimal amt = Util.dec(amountIn.getText().toString().trim()).max(BigDecimal.ZERO);
        totalTv.setText("Total USDT to lock: " + PriceMath.fmtDisplay(PriceMath.total(amt, price)));
    }

    private void submit() {
        if (!act.identityReady()) { act.toast("Still connecting…"); return; }
        BigDecimal price = Util.dec(priceIn.getText().toString().trim());
        BigDecimal amt = Util.dec(amountIn.getText().toString().trim());
        if (price.signum() <= 0) { act.toast("Enter a price"); return; }
        if (amt.compareTo(PriceMath.MIN_ORDER) < 0) { act.toast("Minimum order is 0.01 MINIMA"); return; }
        act.log((buy ? "Placing BUY " : "Placing SELL ") + PriceMath.fmtDisplay(amt) + " @ " + PriceMath.fmtPrice(price), MainActivity.LOG_WARN);
        act.txn().createOrder(buy, amt, price, new LimitTxn.Result() {
            @Override public void onPosted(String txpowid) {
                act.log("Order posted — appears in the book shortly", MainActivity.LOG_OK);
                priceIn.setText(""); amountIn.setText(""); act.requestReload();
            }
            @Override public void onFailed(String message) { act.log("Order failed: " + message, MainActivity.LOG_ERR); act.toast(message); }
        });
    }

    // ===== my orders =====
    private View myOrderRow(Order o) {
        boolean expired = o.expired(act.chainBlock());
        int sideColor = o.isSell() ? Theme.red() : Theme.green();
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(act, 8), Ui.dp(act, 9), Ui.dp(act, 12), Ui.dp(act, 9));

        r.addView(Ui.leftEdge(act, sideColor));
        TextView tag = Ui.mono(act, o.isSell() ? "SELL" : "BUY", sideColor, 11, true);
        tag.setLayoutParams(Ui.lpFixed(act, 48));
        r.addView(tag);
        r.addView(cell(PriceMath.fmtPrice(o.price()), sideColor, true, 1.1f));
        r.addView(cell(PriceMath.fmtDisplay(o.minimaAmount()), Theme.text(), false, 1f));

        long left = o.blocksLeft(act.chainBlock());
        long hrsLeft = left * 50 / 3600;
        String age = expired ? "reclaiming" : ("~" + (hrsLeft > 0 ? hrsLeft + "h" : (left * 50 / 60) + "m"));
        r.addView(cell(age, Theme.dim(), false, 1f));

        if (cancelling.contains(o.coinid())) {
            TextView b = Ui.badge(act, "CANCELLING", Theme.accent(), Theme.accentLight());
            b.setLayoutParams(Ui.lpFixed(act, 44));
            b.setGravity(Gravity.CENTER);
            r.addView(b);
        } else {
            Button cancel = Ui.buttonOutline(act, "✕", Theme.red());
            cancel.setTextSize(12);
            cancel.setPadding(0, Ui.dp(act, 6), 0, Ui.dp(act, 6));
            cancel.setLayoutParams(Ui.lpFixed(act, 44));
            cancel.setOnClickListener(v -> doCancel(o));
            r.addView(cancel);
        }
        return r;
    }

    private void doCancel(Order o) {
        cancelling.add(o.coinid());
        renderOrders();
        act.log("Cancelling order…", MainActivity.LOG_WARN);
        act.txn().cancel(o, new LimitTxn.Result() {
            @Override public void onPosted(String txpowid) { act.log("Cancel posted — funds returning", MainActivity.LOG_OK); act.requestReload(); }
            @Override public void onFailed(String message) { cancelling.remove(o.coinid()); act.log("Cancel failed: " + message, MainActivity.LOG_ERR); act.toast(message); renderOrders(); }
        });
    }

    // ===== bridge banner =====
    private View bridgeBanner() {
        LinearLayout b = new LinearLayout(act);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setBackground(Ui.rounded(0x0F2563EB, 0x332563EB, Ui.RADIUS, act));
        int p = Ui.dp(act, 14);
        b.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(act, 24);
        b.setLayoutParams(lp);

        TextView icon = Ui.text(act, "↗", 0xFF2563EB, 22, true);
        icon.setPadding(0, 0, Ui.dp(act, 12), 0);
        b.addView(icon);
        LinearLayout col = Ui.col(act);
        col.addView(Ui.text(act, "Need USDT on Minima?", Theme.text(), 14, true));
        col.addView(Ui.text(act, "Bridge from Ethereum at mxusd.global", Theme.dim(), 13, false));
        b.addView(col);
        b.setOnClickListener(v -> {
            try { act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://mxusd.global"))); }
            catch (Exception ignored) {}
        });
        return b;
    }

    // ===== shared bits =====
    private LinearLayout bookCard() {
        LinearLayout l = Ui.col(act);
        l.setBackground(Ui.rounded(Theme.surface(), Theme.border(), Ui.RADIUS, act));
        l.setElevation(Ui.dp(act, 1.5f));
        l.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return l;
    }
    private View divider() {
        View v = new View(act);
        v.setBackgroundColor(Theme.borderLight());
        v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(act, 1)));
        return v;
    }
    private TextView cell(String s, int color, boolean bold, float weight) {
        TextView t = Ui.mono(act, s, color, 14, bold);
        t.setLayoutParams(Ui.lpRow(act, weight));
        return t;
    }
    private TextView title(String s) {
        TextView t = Ui.text(act, s, Theme.text(), 20, true);
        t.setPadding(0, 0, 0, Ui.dp(act, 4));
        return t;
    }
    private TextView subtitle(String s) {
        TextView t = Ui.text(act, s, Theme.dim(), 16, true);
        t.setPadding(0, Ui.dp(act, 24), 0, Ui.dp(act, 10));
        return t;
    }
    private TextView desc(String s) {
        TextView t = Ui.text(act, s, Theme.dim(), 14, false);
        t.setPadding(0, 0, 0, Ui.dp(act, 20));
        return t;
    }
    private TextView fieldLabel(String s) {
        TextView t = Ui.label(act, s);
        t.setPadding(0, Ui.dp(act, 2), 0, Ui.dp(act, 6));
        return t;
    }
    private View empty(String s) {
        TextView t = Ui.text(act, s, Theme.dim(), 14, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(act, 24), 0, Ui.dp(act, 24));
        return t;
    }
    private View spacer(int dp) { View v = new View(act); v.setLayoutParams(new LinearLayout.LayoutParams(1, Ui.dp(act, dp))); return v; }

    private EditText input(String hint) {
        EditText e = new EditText(act);
        e.setHint(hint);
        e.setHintTextColor(Theme.dim());
        e.setTextColor(Theme.text());
        e.setTextSize(16);
        e.setTypeface(Theme.body());
        // Plain decimal number input: allows '.' and unlimited fraction digits. (A DigitsKeyListener was
        // tried and REMOVED — combined with the periodic refresh it corrupted the IME composing region.)
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setPadding(Ui.dp(act, 14), Ui.dp(act, 12), Ui.dp(act, 14), Ui.dp(act, 12));
        e.setBackground(Ui.rounded(Theme.surface(), Theme.border(), Ui.RADIUS, act));
        e.setOnFocusChangeListener((v, has) -> {
            e.setBackground(Ui.rounded(Theme.surface(), has ? Theme.accent() : Theme.border(), Ui.RADIUS, act));
            act.inputFocused = has;            // suspend the reload loop while typing
            if (!has) { refresh(); act.requestReload(); }   // re-sync once the user finishes typing
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(act, 16);
        e.setLayoutParams(lp);
        return e;
    }
}
