package org.minimarex.limit;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.core.app.NotificationCompat;

/**
 * One place that builds the Limit notifications, so the foreground {@link MainActivity} and the
 * background {@link LimitService} post the IDENTICAL "your order filled" alert. (Bug fix: the
 * foreground path previously only wrote the in-app ticker and never notified.)
 */
public final class Notifier {

    public static final String CH_FG = "limit_fg";       // ongoing foreground-service notification
    public static final String CH_ALERT = "limit_alert"; // maker-fill alerts
    private static int sAlertId = 2100;

    private Notifier() {}

    public static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(CH_FG, "Limit background",
                    NotificationManager.IMPORTANCE_LOW));
            nm.createNotificationChannel(new NotificationChannel(CH_ALERT, "Limit fills",
                    NotificationManager.IMPORTANCE_DEFAULT));
        }
    }

    /** Post a "your maker order was filled" alert. */
    public static void fill(Context c, boolean sell, String minima, String usdt, String price) {
        ensureChannels(c);
        String title = sell ? "Your SELL order filled" : "Your BUY order filled";
        String body = (sell ? "Sold " : "Bought ") + minima + " MINIMA for " + usdt
                + " USDT @ " + price + " USDT/MINIMA";
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.notify(sAlertId++, new NotificationCompat.Builder(c, CH_ALERT)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setAutoCancel(true)
                .build());
    }
}
