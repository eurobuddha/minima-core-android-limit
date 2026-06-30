package org.minimarex.limit;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

/**
 * HISTORY tab — faithful rebuild of the web "My Trading History": four bordered white stat cards
 * (Trades / Volume / Avg Price / P&L vs Market) and a tabular trade list. P&L is coloured by sign.
 */
public class HistoryView extends BaseView {

    private final LinearLayout container;
    private final SimpleDateFormat fmt = new SimpleDateFormat("dd MMM HH:mm", Locale.getDefault());

    public HistoryView(MainActivity a) { super(a, R.layout.view_container); container = find(R.id.container); refresh(); }
    @Override public void onShown() { refresh(); }

    @Override
    public void refresh() {
        if (container == null) return;
        container.removeAllViews();
        List<Trade> trades = act.trades().all();

        container.addView(title("My Trading History"));

        // ----- stats -----
        BigDecimal vol = BigDecimal.ZERO, weighted = BigDecimal.ZERO, pnl = BigDecimal.ZERO, totMina = BigDecimal.ZERO;
        for (Trade t : trades) {
            BigDecimal usdt = Util.dec(t.usdt), mina = Util.dec(t.minima), price = Util.dec(t.price);
            vol = vol.add(usdt);
            weighted = weighted.add(price.multiply(mina));
            totMina = totMina.add(mina);
            if (t.gecko > 0) {
                BigDecimal gk = BigDecimal.valueOf(t.gecko);
                BigDecimal edge = "BUY".equals(t.kind) ? gk.subtract(price) : price.subtract(gk);
                pnl = pnl.add(edge.multiply(mina));
            }
        }
        BigDecimal avg = totMina.signum() > 0 ? weighted.divide(totMina, 6, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        int pnlColor = pnl.signum() > 0 ? Theme.green() : pnl.signum() < 0 ? Theme.red() : Theme.text();

        LinearLayout row1 = statRow();
        row1.addView(stat("Trades", String.valueOf(trades.size()), Theme.text(), false));
        row1.addView(statGap());
        row1.addView(stat("Volume (USDT)", PriceMath.fmtDisplay(vol), Theme.text(), true));
        container.addView(row1);
        LinearLayout row2 = statRow();
        row2.addView(stat("Avg Price", trades.isEmpty() ? "—" : PriceMath.fmtPrice(avg), Theme.text(), false));
        row2.addView(statGap());
        row2.addView(stat("P&L vs Market", (pnl.signum() >= 0 ? "+" : "") + PriceMath.fmtDisplay(pnl), pnlColor, true));
        container.addView(row2);

        // ----- trades table -----
        LinearLayout tbl = bookCard();
        tbl.addView(tradeHead());
        if (trades.isEmpty()) {
            tbl.addView(empty("No personal trades yet"));
        } else {
            for (int i = 0; i < trades.size(); i++) {
                tbl.addView(divider(i == 0));
                tbl.addView(tradeRow(trades.get(i)));
            }
        }
        container.addView(tbl);

        container.addView(desc("Tracks your fills and detects when your orders are filled by others."));
    }

    // ----- stat cards -----
    private LinearLayout statRow() {
        LinearLayout r = Ui.row(act);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(act, 10);
        r.setLayoutParams(lp);
        return r;
    }
    private View statGap() { View v = new View(act); v.setLayoutParams(Ui.lpFixed(act, 10)); return v; }
    private View stat(String label, String value, int valColor, boolean fitText) {
        LinearLayout c = Ui.col(act);
        c.setGravity(Gravity.CENTER);
        c.setBackground(Ui.rounded(Theme.surface(), Theme.border(), Ui.RADIUS, act));
        c.setElevation(Ui.dp(act, 1f));
        c.setPadding(Ui.dp(act, 10), Ui.dp(act, 12), Ui.dp(act, 10), Ui.dp(act, 12));
        c.setLayoutParams(Ui.lpRow(act, 1f));
        TextView l = Ui.monoLabel(act, label, Theme.dim(), 10);
        l.setGravity(Gravity.CENTER);
        l.setPadding(0, 0, 0, Ui.dp(act, 4));
        c.addView(l);
        TextView v = Ui.mono(act, value, valColor, 16, true);
        v.setGravity(Gravity.CENTER);
        v.setSingleLine(true);
        c.addView(v);
        return c;
    }

    // ----- trade table -----
    private View tradeHead() {
        LinearLayout r = gridRow();
        r.addView(head("Date", 1.2f));
        r.addView(head("Side", 0.7f));
        r.addView(head("Amount", 1f));
        r.addView(head("Price", 1f));
        r.addView(head("Total", 1f));
        return r;
    }
    private View tradeRow(Trade t) {
        boolean isBuy = "BUY".equals(t.kind);
        int sideColor = isBuy ? Theme.green() : Theme.red();
        LinearLayout r = gridRow();
        r.addView(cell(fmt.format(new java.util.Date(t.time)), Theme.dim(), false, 1.2f));
        r.addView(cell(t.kind, sideColor, true, 0.7f));
        r.addView(cell(t.minima, Theme.text(), false, 1f));
        r.addView(cell(t.price, sideColor, false, 1f));
        r.addView(cell(t.usdt, Theme.text(), false, 1f));
        return r;
    }

    // ----- shared bits -----
    private LinearLayout gridRow() {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(act, 12), Ui.dp(act, 9), Ui.dp(act, 12), Ui.dp(act, 9));
        return r;
    }
    private TextView head(String s, float w) {
        TextView t = Ui.monoLabel(act, s, Theme.dim(), 10);
        t.setLayoutParams(Ui.lpRow(act, w));
        return t;
    }
    private TextView cell(String s, int color, boolean bold, float w) {
        TextView t = Ui.mono(act, s, color, 12, bold);
        t.setLayoutParams(Ui.lpRow(act, w));
        return t;
    }
    private LinearLayout bookCard() {
        LinearLayout l = Ui.col(act);
        l.setBackground(Ui.rounded(Theme.surface(), Theme.border(), Ui.RADIUS, act));
        l.setElevation(Ui.dp(act, 1.5f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(act, 8);
        l.setLayoutParams(lp);
        return l;
    }
    private View divider(boolean head) {
        View v = new View(act);
        v.setBackgroundColor(head ? Theme.border() : Theme.borderLight());
        v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(act, head ? 2 : 1)));
        return v;
    }
    private TextView title(String s) {
        TextView t = Ui.text(act, s, Theme.text(), 20, true);
        t.setPadding(0, 0, 0, Ui.dp(act, 14));
        return t;
    }
    private TextView desc(String s) {
        TextView t = Ui.text(act, s, Theme.dim(), 13, false);
        t.setPadding(0, Ui.dp(act, 12), 0, 0);
        return t;
    }
    private View empty(String s) {
        TextView t = Ui.text(act, s, Theme.dim(), 14, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(act, 24), 0, Ui.dp(act, 24));
        return t;
    }
}
