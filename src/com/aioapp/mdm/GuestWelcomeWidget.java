package com.aioapp.mdm;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.RemoteViews;

/**
 * The 4x1 welcome card on a T7's home screen: "Welcome to <restaurant>" over
 * "<table> · tap to see the menu". A tap opens the AIO guest ordering app (the venue's
 * guest_app_package when it names one that is installed, else the first installed of
 * {@link GuestInfo#DEFAULT_GUEST_APPS}); with none installed a tap does nothing.
 *
 * The app is resolved when tapped, not when the card is drawn, so an ordering app
 * installed later works without the card having to be redrawn. The tap reaches us as a
 * broadcast; starting the app from here is allowed because this client runs as the system
 * uid, which background-activity-start rules exempt.
 */
public class GuestWelcomeWidget extends AppWidgetProvider {
    private static final String TAG = "GuestWelcomeWidget";
    static final String ACTION_OPEN_GUEST_APP = "com.aioapp.mdm.action.OPEN_GUEST_APP";

    @Override
    public void onUpdate(Context ctx, AppWidgetManager awm, int[] ids) {
        update(ctx, awm, ids);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (ACTION_OPEN_GUEST_APP.equals(intent.getAction())) {
            openGuestApp(ctx);
            return;
        }
        super.onReceive(ctx, intent);
    }

    /** Redraws every welcome card on the device (guest info changed). */
    static void updateAll(Context ctx) {
        try {
            AppWidgetManager awm = AppWidgetManager.getInstance(ctx);
            if (awm == null) return;
            int[] ids = awm.getAppWidgetIds(new ComponentName(ctx, GuestWelcomeWidget.class));
            if (ids != null && ids.length > 0) update(ctx, awm, ids);
        } catch (RuntimeException e) {
            Log.w(TAG, "update failed: " + e.getMessage());
        }
    }

    private static void update(Context ctx, AppWidgetManager awm, int[] ids) {
        GuestInfo g = GuestInfo.load(ctx);
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.guest_welcome_widget);
        v.setTextViewText(R.id.guest_welcome_title, g.hasRestaurant()
                ? ctx.getString(R.string.guest_welcome_title, g.restaurantName)
                : ctx.getString(R.string.guest_welcome_title_plain));
        v.setTextViewText(R.id.guest_welcome_subtitle, g.hasTable()
                ? ctx.getString(R.string.guest_welcome_subtitle_table, g.tableLabel)
                : ctx.getString(R.string.guest_welcome_subtitle));
        Intent open = new Intent(ACTION_OPEN_GUEST_APP).setClass(ctx, GuestWelcomeWidget.class);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        v.setOnClickPendingIntent(R.id.guest_welcome_root, pi);
        v.setOnClickPendingIntent(R.id.guest_welcome_go, pi);
        awm.updateAppWidget(ids, v);
    }

    private static void openGuestApp(Context ctx) {
        Intent launch = GuestInfo.load(ctx).guestAppIntent(ctx);
        if (launch == null) {
            Log.i(TAG, "no guest ordering app installed; ignoring the tap");
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(launch);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "could not open " + launch.getPackage() + ": " + e.getMessage());
        }
    }
}
