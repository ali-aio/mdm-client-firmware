package com.aioapp.mdm;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.RemoteViews;

/**
 * The 2x1 guest Wi-Fi card on a T7's home screen: "Free Wi-Fi" over the network name. A tap
 * opens {@link GuestWifiActivity} — the name, the password and a QR code a guest scans to
 * join from their phone. With no network set it asks the guest to ask the staff.
 */
public class GuestWifiWidget extends AppWidgetProvider {
    private static final String TAG = "GuestWifiWidget";

    @Override
    public void onUpdate(Context ctx, AppWidgetManager awm, int[] ids) {
        update(ctx, awm, ids);
    }

    /** Redraws every Wi-Fi card on the device (guest info changed). */
    static void updateAll(Context ctx) {
        try {
            AppWidgetManager awm = AppWidgetManager.getInstance(ctx);
            if (awm == null) return;
            int[] ids = awm.getAppWidgetIds(new ComponentName(ctx, GuestWifiWidget.class));
            if (ids != null && ids.length > 0) update(ctx, awm, ids);
        } catch (RuntimeException e) {
            Log.w(TAG, "update failed: " + e.getMessage());
        }
    }

    private static void update(Context ctx, AppWidgetManager awm, int[] ids) {
        GuestInfo g = GuestInfo.load(ctx);
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.guest_wifi_widget);
        v.setTextViewText(R.id.guest_wifi_title, ctx.getString(R.string.guest_wifi_title));
        v.setTextViewText(R.id.guest_wifi_subtitle,
                g.hasWifi() ? g.wifiSsid : ctx.getString(R.string.guest_wifi_ask_staff));
        Intent open = new Intent(ctx, GuestWifiActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        v.setOnClickPendingIntent(R.id.guest_wifi_root, pi);
        awm.updateAppWidget(ids, v);
    }
}
