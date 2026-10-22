package com.aioapp.mdm;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A wiped tablet finds its own way back (1.15.1, retries 1.15.3). The 2.0.x → 2.1.x move needs a wiping OTA,
 * and a wiped T7 used to sit on Google's setup screen with no Wi-Fi: unreachable from the MDM
 * until somebody at the venue tapped through setup.
 *
 * Both are driven by a firmware file, {@value #FIRST_BOOT_FILE}:
 * {"auto_setup": true, "wifi": [{ssid, security, password, hidden}]}. A file rather than a
 * property because a new ro.* property is default_prop, which system_app may not read.
 *
 * <ul>
 * <li>Wi-Fi: on a device with no saved network, the file's networks are saved once. Their
 *     passwords live in the private firmware tree, never in this repo. The venue's own
 *     networks come back afterwards from the server's venue Wi-Fi (wifi_config).</li>
 * <li>Setup: after the first check-in the server answers, the device is marked provisioned and
 *     set up and Google's setup wizard is turned off, so it goes to the launcher. Only when the
 *     file says auto_setup, and only once the MDM can reach it: a device that never gets
 *     online stays on setup, where staff can finish it by hand.</li>
 * </ul>
 * The service runs before setup and before first unlock, so remote view and commands work on
 * the setup screen too.
 */
final class AutoSetup {
    private static final String TAG = "AutoSetup";
    static final String FIRST_BOOT_FILE = "/product/etc/aio/first_boot.json";
    private static final String PREFS = "mdm_auto_setup";
    private static final String K_WIFI_DONE = "bootstrap_wifi_done";
    // Setup wizards a T7 can carry: Google's on GMS builds, AOSP Provision otherwise.
    private static final String[] WIZARDS = {
            "com.google.android.setupwizard", "com.android.provision"};

    private AutoSetup() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Saves the bootstrap networks once per wipe, and only on a device with none saved. Runs on
     * every service start, on user unlock and on every Wi-Fi state change (MdmService): on a
     * wiped, offline device service starts are minutes apart, so those alone left it on setup
     * with no network (031, 22 Oct). Every way out is logged, and nothing it throws is lost in
     * the executor's Future.
     */
    static void bootstrapWifi(Context ctx) {
        try {
            bootstrapWifiOnce(ctx);
        } catch (Throwable t) {
            Log.e(TAG, "bootstrap Wi-Fi failed", t);
        }
    }

    private static void bootstrapWifiOnce(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (p.getBoolean(K_WIFI_DONE, false)) return;
        // Before unlock Wi-Fi has not loaded its saved networks yet: "none saved" would be a guess.
        UserManager um = ctx.getSystemService(UserManager.class);
        if (um == null || !um.isUserUnlocked()) {
            Log.i(TAG, "bootstrap Wi-Fi waits for unlock");
            return;
        }
        JSONObject cfg = config();
        JSONArray list = cfg == null ? null : cfg.optJSONArray("wifi");
        if (list == null || list.length() == 0) return; // firmware without a list
        WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        if (wm == null) {
            Log.w(TAG, "bootstrap Wi-Fi: no WifiManager");
            return;
        }
        if (!wm.isWifiEnabled()) {
            // Wi-Fi comes up asynchronously; WIFI_STATE_CHANGED brings us back here.
            Log.i(TAG, "Wi-Fi off on a device with a bootstrap list: turning it on");
            wm.setWifiEnabled(true);
            return;
        }
        if (!WifiConfig.noneSaved(ctx)) {
            // Not wiped (or set up by hand): its own networks win, the list is never merged in.
            Log.i(TAG, "bootstrap Wi-Fi skipped: the device already has saved networks");
            p.edit().putBoolean(K_WIFI_DONE, true).apply();
            return;
        }
        int added = 0;
        for (int i = 0; i < list.length(); i++) {
            JSONObject n = list.optJSONObject(i);
            if (n == null) continue;
            String err = WifiConfig.addBootstrap(ctx, n);
            if (err == null) added++;
            else Log.w(TAG, "bootstrap network " + n.optString("ssid") + ": " + err);
        }
        Log.i(TAG, "saved " + added + " of " + list.length() + " bootstrap networks");
        if (added > 0) p.edit().putBoolean(K_WIFI_DONE, true).apply();
    }

    /** True when setup still has to be finished on this device. */
    static boolean pending(Context ctx) {
        return Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.DEVICE_PROVISIONED, 0) == 0
                || Settings.Secure.getInt(ctx.getContentResolver(), Settings.Secure.USER_SETUP_COMPLETE, 0) == 0;
    }

    /** Called after a check-in the server answered: finishes setup where the firmware allows it. */
    static void maybeFinish(Context ctx) {
        if (!pending(ctx)) return;
        JSONObject cfg = config();
        if (cfg == null || !cfg.optBoolean("auto_setup", false)) return;
        finish(ctx);
    }

    /** Marks the device set up, turns the setup wizards off and goes to the launcher. */
    static void finish(Context ctx) {
        Log.i(TAG, "finishing setup: the MDM reaches this device");
        Settings.Global.putInt(ctx.getContentResolver(), Settings.Global.DEVICE_PROVISIONED, 1);
        Settings.Secure.putInt(ctx.getContentResolver(), Settings.Secure.USER_SETUP_COMPLETE, 1);
        PackageManager pm = ctx.getPackageManager();
        for (String pkg : WIZARDS) {
            try {
                pm.getPackageInfo(pkg, 0);
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            try {
                // Disabling stops the running wizard too; its status bar lock goes with its process.
                pm.setApplicationEnabledSetting(pkg, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0);
                Log.i(TAG, "turned off " + pkg);
            } catch (Exception e) {
                Log.e(TAG, "could not turn off " + pkg + ": " + e.getMessage());
            }
        }
        try {
            ctx.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Log.e(TAG, "could not start home: " + e.getMessage());
        }
    }

    /** The firmware's first-boot file, or null when this firmware has none. */
    private static JSONObject config() {
        File f = new File(FIRST_BOOT_FILE);
        if (!f.canRead()) return null;
        try {
            return new JSONObject(read(f));
        } catch (Exception e) {
            Log.e(TAG, FIRST_BOOT_FILE + " unreadable: " + e.getMessage());
            return null;
        }
    }

    private static String read(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
