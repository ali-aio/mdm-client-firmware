package com.aioapp.mdm;

import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * How the client's notifications look and read (N2, picked 23 Oct 2026, "our own words"):
 * the AIO ring as the small icon in coral, the app's own picture as the large one, plain
 * words for what happened ("Nugget updated · 2-pos (code 3)"), updates grouped, and finished
 * notices clearing themselves after ten minutes.
 *
 * It also takes down Android's own "Updated by your admin" / "Installed by your admin". The
 * framework posts that (PackageInstallerSession.sendOnPackageInstalled) for every install
 * whose installer counts as Device Owner, under package "android", tag = the installed
 * package, id NOTE_PACKAGE_STATE. That is uid 1000, the same uid as this client, so the
 * framework lets us cancel it; we post our own notice in its place.
 */
final class Notices {
    private static final String TAG = "MdmNotices";

    static final int ACCENT = 0xFFCF4529;
    static final String GROUP_UPDATES = "com.aioapp.mdm.UPDATES";
    static final long DONE_TIMEOUT_MS = 10 * 60_000L;
    static final long FAILED_TIMEOUT_MS = 60 * 60_000L;
    /** SystemMessage.NOTE_PACKAGE_STATE (frameworks/base/proto/src/system_messages.proto). */
    private static final int NOTE_PACKAGE_STATE = 21;
    private static final int SELF_UPDATED_ID = 1003;

    private static final String PREFS = "mdm_notices";
    private static final String K_VERSION = "version";

    private Notices() {}

    /** The base every client notification shares: AIO glyph, coral, our sub-line. */
    static Notification.Builder base(Context ctx, String channel, String sub) {
        return new Notification.Builder(ctx, channel)
                .setSmallIcon(R.drawable.ic_notify_aio)
                .setColor(ACCENT)
                .setSubText(sub);
    }

    /** Takes down Android's "Updated/Installed by your admin" for [pkg]; harmless when absent. */
    static void dismissAdminNotice(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        try {
            INotificationManager nm = NotificationManager.getService();
            nm.cancelNotificationWithTag("android", "android", pkg, NOTE_PACKAGE_STATE,
                    Process.myUserHandle().getIdentifier());
        } catch (Throwable t) {
            Log.w(TAG, "could not dismiss the admin notice for " + pkg + ": " + t.getMessage());
        }
    }

    /** dismissAdminNotice now and again shortly after: the framework posts it around the
     *  time the install reports back, which can be either side of our callback. */
    static void dismissAdminNoticeSoon(String pkg) {
        dismissAdminNotice(pkg);
        Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(() -> dismissAdminNotice(pkg), 5_000);
        h.postDelayed(() -> dismissAdminNotice(pkg), 30_000);
    }

    /** An app's icon at notification size, or null. */
    static Bitmap bitmap(Drawable d) {
        if (d == null) return null;
        if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
            return ((BitmapDrawable) d).getBitmap();
        }
        int s = 128;
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, s, s);
        d.draw(c);
        return b;
    }

    static Bitmap installedIcon(Context ctx, String pkg) {
        try {
            return bitmap(ctx.getPackageManager().getApplicationIcon(pkg));
        } catch (Exception e) {
            return null;
        }
    }

    /** "2-pos (code 3)". */
    static String versionOf(PackageInfo pi) {
        if (pi == null) return "";
        String name = pi.versionName == null ? "" : pi.versionName.trim();
        long code = pi.getLongVersionCode();
        if (name.isEmpty()) return code > 0 ? "code " + code : "";
        return code > 0 ? name + " (code " + code + ")" : name;
    }

    /**
     * At service start. If this build replaced another (a self-update, or a firmware update
     * that carried a new client), take down Android's notice and the old process's stale
     * "Installing AIO MDM", then say in our words that the client was updated.
     */
    static void afterStart(Context ctx, String channel, String version) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String last = p.getString(K_VERSION, "");
        p.edit().putString(K_VERSION, version).apply();
        dismissAdminNoticeSoon(ctx.getPackageName());
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        try {
            // An install in progress when the process ended (our own update ends it) is never
            // finished by anyone: its progress notice would sit there.
            for (StatusBarNotification sbn : nm.getActiveNotifications()) {
                Notification n = sbn.getNotification();
                if (GROUP_UPDATES.equals(n.getGroup()) && (n.flags & Notification.FLAG_ONGOING_EVENT) != 0) {
                    nm.cancel(sbn.getTag(), sbn.getId());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "stale install notices: " + e.getMessage());
        }
        String text;
        if (!last.isEmpty()) {
            if (last.equals(version)) return;
            text = last + " → " + version + " · nothing to do";
        } else {
            // First start of a build that keeps this record: only speak if it was just installed.
            try {
                PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
                boolean justUpdated = pi.lastUpdateTime != pi.firstInstallTime
                        && System.currentTimeMillis() - pi.lastUpdateTime < 15 * 60_000L;
                if (!justUpdated) return;
            } catch (Exception e) {
                return;
            }
            text = "Now " + version + " · nothing to do";
        }
        Notification n = base(ctx, channel, "Updates")
                .setContentTitle("AIO MDM updated")
                .setContentText(text)
                .setLargeIcon(installedIcon(ctx, ctx.getPackageName()))
                .setGroup(GROUP_UPDATES)
                .setTimeoutAfter(DONE_TIMEOUT_MS)
                .setOnlyAlertOnce(true)
                .build();
        nm.notify(SELF_UPDATED_ID, n);
    }
}
