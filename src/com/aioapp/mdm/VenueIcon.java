package com.aioapp.mdm;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URLEncoder;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The venue the lost screen names, and its icon (1.13.4, L1). The server sends the venue's
 * name and the icon's version ({@code venue}, {@code venue_icon_v}) in the lost_mode payload
 * and in the config's {@code self_lock}; when the version changes the icon (a PNG of at most
 * 128 px) is fetched once from {@code GET /api/v1/venue-icon} and kept as a file. Everything
 * lives in device-protected storage, so a self-lock or a lost screen after a reboot draws it
 * before the user storage is unlocked.
 *
 * A failed fetch is retried at most once an hour for the same version; a 404 (no icon, or a
 * server that predates the endpoint) counts as fetched, so nothing hammers the server.
 */
final class VenueIcon {
    private static final String TAG = "VenueIcon";
    private static final String PREFS = "mdm_venue";
    private static final String K_VENUE = "venue";
    private static final String K_WANT = "want_v";   // version the server says is current
    private static final String K_HAVE = "have_v";   // version on disk (or settled as none)
    private static final String K_TRIED = "tried_v";  // version last tried …
    private static final String K_TRIED_AT = "tried_at"; // … and when
    private static final String FILE = "venue_icon.png";
    private static final long RETRY_MS = 60 * 60_000L;

    private static final AtomicBoolean running = new AtomicBoolean(false);

    private VenueIcon() {}

    private static Context dp(Context ctx) { return ctx.createDeviceProtectedStorageContext(); }

    private static SharedPreferences prefs(Context ctx) {
        return dp(ctx).getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Takes venue/venue_icon_v from a self_lock config or a lost_mode payload, if present. */
    static void note(Context ctx, JSONObject o) {
        if (o == null || !o.has("venue")) return; // a server before 1.13.4's data says nothing
        prefs(ctx).edit()
                .putString(K_VENUE, o.optString("venue", "").trim())
                .putLong(K_WANT, Math.max(0, o.optLong("venue_icon_v", 0)))
                .apply();
    }

    /** The venue's name: the server's, else the guest info's, else "". */
    static String venue(Context ctx) {
        String v = prefs(ctx).getString(K_VENUE, "");
        if (!v.isEmpty()) return v;
        try {
            return GuestInfo.load(ctx).restaurantName;
        } catch (Exception e) {
            return "";
        }
    }

    /** Version of the icon on disk, 0 when there is none. */
    static long iconVersion(Context ctx) {
        long have = prefs(ctx).getLong(K_HAVE, 0);
        return have > 0 && iconFile(ctx).isFile() ? have : 0;
    }

    /** Where the cached icon lives (device-protected storage); may not exist. */
    static File iconFile(Context ctx) {
        return new File(dp(ctx).getFilesDir(), FILE);
    }

    /** The cached icon, or null. */
    static Bitmap icon(Context ctx) {
        if (prefs(ctx).getLong(K_HAVE, 0) <= 0) return null;
        File f = new File(dp(ctx).getFilesDir(), FILE);
        if (!f.isFile()) return null;
        try {
            return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Brings the cached icon in line with the wanted version. Blocking network: call it off
     * the main thread. At most one run at a time.
     */
    static void sync(Context ctx, MdmApiService api, String serial) {
        if (api == null || serial == null || serial.isEmpty()) return;
        if (!running.compareAndSet(false, true)) return;
        try {
            SharedPreferences p = prefs(ctx);
            long want = p.getLong(K_WANT, 0), have = p.getLong(K_HAVE, 0);
            File f = new File(dp(ctx).getFilesDir(), FILE);
            if (want == have) return;
            if (want <= 0) { // the venue has no icon (any more)
                f.delete();
                p.edit().putLong(K_HAVE, 0).apply();
                changed(ctx);
                return;
            }
            long now = System.currentTimeMillis();
            if (p.getLong(K_TRIED, 0) == want && now - p.getLong(K_TRIED_AT, 0) < RETRY_MS) return;
            p.edit().putLong(K_TRIED, want).putLong(K_TRIED_AT, now).apply();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int code = api.fetchBytes("/api/v1/venue-icon?serial=" + URLEncoder.encode(serial, "UTF-8"), out);
            if (code == 200) {
                byte[] b = out.toByteArray();
                if (BitmapFactory.decodeByteArray(b, 0, b.length) == null) {
                    Log.w(TAG, "venue icon is not an image; keeping none");
                    return;
                }
                File tmp = new File(dp(ctx).getFilesDir(), FILE + ".tmp");
                try (FileOutputStream fo = new FileOutputStream(tmp)) {
                    fo.write(b);
                }
                if (!tmp.renameTo(f)) return;
                p.edit().putLong(K_HAVE, want).apply();
                Log.i(TAG, "venue icon v" + want + " cached (" + b.length + " bytes)");
                changed(ctx);
            } else if (code == 404) {
                // No icon, or a server without the endpoint: settled, no retries.
                f.delete();
                p.edit().putLong(K_HAVE, want).apply();
                changed(ctx);
            } else {
                Log.w(TAG, "venue icon fetch: HTTP " + code + "; next try in an hour");
            }
        } catch (Exception e) {
            Log.w(TAG, "venue icon sync: " + e.getMessage());
        } finally {
            running.set(false);
        }
    }

    /** Tells the welcome screens (lock screen, Home card) and an open lost screen. */
    private static void changed(Context ctx) {
        try {
            ctx.getContentResolver().notifyChange(StatusProvider.ROOT_URI, null);
        } catch (Exception ignored) {
        }
        GuestWelcomeWidget.updateAll(ctx);
        if (!LostMode.isOn(ctx)) return;
        try {
            ctx.sendBroadcast(new Intent(LostMode.ACTION_CHANGED).setPackage(ctx.getPackageName()));
        } catch (Exception ignored) {
        }
    }
}
