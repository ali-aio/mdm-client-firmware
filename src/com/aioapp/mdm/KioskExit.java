package com.aioapp.mdm;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import org.json.JSONObject;

/**
 * State for the OFFLINE kiosk-exit feature: the exit PIN, the
 * local "suspended" flag (set when a technician has exited kiosk without the server),
 * brute-force rate limiting, and a pending "I was exited offline" event to report on
 * the next check-in.
 *
 * All state lives in DEVICE-PROTECTED storage so it is readable during Direct Boot
 * (BootReceiver runs on LOCKED_BOOT_COMPLETED before the user unlocks), matching how
 * {@link KioskManager} stores the kiosk config.
 */
public final class KioskExit {
    private static final String TAG = "KioskExit";
    private static final String PREFS = "mdm_kiosk_exit";

    private static final String K_ENABLED   = "enabled";
    // The rotating (TOTP) code was removed in 1.12.1; its old keys are cleared on the next policy save.
    private static final String K_SEED      = "seed";
    private static final String K_DIGITS    = "digits";
    private static final String K_PERIOD    = "period";
    private static final String K_RELOCK    = "relock";     // "reboot" (P1)
    private static final String K_SUSPENDED = "suspended";  // local exit currently active
    private static final String K_FAILS     = "fail_count";
    private static final String K_LOCKOUT   = "lockout_until_ms";
    private static final String K_EVENT_AT  = "exit_event_at"; // epoch secs, 0 = none pending
    private static final String K_PIN       = "pin";        // static exit PIN (server-pushed)

    /**
     * Static exit PIN used until the server pushes one in offline_exit.pin; this PIN is what
     * the power-press exit prompt accepts. (The rotating code it used to accept as well was
     * removed in 1.12.1.)
     */
    private static final String DEFAULT_PIN = "147147";

    private static final int MAX_FAILS = 5;
    private static final long LOCKOUT_MS = 60_000L; // 60s cool-off after MAX_FAILS

    private KioskExit() {}

    private static SharedPreferences prefs(Context ctx) {
        Context de = ctx.createDeviceProtectedStorageContext();
        de.moveSharedPreferencesFrom(ctx, PREFS); // one-time CE->DE migration; no-op afterward
        return de.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Ingest the server's offline_exit config block: a server-pushed PIN, when there is one.
     * A seed an older server still sends is ignored, and one stored by an earlier client is
     * dropped — the rotating code no longer unlocks anything.
     */
    public static void savePolicy(Context ctx, JSONObject offline) {
        if (offline == null) return;
        SharedPreferences.Editor e = prefs(ctx).edit();
        e.remove(K_SEED).remove(K_DIGITS).remove(K_PERIOD);
        String pin = offline.optString("pin", "");
        if (!pin.isEmpty()) e.putString(K_PIN, pin);
        e.apply();
    }

    /** Offline exit is always usable: the PIN falls back to DEFAULT_PIN and needs no provisioning. */
    public static boolean isEnabled(Context ctx) {
        return !pin(ctx).isEmpty();
    }

    /** The server-pushed PIN, or the compiled-in default when none has been pushed. */
    private static String pin(Context ctx)    { return prefs(ctx).getString(K_PIN, DEFAULT_PIN); }

    // ── exited guard (device left kiosk locally; survives reboot until the server acks) ──
    public static boolean isSuspended(Context ctx) { return prefs(ctx).getBoolean(K_SUSPENDED, false); }
    public static void setSuspended(Context ctx, boolean v) { prefs(ctx).edit().putBoolean(K_SUSPENDED, v).apply(); }

    // ── rate limiting ──
    public static boolean isLockedOut(Context ctx) {
        long until = prefs(ctx).getLong(K_LOCKOUT, 0);
        return System.currentTimeMillis() < until;
    }

    public static long lockoutRemainingMs(Context ctx) {
        return Math.max(0, prefs(ctx).getLong(K_LOCKOUT, 0) - System.currentTimeMillis());
    }

    /** Verify an entered exit PIN; on success clears failures and returns true. Rate-limited. */
    public static boolean verify(Context ctx, String code) {
        if (isLockedOut(ctx)) return false;
        String expectedPin = pin(ctx);
        boolean ok = !expectedPin.isEmpty() && expectedPin.equals(code);
        SharedPreferences p = prefs(ctx);
        if (ok) {
            p.edit().putInt(K_FAILS, 0).putLong(K_LOCKOUT, 0).apply();
            return true;
        }
        int fails = p.getInt(K_FAILS, 0) + 1;
        SharedPreferences.Editor e = p.edit().putInt(K_FAILS, fails);
        if (fails >= MAX_FAILS) {
            e.putInt(K_FAILS, 0).putLong(K_LOCKOUT, System.currentTimeMillis() + LOCKOUT_MS);
        }
        e.apply();
        return false;
    }

    /** Record that the device was exited from kiosk offline (queues a check-in event). */
    public static void markExited(Context ctx) {
        setSuspended(ctx, true);
        prefs(ctx).edit().putLong(K_EVENT_AT, System.currentTimeMillis() / 1000L).apply();
    }

    /** Epoch seconds of a pending offline-exit event, or 0 if none. */
    public static long pendingEventAt(Context ctx) { return prefs(ctx).getLong(K_EVENT_AT, 0); }
    public static void clearPendingEvent(Context ctx) { prefs(ctx).edit().putLong(K_EVENT_AT, 0).apply(); }
}
