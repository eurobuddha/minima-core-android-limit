package org.minimarex.limit;

import android.content.Context;
import android.graphics.Typeface;

import androidx.core.content.res.ResourcesCompat;

/**
 * Design tokens — a faithful port of the Limit web MiniDapp's CSS (`style.css` :root). The web app is a
 * single bright "cream-paper fintech" theme (no dark mode): warm cream background, white cards, a muted
 * amber accent, Inter (sans) + JetBrains Mono. Every view reads colours/typefaces from here so the whole
 * app matches the dapp pixel-for-pixel. Hex values are 1:1 with the CSS custom properties.
 */
public final class Theme {

    private Theme() {}

    // Bundled fonts (variable TTFs in res/font, exposed at weights via the font-family XMLs).
    private static Typeface sSans;   // Inter
    private static Typeface sMono;   // JetBrains Mono

    /** Load the bundled typefaces once. Colours are constants, so nothing else to load. */
    public static void load(Context c) {
        if (sSans == null) {
            try { sSans = ResourcesCompat.getFont(c, R.font.inter); } catch (Exception ignored) {}
            try { sMono = ResourcesCompat.getFont(c, R.font.jetbrains_mono); } catch (Exception ignored) {}
        }
    }

    // ---- palette (verbatim from style.css :root) ----
    public static int bg()          { return 0xFFF4F2ED; } // --bg
    public static int surface()     { return 0xFFFFFFFF; } // --surface (cards/rows/inputs)
    public static int panel()       { return 0xFFFFFFFF; } // alias of surface (legacy call sites)
    public static int panel2()      { return 0xFFF4F2ED; } // readonly/raised = bg cream
    public static int border()      { return 0xFFD8D4CC; } // --border
    public static int borderLight() { return 0xFFE8E5DE; } // --border-light (row separators)
    public static int text()        { return 0xFF1A1A1A; } // --text
    public static int dim()         { return 0xFF7A7568; } // --dim
    public static int green()       { return 0xFF16A34A; } // --green (buy/bid/positive)
    public static int greenLight()  { return 0xFFDCFCE7; } // --green-light (active BUY toggle)
    public static int red()         { return 0xFFDC2626; } // --red (sell/ask/negative)
    public static int redLight()    { return 0xFFFEE2E2; } // --red-light (active SELL toggle)
    public static int accent()      { return 0xFFB45309; } // --accent (amber/brown)
    public static int accentBg()    { return 0x14B45309; } // --accent-bg (~8% accent)
    public static int accentLight() { return 0xFFFEF3C7; } // --accent-light (summary border)
    public static int buyHover()    { return 0xFF15803D; }
    public static int sellHover()   { return 0xFFB91C1C; }
    public static int onAccent()    { return 0xFFFFFFFF; } // text on solid accent/green/red
    public static int bridgeA()     { return 0xFF2563EB; } // bridge gradient start (blue)
    public static int bridgeB()     { return 0xFF7C3AED; } // bridge gradient end (purple)

    // ---- legacy aliases (older call sites used the casino names; map onto the Limit palette) ----
    public static int gold()  { return accent(); }
    public static int cyan()  { return accent(); }
    public static int amber() { return accent(); }

    // ---- type ----
    public static Typeface body() { return sSans != null ? sSans : Typeface.SANS_SERIF; }   // Inter
    public static Typeface mono() { return sMono != null ? sMono : Typeface.MONOSPACE; }     // JetBrains Mono
    /** Bold display/mono face — JetBrains Mono 700 (picked from the font-family). */
    public static Typeface pixel() { return Typeface.create(mono(), Typeface.BOLD); }
}
