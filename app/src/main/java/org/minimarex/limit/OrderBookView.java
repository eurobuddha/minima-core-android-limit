package org.minimarex.limit;

import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * ORDER BOOK tab — a faithful rebuild of the web dapp's order book (style.css .book): a legend, a single
 * white card stitching a column head + side-tagged rows (each with a 4dp green/red left edge), and an
 * inline Fill Order panel. Every fillable order shows a working BUY/SELL action (the web allows
 * self-fill, so own orders are NOT suppressed). Expired orders are hidden from the book, exactly as the
 * web filters them, but remain in the data so the auto-collector still reclaims them.
 */
public class OrderBookView extends BaseView {

    private final LinearLayout container;
    private Order fillTarget;         // order whose inline fill panel is open
    private String fillStatus = "";   // status line under the panel

    public OrderBookView(MainActivity a) { super(a, R.layout.view_container); container = find(R.id.container); refresh(); }
    @Override public void onShown() { act.requestReload(); refresh(); }

    @Override
    public void refresh() {
        container.removeAllViews();
        container.addView(legend());

        // sells (asks) and buys (bids), price desc, dust + expired hidden (display only).
        List<Order> sells = new ArrayList<>(), buys = new ArrayList<>();
        for (Order o : act.orders()) {
            if (o.minimaAmount().compareTo(PriceMath.MIN_ORDER) < 0) continue;     // dust
            if (o.expired(act.chainBlock())) continue;                              // expired hidden from book
            (o.isSell() ? sells : buys).add(o);
        }
        Collections.sort(sells, Comparator.comparing((Order o) -> o.price()).reversed());
        Collections.sort(buys, Comparator.comparing((Order o) -> o.price()).reversed());

        LinearLayout book = card(0);
        book.addView(headRow());
        if (sells.isEmpty() && buys.isEmpty()) {
            book.addView(empty(act.identityReady()
                    ? (act.bookTruncated ? "Syncing order book…" : "No open orders") : "Connecting to your node…"));
        } else {
            boolean first = true;
            for (Order o : sells) { book.addView(divider(first)); first = false; book.addView(row(o)); }
            for (Order o : buys)  { book.addView(divider(first)); first = false; book.addView(row(o)); }
        }
        container.addView(book);

        if (fillTarget != null) container.addView(fillPanel(fillTarget));
    }

    // ----- legend -----
    private View legend() {
        LinearLayout r = Ui.row(act);
        r.setPadding(Ui.dp(act, 2), Ui.dp(act, 4), 0, Ui.dp(act, 8));
        r.addView(legendItem("BUY", Theme.green()));
        r.addView(legendItem("SELL", Theme.red()));
        r.addView(legendItem("FILLED", Theme.accent()));
        return r;
    }
    private View legendItem(String s, int color) {
        LinearLayout g = Ui.row(act);
        g.setPadding(0, 0, Ui.dp(act, 18), 0);
        View sq = new View(act);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(Ui.dp(act, 10), Ui.dp(act, 10));
        slp.rightMargin = Ui.dp(act, 6);
        sq.setLayoutParams(slp);
        sq.setBackground(Ui.rounded(color, 0, 3, act));
        g.addView(sq);
        g.addView(Ui.mono(act, s, Theme.dim(), 12, true));
        return g;
    }

    // ----- book card + rows -----
    /** A white surface card with no inner padding (rows carry their own). radiusDp matches the web. */
    private LinearLayout card(int unused) {
        LinearLayout l = Ui.col(act);
        l.setBackground(Ui.rounded(Theme.surface(), Theme.border(), Ui.RADIUS, act));
        l.setElevation(Ui.dp(act, 1.5f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(act, 12);
        l.setLayoutParams(lp);
        return l;
    }

    private View headRow() {
        LinearLayout r = gridRow();
        r.addView(edgeSpacer());
        r.addView(headCell("Side", false, 52, 0));
        r.addView(headCell("Price (USDT)", true, 0, 1.1f));
        r.addView(headCell("Amount", true, 0, 1f));
        r.addView(headCell("Total", true, 0, 1f));
        r.addView(headCell("", false, 64, 0));
        return r;
    }

    private View row(Order o) {
        boolean fillIsBuy = o.isSell();                       // filling a SELL = you BUY MINIMA
        boolean filling = act.isFilling(o.coinid());
        int sideColor = o.isSell() ? Theme.red() : Theme.green();

        LinearLayout r = gridRow();
        r.addView(Ui.leftEdge(act, sideColor));               // 4dp coloured left border
        r.addView(dataCell(o.isSell() ? "SELL" : "BUY", sideColor, true, 52, 0));
        r.addView(dataCell(PriceMath.fmtPrice(o.price()), sideColor, true, 0, 1.1f));
        r.addView(dataCell(PriceMath.fmtDisplay(o.minimaAmount()), Theme.text(), false, 0, 1f));
        r.addView(dataCell(PriceMath.fmtDisplay(o.usdtAmount()), Theme.text(), false, 0, 1f));

        if (filling) {
            TextView f = Ui.badge(act, "FILLING…", Theme.accent(), Theme.accentLight());
            f.setLayoutParams(Ui.lpFixed(act, 64));
            f.setGravity(Gravity.CENTER);
            r.addView(f);
        } else {
            // Self-fill allowed (matches the web): a working button on every order.
            Button b = Ui.buttonSm(act, fillIsBuy ? "BUY" : "SELL",
                    fillIsBuy ? Theme.green() : Theme.red(), Theme.onAccent());
            b.setLayoutParams(Ui.lpFixed(act, 64));
            b.setOnClickListener(v -> openFill(o));
            r.addView(b);
        }
        return r;
    }

    private LinearLayout gridRow() {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(act, 8), Ui.dp(act, 9), Ui.dp(act, 12), Ui.dp(act, 9));
        return r;
    }
    private View edgeSpacer() {
        View v = new View(act);
        v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(act, 4), 1));
        return v;
    }
    private TextView headCell(String s, boolean num, float fixedDp, float weight) {
        TextView t = Ui.monoLabel(act, s, Theme.dim(), 11);
        t.setLayoutParams(weight > 0 ? Ui.lpRow(act, weight) : Ui.lpFixed(act, fixedDp));
        return t;
    }
    private TextView dataCell(String s, int color, boolean bold, float fixedDp, float weight) {
        TextView t = Ui.mono(act, s, color, 14, bold);
        t.setLayoutParams(weight > 0 ? Ui.lpRow(act, weight) : Ui.lpFixed(act, fixedDp));
        return t;
    }
    private View divider(boolean head) {
        View v = new View(act);
        v.setBackgroundColor(head ? Theme.border() : Theme.borderLight());
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(act, head ? 2 : 1)));
        return v;
    }
    private View empty(String s) {
        TextView t = Ui.text(act, s, Theme.dim(), 14, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(act, 28), 0, Ui.dp(act, 28));
        return t;
    }

    // ----- inline fill panel -----
    private void openFill(Order o) {
        if (!act.identityReady()) { act.toast("Still connecting…"); return; }
        fillTarget = o; fillStatus = ""; refresh();
    }

    private View fillPanel(Order o) {
        boolean fillIsBuy = o.isSell();
        String payAmt = fillIsBuy ? PriceMath.fmtDisplay(o.usdtAmount()) : PriceMath.fmtDisplay(o.minimaAmount());
        String payUnit = fillIsBuy ? "USDT" : "MINIMA";
        String getAmt = fillIsBuy ? PriceMath.fmtDisplay(o.minimaAmount()) : PriceMath.fmtDisplay(o.usdtAmount());
        String getUnit = fillIsBuy ? "MINIMA" : "USDT";

        LinearLayout p = Ui.card(act);
        p.setBackground(Ui.rounded(Theme.surface(), Theme.border(), Ui.RADIUS, act));
        p.setElevation(Ui.dp(act, 3f));

        p.addView(Ui.text(act, fillIsBuy ? "Buy MINIMA" : "Sell MINIMA", Theme.text(), 17, true));

        LinearLayout info = Ui.row(act);
        info.setPadding(0, Ui.dp(act, 10), 0, Ui.dp(act, 10));
        TextView price = Ui.text(act, "Price " + PriceMath.fmtPrice(o.price()) + " USDT/MINIMA", Theme.dim(), 14, false);
        price.setLayoutParams(Ui.lpRow(act, 1f));
        info.addView(price);
        info.addView(Ui.text(act, "Receive " + getAmt + " " + getUnit, Theme.dim(), 14, false));
        p.addView(info);

        // full-fill only: amount is fixed (read-only)
        EditText amount = new EditText(act);
        amount.setText(getAmt + " " + getUnit);
        amount.setEnabled(false);
        amount.setTextColor(Theme.dim());
        amount.setTextSize(16);
        amount.setInputType(InputType.TYPE_NULL);
        amount.setBackground(Ui.rounded(Theme.bg(), Theme.border(), Ui.RADIUS, act));
        amount.setPadding(Ui.dp(act, 12), Ui.dp(act, 12), Ui.dp(act, 12), Ui.dp(act, 12));
        amount.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        p.addView(amount);

        TextView cost = Ui.mono(act, "Cost: " + payAmt + " " + payUnit, Theme.accent(), 16, true);
        cost.setPadding(0, Ui.dp(act, 12), 0, 0);
        p.addView(cost);

        LinearLayout actions = Ui.row(act);
        actions.setPadding(0, Ui.dp(act, 14), 0, 0);
        Button confirm = Ui.button(act, "Confirm", Theme.green(), Theme.onAccent());
        confirm.setOnClickListener(v -> doFill(o));
        LinearLayout.LayoutParams clp = Ui.lpRow(act, 1f); clp.rightMargin = Ui.dp(act, 10);
        confirm.setLayoutParams(clp);
        Button cancel = Ui.buttonOutline(act, "Cancel", Theme.dim());
        cancel.setOnClickListener(v -> { fillTarget = null; refresh(); });
        cancel.setLayoutParams(Ui.lpRow(act, 1f));
        actions.addView(confirm);
        actions.addView(cancel);
        p.addView(actions);

        if (!fillStatus.isEmpty()) {
            TextView st = Ui.mono(act, fillStatus, Theme.dim(), 13, true);
            st.setPadding(0, Ui.dp(act, 8), 0, 0);
            p.addView(st);
        }
        return p;
    }

    private void doFill(Order o) {
        final String id = o.coinid();
        act.markFilling(id);
        fillStatus = "Filling order…"; fillTarget = null; refresh();
        act.log("Filling order…", MainActivity.LOG_WARN);
        act.txn().fill(o, new LimitTxn.Result() {
            @Override public void onPosted(String txpowid) {
                act.log("Fill posted — settling…", MainActivity.LOG_OK);
                recordFill(o);
                act.requestReload();
            }
            @Override public void onFailed(String message) {
                act.unmarkFilling(id);
                act.log("Fill failed: " + message, MainActivity.LOG_ERR);
                act.toast(message);
                refresh();
            }
        });
    }

    private void recordFill(Order o) {
        Trade t = new Trade();
        t.time = System.currentTimeMillis();
        t.kind = o.isSell() ? "BUY" : "SELL";   // taker's effective side
        t.minima = PriceMath.fmtDisplay(o.minimaAmount());
        t.usdt = PriceMath.fmtDisplay(o.usdtAmount());
        t.price = PriceMath.fmtPrice(o.price());
        t.gecko = act.geckoPrice();
        t.block = act.chainBlock();
        t.coinid = o.coinid();                  // lets the maker-fill detector de-dupe a self-fill
        act.trades().addOncePerCoin(t);
    }
}
