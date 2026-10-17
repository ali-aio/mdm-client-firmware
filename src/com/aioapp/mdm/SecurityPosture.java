package com.aioapp.mdm;

import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Security posture flags for the check-in, the same keys the DPC agent and MDM-lite send,
 * so the server's compliance rules read one vocabulary across the whole fleet:
 *
 *   adb_enabled, adb_tcp, dev_options_enabled, unknown_sources, unknown_source_apps,
 *   su_present, build_type, build_tags, accessibility_services, play_protect,
 *   security_patch, verified_boot, selinux_enforcing, storage_encrypted
 *
 * A value the client cannot read goes as null, never as a guess: the server's checks count
 * null as "unknown", which is neither pass nor fail.
 *
 * All reads are cheap except the per-package app-op scan, which is cached.
 */
final class SecurityPosture {
    private static final String TAG = "SecurityPosture";

    // Where su binaries are dropped by rooted images and root kits.
    private static final String[] SU_PATHS = {
            "/system/xbin/su", "/system/bin/su", "/sbin/su", "/system/sbin/su",
            "/vendor/bin/su", "/su/bin/su", "/debug_ramdisk/su", "/data/local/xbin/su",
            "/data/local/bin/su",
    };
    private static final long APP_SCAN_TTL_MS = 5 * 60_000L;
    // AppOpsManager.OPSTR_REQUEST_INSTALL_PACKAGES, which the public SDK hides.
    private static final String OP_REQUEST_INSTALL_PACKAGES = "android:request_install_packages";

    private static JSONArray cachedInstallers;
    private static long installersAtMs;

    private SecurityPosture() {}

    static void put(Context ctx, JSONObject extra) throws JSONException {
        extra.put("adb_enabled", globalInt(ctx, Settings.Global.ADB_ENABLED) == 1);
        // adb over the network: the port adbd listens on besides USB (0 or unset = none).
        String port = SystemPropertiesProxy.get("service.adb.tcp.port", "");
        if (port.isEmpty()) port = SystemPropertiesProxy.get("persist.adb.tcp.port", "");
        extra.put("adb_tcp", !port.isEmpty() && !"0".equals(port) && !"-1".equals(port));
        extra.put("dev_options_enabled",
                globalInt(ctx, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) == 1);
        // The pre-Oreo global switch; vendor first-boot scripts still set it.
        @SuppressWarnings("deprecation")
        int legacyUnknown = secureInt(ctx, Settings.Secure.INSTALL_NON_MARKET_APPS);
        JSONArray installers = unknownSourceApps(ctx);
        extra.put("unknown_sources", legacyUnknown == 1 || installers.length() > 0);
        extra.put("unknown_source_apps", installers);
        extra.put("su_present", suPresent());
        extra.put("build_type", Build.TYPE);
        extra.put("build_tags", Build.TAGS);
        extra.put("accessibility_services", accessibilityServices(ctx));
        // Google's app verification (Play Protect); vendor scripts turn it off.
        extra.put("play_protect", globalInt(ctx, "package_verifier_enable", 1) != 0);
        // Compliance (1.12.0): the patch level, the bootloader's verdict on this image,
        // SELinux mode and storage encryption.
        String patch = Build.VERSION.SECURITY_PATCH;
        extra.put("security_patch", patch == null || patch.isEmpty() ? JSONObject.NULL : patch);
        String vb = SystemPropertiesProxy.get("ro.boot.verifiedbootstate", "");
        extra.put("verified_boot", vb.isEmpty() ? JSONObject.NULL : vb);
        extra.put("selinux_enforcing", selinuxEnforcing());
        extra.put("storage_encrypted", storageEncrypted(ctx));
    }

    /**
     * SELinux enforcing. Not SELinux.isSELinuxEnforced(): it reads /sys/fs/selinux/enforce and
     * answers false when that read is refused, which is what a system_app gets on our images
     * (1.12.0 reported "not enforcing" from every T7 that way). The file is world-readable
     * (0644), so a refusal can only come from SELinux itself — and SELinux refuses only when
     * it is enforcing; a permissive kernel logs the denial and lets the read through.
     */
    static Object selinuxEnforcing() {
        try {
            if (!android.os.SELinux.isSELinuxEnabled()) return false;
        } catch (Throwable t) {
            return JSONObject.NULL;
        }
        try (java.io.FileInputStream in = new java.io.FileInputStream("/sys/fs/selinux/enforce")) {
            int c = in.read();
            if (c == '1') return true;
            if (c == '0') return false;
            return JSONObject.NULL;
        } catch (java.io.FileNotFoundException e) {
            // FileInputStream reports EACCES as FileNotFoundException("… (Permission denied)").
            String m = e.getMessage();
            return m != null && m.contains("Permission denied") ? (Object) true : JSONObject.NULL;
        } catch (Throwable t) {
            return JSONObject.NULL;
        }
    }

    /**
     * Storage encrypted. ACTIVE_DEFAULT_KEY counts: the data is encrypted, just not yet with a
     * key derived from a user credential, which a kiosk never has. ro.crypto.state backs it up
     * where the policy service cannot be asked.
     */
    static Object storageEncrypted(Context ctx) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm != null) {
                switch (dpm.getStorageEncryptionStatus()) {
                    case DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE:
                    case DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER:
                    case DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY:
                        return true;
                    case DevicePolicyManager.ENCRYPTION_STATUS_INACTIVE:
                    case DevicePolicyManager.ENCRYPTION_STATUS_UNSUPPORTED:
                        return false;
                    default:
                        break;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "encryption status: " + t.getMessage());
        }
        String state = SystemPropertiesProxy.get("ro.crypto.state", "");
        if ("encrypted".equals(state)) return true;
        if ("unencrypted".equals(state)) return false;
        return JSONObject.NULL;
    }

    static boolean suPresent() {
        for (String p : SU_PATHS) {
            if (new File(p).exists()) return true;
        }
        return false;
    }

    static JSONArray accessibilityServices(Context ctx) {
        String v = Settings.Secure.getString(ctx.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        List<String> out = new ArrayList<>();
        if (v != null) {
            for (String s : v.split(":")) {
                if (!s.trim().isEmpty()) out.add(s.trim());
            }
        }
        Collections.sort(out); // stable order, so an unchanged set compares equal
        return new JSONArray(out);
    }

    /** Packages the user has allowed to install unknown apps (REQUEST_INSTALL_PACKAGES). */
    private static synchronized JSONArray unknownSourceApps(Context ctx) {
        long now = SystemClock.elapsedRealtime();
        if (cachedInstallers != null && now - installersAtMs < APP_SCAN_TTL_MS) return cachedInstallers;
        List<String> out = new ArrayList<>();
        try {
            PackageManager pm = ctx.getPackageManager();
            AppOpsManager ops = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            for (PackageInfo pi : pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
                if (pi.requestedPermissions == null || pi.applicationInfo == null) continue;
                boolean asks = false;
                for (String perm : pi.requestedPermissions) {
                    if ("android.permission.REQUEST_INSTALL_PACKAGES".equals(perm)) { asks = true; break; }
                }
                if (!asks) continue;
                int mode = ops.unsafeCheckOpNoThrow(OP_REQUEST_INSTALL_PACKAGES,
                        pi.applicationInfo.uid, pi.packageName);
                if (mode == AppOpsManager.MODE_ALLOWED) out.add(pi.packageName);
            }
        } catch (Throwable t) {
            Log.w(TAG, "unknown-sources scan failed: " + t.getMessage());
        }
        Collections.sort(out);
        cachedInstallers = new JSONArray(out);
        installersAtMs = now;
        return cachedInstallers;
    }

    private static int globalInt(Context ctx, String key) { return globalInt(ctx, key, 0); }

    private static int globalInt(Context ctx, String key, int def) {
        return Settings.Global.getInt(ctx.getContentResolver(), key, def);
    }

    private static int secureInt(Context ctx, String key) {
        return Settings.Secure.getInt(ctx.getContentResolver(), key, 0);
    }
}
