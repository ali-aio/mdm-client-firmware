package com.aioapp.mdm;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The {@code wifi_config} command: add or update a saved Wi-Fi network, or forget one, without
 * ever dropping the network the device is on. Runs as the system uid, which the Q+ addNetwork
 * restriction exempts (WifiServiceImpl.isTargetSdkLessThanQOrPrivileged → isSystem).
 *
 * The case that matters most is a venue changing its router password: the new password has to
 * reach the device while it is still online on the old one, but writing it into the network
 * the device is connected to would disconnect it on the spot — and it could not reconnect,
 * because the router still has the old password. So a change to the CURRENT network is kept
 * as pending and applied only once the device has lost that network (disconnected for a
 * minute while the SSID is still in range — the router has changed). If the new credentials
 * do not connect within two minutes the old ones go back, and it tries again ten minutes
 * later. Every other add/update/remove is applied at once.
 *
 * Passwords are never reported: {@link #networks} lists SSID, security and state only.
 */
final class WifiConfig {
    private static final String TAG = "WifiConfig";
    private static final String PREFS = "mdm_wifi_pending";
    private static final String K_PENDING = "pending";  // JSON: ssid, security, password, hidden
    private static final String K_STATE = "state";      // "waiting" | "trying"
    private static final String K_TRY_AT = "try_at";    // elapsedRealtime of the switch
    private static final String K_FAIL_AT = "fail_at";  // elapsedRealtime of the last revert
    private static final String K_BACKUP = "backup";    // JSON: security, password of the old config

    private static final long OFFLINE_BEFORE_SWITCH_MS = 60_000L;
    private static final long TRY_MS = 120_000L;
    private static final long RETRY_AFTER_FAIL_MS = 10 * 60_000L;

    private static long disconnectedSinceMs; // elapsedRealtime; 0 while connected

    private WifiConfig() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Runs a wifi_config payload. Returns null on success, else the error for the ack. */
    static String apply(Context ctx, JSONObject p) {
        WifiManager wm = wifi(ctx);
        if (wm == null) return "no wifi";
        String action = p.optString("action", "");
        String ssid = p.optString("ssid", "");
        int len = ssid.getBytes(StandardCharsets.UTF_8).length;
        if (len < 1 || len > 32) return "invalid ssid";
        switch (action) {
            case "add":
                return add(ctx, wm, ssid, p.optString("security", "wpa2"),
                        p.optString("password", ""), p.optBoolean("hidden", false));
            case "remove":
                return remove(ctx, wm, ssid);
            default:
                return "unknown action: " + action;
        }
    }

    private static String add(Context ctx, WifiManager wm, String ssid, String security,
                              String password, boolean hidden) {
        if (!"open".equals(security) && !"wpa2".equals(security) && !"wpa3".equals(security)) {
            return "invalid security: " + security;
        }
        if (!"open".equals(security) && (password.length() < 8 || password.length() > 63)) {
            return "password must be 8–63 characters";
        }
        // A WPA3 network saved on a radio without SAE could never connect: refuse it instead.
        if ("wpa3".equals(security) && !wm.isWpa3SaeSupported()) {
            return "wpa3 not supported on this device";
        }
        WifiConfiguration existing = find(wm, ssid);
        if (existing != null && ssid.equals(connectedSsid(wm))) {
            if (sameCredentials(wm, existing, security, password)) {
                clearPending(ctx, ssid);
                return null; // nothing to change
            }
            // The network the device is on: keep it for when that network is gone (see top).
            try {
                JSONObject pend = new JSONObject().put("ssid", ssid).put("security", security)
                        .put("password", password).put("hidden", hidden);
                prefs(ctx).edit().putString(K_PENDING, pend.toString()).putString(K_STATE, "waiting")
                        .putLong(K_FAIL_AT, 0).remove(K_BACKUP).commit();
            } catch (JSONException e) {
                return "pending: " + e.getMessage();
            }
            Log.i(TAG, "change to the connected network " + ssid + " kept as pending");
            return null;
        }
        WifiConfiguration c = build(ssid, security, password, hidden);
        int id;
        if (existing != null) {
            c.networkId = existing.networkId;
            id = wm.updateNetwork(c);
        } else {
            id = wm.addNetwork(c);
        }
        if (id < 0) return "the system refused the network";
        wm.enableNetwork(id, false); // allowed to join; do not leave the current network for it
        clearPending(ctx, ssid);
        Log.i(TAG, (existing != null ? "updated " : "added ") + ssid + " (" + security + ")");
        return null;
    }

    private static String remove(Context ctx, WifiManager wm, String ssid) {
        if (ssid.equals(connectedSsid(wm))) return "connected";
        List<WifiConfiguration> list = wm.getConfiguredNetworks();
        if (list != null) {
            for (WifiConfiguration c : list) {
                if (ssid.equals(unquote(c.SSID)) && !wm.removeNetwork(c.networkId)) {
                    return "the system refused to forget it";
                }
            }
        }
        clearPending(ctx, ssid);
        Log.i(TAG, "forgot " + ssid);
        return null;
    }

    /**
     * Applies or rolls back a pending change to the network the device was on. Called once a
     * minute from the Wi-Fi scan loop.
     */
    static void tick(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        String raw = sp.getString(K_PENDING, null);
        WifiManager wm = wifi(ctx);
        if (wm == null) return;
        String connected = connectedSsid(wm);
        long now = SystemClock.elapsedRealtime();
        if (connected == null) {
            if (disconnectedSinceMs == 0) disconnectedSinceMs = now;
        } else {
            disconnectedSinceMs = 0;
        }
        if (raw == null) return;
        try {
            JSONObject pend = new JSONObject(raw);
            String ssid = pend.getString("ssid");
            if ("trying".equals(sp.getString(K_STATE, ""))) {
                if (ssid.equals(connected)) {
                    Log.i(TAG, "pending change to " + ssid + " connected — done");
                    sp.edit().clear().commit();
                } else if (now - sp.getLong(K_TRY_AT, 0) > TRY_MS) {
                    JSONObject b = new JSONObject(sp.getString(K_BACKUP, "{}"));
                    String bSec = b.optString("security", "");
                    // No readable backup: leave the new credentials in place rather than
                    // writing an empty key over them.
                    if ("open".equals(bSec) || !b.optString("password", "").isEmpty()) {
                        writeNetwork(wm, ssid, bSec, b.optString("password", ""),
                                pend.optBoolean("hidden", false), false);
                    }
                    sp.edit().putString(K_STATE, "waiting").putLong(K_FAIL_AT, now).remove(K_BACKUP).commit();
                    Log.w(TAG, "pending change to " + ssid + " did not connect — old credentials back");
                }
                return;
            }
            boolean lostIt = connected == null && disconnectedSinceMs > 0
                    && now - disconnectedSinceMs >= OFFLINE_BEFORE_SWITCH_MS;
            long failAt = sp.getLong(K_FAIL_AT, 0);
            if (!lostIt || !inRange(wm, ssid) || (failAt > 0 && now - failAt < RETRY_AFTER_FAIL_MS)) return;
            WifiConfiguration old = privileged(wm, ssid);
            JSONObject backup = new JSONObject();
            if (old != null) {
                backup.put("security", securityOf(old)).put("password", unquote(old.preSharedKey));
            }
            sp.edit().putString(K_BACKUP, backup.toString()).putString(K_STATE, "trying")
                    .putLong(K_TRY_AT, now).commit();
            writeNetwork(wm, ssid, pend.getString("security"), pend.optString("password", ""),
                    pend.optBoolean("hidden", false), true);
            Log.i(TAG, ssid + " lost for a minute while in range — trying its pending credentials");
        } catch (Exception e) {
            Log.e(TAG, "tick: " + e.getMessage());
        }
    }

    /** extra.wifi_networks: saved networks, no passwords. Null when Wi-Fi can't be read. */
    static JSONArray networks(Context ctx) {
        WifiManager wm = wifi(ctx);
        if (wm == null) return null;
        try {
            List<WifiConfiguration> list = wm.getConfiguredNetworks();
            if (list == null) return null;
            String connected = connectedSsid(wm);
            String pendingSsid = null;
            String raw = prefs(ctx).getString(K_PENDING, null);
            if (raw != null) pendingSsid = new JSONObject(raw).optString("ssid", null);
            JSONArray out = new JSONArray();
            for (WifiConfiguration c : list) {
                String ssid = unquote(c.SSID);
                if (ssid == null || ssid.isEmpty()) continue;
                JSONObject o = new JSONObject();
                o.put("ssid", ssid);
                o.put("security", securityOf(c));
                o.put("connected", ssid.equals(connected));
                if (ssid.equals(pendingSsid)) o.put("pending", true);
                out.put(o);
            }
            return out;
        } catch (Exception e) {
            Log.w(TAG, "networks: " + e.getMessage());
            return null;
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static void writeNetwork(WifiManager wm, String ssid, String security, String password,
                                     boolean hidden, boolean connect) {
        WifiConfiguration existing = find(wm, ssid);
        WifiConfiguration c = build(ssid, security, password, hidden);
        int id;
        if (existing != null) {
            c.networkId = existing.networkId;
            id = wm.updateNetwork(c);
        } else {
            id = wm.addNetwork(c);
        }
        if (id >= 0) wm.enableNetwork(id, connect);
    }

    private static WifiConfiguration build(String ssid, String security, String password, boolean hidden) {
        WifiConfiguration c = new WifiConfiguration();
        c.SSID = "\"" + ssid + "\"";
        c.hiddenSSID = hidden;
        switch (security) {
            case "open":
                c.setSecurityParams(WifiConfiguration.SECURITY_TYPE_OPEN);
                break;
            case "wpa3":
                c.setSecurityParams(WifiConfiguration.SECURITY_TYPE_SAE);
                c.preSharedKey = "\"" + password + "\"";
                break;
            default:
                c.setSecurityParams(WifiConfiguration.SECURITY_TYPE_PSK);
                c.preSharedKey = "\"" + password + "\"";
        }
        return c;
    }

    private static boolean sameCredentials(WifiManager wm, WifiConfiguration existing,
                                           String security, String password) {
        if (!security.equals(securityOf(existing))) return false;
        if ("open".equals(security)) return true;
        WifiConfiguration full = privileged(wm, unquote(existing.SSID));
        return full != null && password.equals(unquote(full.preSharedKey));
    }

    /** The saved config with its key, which only a privileged caller can read. */
    private static WifiConfiguration privileged(WifiManager wm, String ssid) {
        try {
            List<WifiConfiguration> list = wm.getPrivilegedConfiguredNetworks();
            if (list == null) return null;
            for (WifiConfiguration c : list) {
                if (ssid.equals(unquote(c.SSID))) return c;
            }
        } catch (Exception e) {
            Log.w(TAG, "privileged read: " + e.getMessage());
        }
        return null;
    }

    private static WifiConfiguration find(WifiManager wm, String ssid) {
        List<WifiConfiguration> list = wm.getConfiguredNetworks();
        if (list == null) return null;
        for (WifiConfiguration c : list) {
            if (ssid.equals(unquote(c.SSID))) return c;
        }
        return null;
    }

    private static String securityOf(WifiConfiguration c) {
        if (c.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SAE)) return "wpa3";
        if (c.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)) return "wpa2";
        if (c.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE)
                && (c.wepKeys == null || c.wepKeys[0] == null)) return "open";
        return "other";
    }

    /** SSID of the network the device is connected to, or null. */
    private static String connectedSsid(WifiManager wm) {
        try {
            WifiInfo info = wm.getConnectionInfo();
            if (info == null || info.getNetworkId() == -1) return null;
            String s = unquote(info.getSSID());
            return s == null || s.isEmpty() || WifiManager.UNKNOWN_SSID.equals(info.getSSID()) ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean inRange(WifiManager wm, String ssid) {
        try {
            List<ScanResult> results = wm.getScanResults();
            if (results == null) return false;
            for (ScanResult r : results) {
                if (ssid.equals(r.SSID)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static void clearPending(Context ctx, String ssid) {
        SharedPreferences sp = prefs(ctx);
        String raw = sp.getString(K_PENDING, null);
        if (raw == null) return;
        try {
            if (ssid.equals(new JSONObject(raw).optString("ssid"))) sp.edit().clear().commit();
        } catch (JSONException e) {
            sp.edit().clear().commit();
        }
    }

    private static WifiManager wifi(Context ctx) {
        return (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    private static String unquote(String s) {
        if (s == null) return null;
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        return s;
    }
}
