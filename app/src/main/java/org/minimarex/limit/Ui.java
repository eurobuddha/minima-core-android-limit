package org.minimarex.limit;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Programmatic-UI helpers, styled 1:1 with the Limit web dapp's {@code style.css}: 8px radius, 2px
 * borders, white surfaces on a cream page, Inter (sans) for titles/inputs and JetBrains Mono for
 * prices/labels/buttons, soft shadows. All colours/typefaces come from {@link Theme}.
 */
public final class Ui {

    static final float RADIUS = 8f;     // --radius
    static final float STROKE = 2f;     // 2px component borders

    private Ui() {}

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    // ---- shapes ----
    public static GradientDrawable rounded(int fill, int stroke, float radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) g.setStroke(dp(c, STROKE), stroke);
        return g;
    }

    /** Per-corner rounded rect (top/bottom only), for stitched book head/last-row corners. */
    public static GradientDrawable roundedCorners(int fill, int stroke, float tl, float tr, float br, float bl, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        float a = dp(c, tl), b = dp(c, tr), d = dp(c, br), e = dp(c, bl);
        g.setCornerRadii(new float[]{a, a, b, b, d, d, e, e});
        if (stroke != 0) g.setStroke(dp(c, STROKE), stroke);
        return g;
    }

    public static GradientDrawable gradient(int from, int to, float radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{from, to});
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    // ---- containers ----
    public static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /** White card: surface bg, 2px border, radius 8, padding 14, soft shadow, 12dp bottom margin. */
    public static LinearLayout card(Context c) {
        LinearLayout l = col(c);
        l.setBackground(rounded(Theme.surface(), Theme.border(), RADIUS, c));
        l.setElevation(dp(c, 1.5f));
        int p = dp(c, 14);
        l.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 12);
        l.setLayoutParams(lp);
        return l;
    }

    // ---- text ----
    /** Inter (sans). */
    public static TextView text(Context c, String s, int color, float sp, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setTypeface(Theme.body(), bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    /** JetBrains Mono. */
    public static TextView mono(Context c, String s, int color, float sp, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setTypeface(bold ? Theme.pixel() : Theme.mono(), bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    /** Uppercase mono label, dim, letter-spaced — column heads / field labels in mono contexts. */
    public static TextView monoLabel(Context c, String s, int color, float sp) {
        TextView t = mono(c, s.toUpperCase(), color, sp, true);
        t.setLetterSpacing(0.08f);
        return t;
    }

    /** Uppercase sans field label (web .field__label inherits Inter 13/600). */
    public static TextView label(Context c, String s) {
        TextView t = text(c, s.toUpperCase(), Theme.dim(), 13, true);
        t.setLetterSpacing(0.06f);
        return t;
    }

    // ---- buttons ----
    /** Solid filled button (buy/sell/place), mono uppercase bold. */
    public static Button button(Context c, String s, int bg, int fg) {
        Button b = baseButton(c, s, fg);
        b.setBackground(rounded(bg, bg, RADIUS, c));
        b.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12));
        return b;
    }

    /** Small action button (book row BUY/SELL): compact, mono 11sp. */
    public static Button buttonSm(Context c, String s, int bg, int fg) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(true);
        b.setTextColor(fg);
        b.setTextSize(11);
        b.setTypeface(Theme.pixel(), Typeface.BOLD);
        b.setLetterSpacing(0.06f);
        b.setBackground(rounded(bg, bg, RADIUS, c));
        b.setPadding(dp(c, 4), dp(c, 6), dp(c, 4), dp(c, 6));
        b.setMinWidth(0); b.setMinHeight(0); b.setMinimumWidth(0); b.setMinimumHeight(0);
        return b;
    }

    /** Outline/ghost button (cancel, EXIT): transparent fill, coloured 2px border + text. */
    public static Button buttonOutline(Context c, String s, int color) {
        Button b = baseButton(c, s, color);
        b.setBackground(rounded(0x00000000, color, RADIUS, c));
        b.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
        return b;
    }

    private static Button baseButton(Context c, String s, int fg) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(true);
        b.setTextColor(fg);
        b.setTextSize(13);
        b.setTypeface(Theme.pixel(), Typeface.BOLD);
        b.setLetterSpacing(0.06f);
        b.setStateListAnimator(null);
        b.setMinWidth(0); b.setMinHeight(0); b.setMinimumWidth(0); b.setMinimumHeight(0);
        return b;
    }

    /** Small rounded badge (e.g. FILLING…, status). */
    public static TextView badge(Context c, String s, int fg, int bg) {
        TextView t = mono(c, s.toUpperCase(), fg, 10, true);
        t.setLetterSpacing(0.05f);
        t.setBackground(rounded(bg, 0, RADIUS, c));
        t.setPadding(dp(c, 8), dp(c, 3), dp(c, 8), dp(c, 3));
        return t;
    }

    /** Header wallet pill: white surface, 1px border, radius 8, soft shadow, mono 13/600. */
    public static TextView pill(Context c, String s) {
        TextView t = mono(c, s, Theme.text(), 13, true);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Theme.surface());
        g.setCornerRadius(dp(c, RADIUS));
        g.setStroke(dp(c, 1), Theme.border());
        t.setBackground(g);
        t.setElevation(dp(c, 1f));
        t.setPadding(dp(c, 12), dp(c, 5), dp(c, 12), dp(c, 5));
        return t;
    }

    /** A 4dp coloured left-edge strip to prepend to a book row (web .book__row--buy/--sell). */
    public static View leftEdge(Context c, int color) {
        View v = new View(c);
        v.setBackgroundColor(color);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 4), ViewGroup.LayoutParams.MATCH_PARENT));
        return v;
    }

    // ---- layout params ----
    public static LinearLayout.LayoutParams lpRow(Context c, float weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }

    /** Fixed-width weighted-less column (for the Side / action columns). */
    public static LinearLayout.LayoutParams lpFixed(Context c, float widthDp) {
        return new LinearLayout.LayoutParams(dp(c, widthDp), ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static void marginTop(View v, int px) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) ((LinearLayout.LayoutParams) lp).topMargin = px;
    }
}
