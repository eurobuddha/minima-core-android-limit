package org.minimarex.limit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * PRICE CHART tab — faithful rebuild of the web "Trade History" chart view: three white chart cards
 * (7-day CoinGecko line, settlement-price scatter, fill-volume bars) plus a fill-history table.
 * Charts are drawn on a native Canvas (no third-party charting lib) in the muted Limit palette.
 */
public class ChartView extends BaseView {

    private final LinearLayout container;
    private List<double[]> series;   // gecko [ms, price]
    private final java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault());

    public ChartView(MainActivity a) { super(a, R.layout.view_container); container = find(R.id.container); refresh(); }

    @Override public void onShown() {
        if (series == null) new Thread(() -> {
            List<double[]> s = Gecko.market7d();
            if (!s.isEmpty()) act.runOnUiThread(() -> { series = s; refresh(); });
        }).start();
        refresh();
    }

    @Override
    public void refresh() {
        container.removeAllViews();
        container.addView(title("Trade History"));
        container.addView(desc("Filled limit orders — amber points mark each settlement."));

        // --- chart 1: 7-day gecko line ---
        LinearLayout c1 = chartCard("MINIMA / USD — 7-day (CoinGecko)");
        if (series == null || series.isEmpty()) {
            c1.addView(chartEmpty("Loading price…"));
        } else {
            double lo = Double.MAX_VALUE, hi = 0;
            for (double[] p : series) { lo = Math.min(lo, p[1]); hi = Math.max(hi, p[1]); }
            c1.addView(chartCanvas(new MiniChart(act, MiniChart.LINE, lineVals(series), Theme.accent())));
            c1.addView(axisLabel("low $" + trim(lo) + "    high $" + trim(hi)));
        }
        container.addView(c1);

        // newest-first store → oldest-first for left-to-right charts
        List<Trade> trades = act.trades().all();
        List<Trade> chrono = new ArrayList<>(trades);
        Collections.reverse(chrono);

        // --- chart 2: settlement-price scatter ---
        LinearLayout c2 = chartCard("Settlement Prices (USDT/MINIMA)");
        if (chrono.isEmpty()) c2.addView(chartEmpty("No fills yet"));
        else {
            double[] prices = new double[chrono.size()];
            int[] cols = new int[chrono.size()];
            for (int i = 0; i < chrono.size(); i++) {
                prices[i] = Util.dec(chrono.get(i).price).doubleValue();
                cols[i] = "BUY".equals(chrono.get(i).kind) ? Theme.green() : Theme.red();
            }
            MiniChart mc = new MiniChart(act, MiniChart.SCATTER, prices, Theme.accent());
            mc.pointColors = cols;
            c2.addView(chartCanvas(mc));
        }
        container.addView(c2);

        // --- chart 3: fill-volume bars ---
        LinearLayout c3 = chartCard("Fill Volume (USDT)");
        if (chrono.isEmpty()) c3.addView(chartEmpty("No fills yet"));
        else {
            double[] vols = new double[chrono.size()];
            int[] cols = new int[chrono.size()];
            for (int i = 0; i < chrono.size(); i++) {
                vols[i] = Util.dec(chrono.get(i).usdt).doubleValue();
                cols[i] = "BUY".equals(chrono.get(i).kind) ? Theme.green() : Theme.red();
            }
            MiniChart mc = new MiniChart(act, MiniChart.BARS, vols, Theme.accent());
            mc.pointColors = cols;
            c3.addView(chartCanvas(mc));
        }
        container.addView(c3);

        // --- fill-history table ---
        LinearLayout tbl = bookCard();
        tbl.addView(fillHead());
        if (trades.isEmpty()) tbl.addView(empty("No fills yet"));
        else for (int i = 0; i < trades.size(); i++) { tbl.addView(divider(i == 0)); tbl.addView(fillRow(trades.get(i))); }
        container.addView(tbl);
    }

    private static double[] lineVals(List<double[]> series) {
        double[] v = new double[series.size()];
        for (int i = 0; i < series.size(); i++) v[i] = series.get(i)[1];
        return v;
    }

    private static String trim(double d) {
        return new BigDecimal(d).setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    // ----- fill-history table -----
    private View fillHead() {
        LinearLayout r = gridRow();
        r.addView(head("Time", 1.3f));
        r.addView(head("Side", 0.7f));
        r.addView(head("Price", 1f));
        r.addView(head("Amount", 1f));
        r.addView(head("Total", 1f));
        return r;
    }
    private View fillRow(Trade t) {
        int sideColor = "BUY".equals(t.kind) ? Theme.green() : Theme.red();
        LinearLayout r = gridRow();
        r.addView(cell(fmt.format(new java.util.Date(t.time)), Theme.dim(), false, 1.3f));
        r.addView(cell(t.kind, sideColor, true, 0.7f));
        r.addView(cell(t.price, sideColor, false, 1f));
        r.addView(cell(t.minima, Theme.text(), false, 1f));
        r.addView(cell(t.usdt, Theme.text(), false, 1f));
        return r;
    }

    // ----- chart scaffolding -----
    private LinearLayout chartCard(String heading) {
        LinearLayout c = Ui.card(act);
        c.addView(Ui.monoLabel(act, heading, Theme.dim(), 11));
        return c;
    }
    private View chartCanvas(View chart) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(act, 180));
        lp.topMargin = Ui.dp(act, 10);
        chart.setLayoutParams(lp);
        return chart;
    }
    private View chartEmpty(String s) {
        TextView t = Ui.text(act, s, Theme.dim(), 13, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(act, 30), 0, Ui.dp(act, 30));
        return t;
    }
    private TextView axisLabel(String s) {
        TextView t = Ui.mono(act, s, Theme.dim(), 11, false);
        t.setPadding(0, Ui.dp(act, 10), 0, 0);
        return t;
    }

    // ----- shared table bits -----
    private LinearLayout gridRow() {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(act, 12), Ui.dp(act, 9), Ui.dp(act, 12), Ui.dp(act, 9));
        return r;
    }
    private TextView head(String s, float w) { TextView t = Ui.monoLabel(act, s, Theme.dim(), 10); t.setLayoutParams(Ui.lpRow(act, w)); return t; }
    private TextView cell(String s, int color, boolean bold, float w) { TextView t = Ui.mono(act, s, color, 12, bold); t.setLayoutParams(Ui.lpRow(act, w)); return t; }
    private LinearLayout bookCard() {
        LinearLayout l = Ui.col(act);
        l.setBackground(Ui.rounded(Theme.surface(), Theme.border(), Ui.RADIUS, act));
        l.setElevation(Ui.dp(act, 1.5f));
        l.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return l;
    }
    private View divider(boolean head) {
        View v = new View(act);
        v.setBackgroundColor(head ? Theme.border() : Theme.borderLight());
        v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(act, head ? 2 : 1)));
        return v;
    }
    private TextView title(String s) { TextView t = Ui.text(act, s, Theme.text(), 20, true); t.setPadding(0, 0, 0, Ui.dp(act, 4)); return t; }
    private TextView desc(String s) { TextView t = Ui.text(act, s, Theme.dim(), 14, false); t.setPadding(0, 0, 0, Ui.dp(act, 16)); return t; }
    private View empty(String s) {
        TextView t = Ui.text(act, s, Theme.dim(), 14, false);
        t.setGravity(Gravity.CENTER); t.setPadding(0, Ui.dp(act, 24), 0, Ui.dp(act, 24));
        return t;
    }

    /** A minimal native chart: filled line / scatter points / vertical bars, in the Limit palette. */
    static class MiniChart extends View {
        static final int LINE = 0, SCATTER = 1, BARS = 2;
        private final int type;
        private final double[] vals;
        private final int color;
        int[] pointColors;   // per-point colour for scatter/bars (optional)
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);

        MiniChart(Context c, int type, double[] vals, int color) {
            super(c);
            this.type = type; this.vals = vals; this.color = color;
            line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(Ui.dp(c, 2)); line.setColor(color);
            fill.setStyle(Paint.Style.FILL); fill.setColor((color & 0x00FFFFFF) | 0x1F000000);
            pt.setStyle(Paint.Style.FILL);
        }

        @Override protected void onDraw(Canvas cv) {
            int n = vals.length;
            if (n == 0) return;
            float w = getWidth(), h = getHeight(), pad = Math.max(h * 0.10f, Ui.dp(getContext(), 8));
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (double v : vals) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
            if (type == BARS) lo = Math.min(lo, 0);
            if (hi <= lo) hi = lo + 1;

            if (type == LINE) {
                if (n < 2) { drawDots(cv, w, h, pad, lo, hi); return; }
                Path path = new Path(), area = new Path();
                for (int i = 0; i < n; i++) {
                    float x = (float) i / (n - 1) * w;
                    float y = (float) (h - pad - (vals[i] - lo) / (hi - lo) * (h - 2 * pad));
                    if (i == 0) { path.moveTo(x, y); area.moveTo(x, h); area.lineTo(x, y); }
                    else { path.lineTo(x, y); area.lineTo(x, y); }
                }
                area.lineTo(w, h); area.close();
                cv.drawPath(area, fill);
                cv.drawPath(path, line);
            } else if (type == SCATTER) {
                drawDots(cv, w, h, pad, lo, hi);
            } else { // BARS
                float bw = Math.min(w / Math.max(n, 1) * 0.6f, Ui.dp(getContext(), 22));
                for (int i = 0; i < n; i++) {
                    float cx = (n == 1) ? w / 2 : pad + (float) i / (n - 1) * (w - 2 * pad);
                    float y = (float) (h - pad - (vals[i] - lo) / (hi - lo) * (h - 2 * pad));
                    pt.setColor(pointColors != null ? pointColors[i] : color);
                    cv.drawRect(cx - bw / 2, y, cx + bw / 2, h - pad, pt);
                }
            }
        }

        private void drawDots(Canvas cv, float w, float h, float pad, double lo, double hi) {
            int n = vals.length;
            float r = Ui.dp(getContext(), 4);
            for (int i = 0; i < n; i++) {
                float x = (n == 1) ? w / 2 : pad + (float) i / (n - 1) * (w - 2 * pad);
                float y = (float) (h - pad - (vals[i] - lo) / (hi - lo) * (h - 2 * pad));
                pt.setColor(pointColors != null ? pointColors[i] : color);
                cv.drawCircle(x, y, r, pt);
            }
        }
    }
}
