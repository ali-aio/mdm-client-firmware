package com.aioapp.mdm;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

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
 * Passwords are never reported in the clear: {@link #networks} lists SSID, security and state
 * only, and {@link #export} (wifi_config "export", 1.20.0) seals them to a key the server does
 * not hold.
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

    /**
     * RSA-3072 public key (X.509 DER, base64) that wifi_config "export" seals to. The private
     * half is kept off the MDM server (~/.config/aio-mdm/wifi-export.key on the firmware PC),
     * so the stored command output is unreadable there.
     */
    private static final String EXPORT_KEY = ""
            + "MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEAz5NpgBoFJJ+lNDG288GYOYp+4w0vQ8DpqvK9CfA7U2cn"
            + "YinXg0mDhjnMEfSaaHwlsz9dUFA5ETLDO6p9AQUWYtbz3oxVRrkCwoMS7z+6U6RAjfAim5bdv0niYUdZZRu134T6"
            + "fYpCRfItkmwSgeini5QaT6fGmxS9ASbgKUFtwG9VyyF5SHw17o14eTozBWzW/+AHGdUoqZbLzKoKBm1HE/d07k7n"
            + "abc3LawNbI6UyJoTAvRA0cp4AN8YWYrzg8Ldo6ElL4IkdKi27orj4bsDiSPN5z/rgvA5jKymRJkTJRFNFlAoNgiK"
            + "sn0jB0RwbdkMSyKUm8OCFeVts0eq9W6oHzgp4wbVT4XHODIuKagATkewSLvlyAjOWQm2H5T/TQsm4fzcP/uABwkg"
            + "agxsX1gpYUp8gUaOZ/za9MAPZ80xmILg4Utig0jZWxmKmfPX8SRYdKPls/aDmTIp9HylWaS84RptH7ulOt/jDoM6"
            + "4G45x7Ga07xBvYRsshub5mDV2/ghAgMBAAE=";

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
                        p.optString("password", ""), p.optBoolean("hidden", false), true);
            case "remove":
                return remove(ctx, wm, ssid);
            default:
                return "unknown action: " + action;
        }
    }

    /**
     * Saves one network from the firmware's bootstrap list (AutoSetup). Unlike wifi_config it is
     * not a venue network, so SelfLock does not learn it: a company SSID seen anywhere must not
     * count as "still at the venue". Returns null on success, else why not.
     */
    static String addBootstrap(Context ctx, JSONObject n) {
        WifiManager wm = wifi(ctx);
        if (wm == null) return "no wifi";
        String ssid = n.optString("ssid", "");
        int len = ssid.getBytes(StandardCharsets.UTF_8).length;
        if (len < 1 || len > 32) return "invalid ssid";
        return add(ctx, wm, ssid, n.optString("security", "wpa2"), n.optString("password", ""),
                n.optBoolean("hidden", false), false);
    }

    /** True when the device has no saved Wi-Fi network at all (a wiped device). */
    static boolean noneSaved(Context ctx) {
        WifiManager wm = wifi(ctx);
        if (wm == null) return false;
        List<WifiConfiguration> list = wm.getConfiguredNetworks();
        return list == null || list.isEmpty();
    }

    private static String add(Context ctx, WifiManager wm, String ssid, String security,
                              String password, boolean hidden, boolean venue) {
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
        if (venue) SelfLock.rememberNetwork(ctx, ssid); // its presence means "still here"
        Log.i(TAG, (existing != null ? "updated " : "added ") + ssid + " (" + security + ")");
        return null;
    }

    private static String remove(Context ctx, WifiManager wm, String ssid) {
        if (ssid.equals(connectedSsid(wm))) return "connected";
        // A WPA2/WPA3 transition network is listed twice (one config per security type) under
        // one networkId: remove each id once, and judge by whether the name is still saved —
        // the second removeNetwork of an id that is already gone answers false.
        List<WifiConfiguration> list = wm.getConfiguredNetworks();
        if (list != null) {
            java.util.Set<Integer> done = new java.util.HashSet<>();
            for (WifiConfiguration c : list) {
                if (ssid.equals(unquote(c.SSID)) && done.add(c.networkId)) {
                    wm.removeNetwork(c.networkId);
                }
            }
            List<WifiConfiguration> after = wm.getConfiguredNetworks();
            if (after != null) {
                for (WifiConfiguration c : after) {
                    if (ssid.equals(unquote(c.SSID))) return "the system refused to forget it";
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

    static final long SWITCH_MS = 90_000L;

    /**
     * Switch to a saved network, reversibly (1.18.0; device page Wi-Fi card, W1, 22 Oct
     * 2026). Joins {@code ssid}, and keeps it only if the MDM answers from there within
     * SWITCH_MS ({@code reach}); otherwise rejoins the network it was on. Blocks up to
     * SWITCH_MS plus the way back, so run it off the command thread. Returns the command
     * result: {ok, after_s} or {error: "switch_failed", reason, reverted_to}.
     */
    static JSONObject connect(Context ctx, String ssid, java.util.concurrent.Callable<Boolean> reach) throws JSONException {
        WifiManager wm = wifi(ctx);
        if (wm == null) return new JSONObject().put("error", "no wifi");
        WifiConfiguration target = find(wm, ssid);
        if (target == null) return new JSONObject().put("error", "switch_failed").put("reason", "unknown_network");
        String prevSsid = connectedSsid(wm);
        if (ssid.equals(prevSsid)) return new JSONObject().put("ok", true).put("after_s", 0);
        int prevId = -1;
        try { WifiInfo info = wm.getConnectionInfo(); if (info != null) prevId = info.getNetworkId(); } catch (Exception ignored) {}
        final boolean[] authFailed = {false};
        android.content.BroadcastReceiver rx = new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, android.content.Intent i) {
                if (i.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, -1) == WifiManager.ERROR_AUTHENTICATING) authFailed[0] = true;
            }
        };
        ctx.getApplicationContext().registerReceiver(rx, new android.content.IntentFilter(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION));
        boolean seen = inRange(wm, ssid), joined = false;
        long start = SystemClock.elapsedRealtime();
        try {
            Log.i(TAG, "switching " + prevSsid + " -> " + ssid);
            join(wm, target.networkId);
            while (SystemClock.elapsedRealtime() - start < SWITCH_MS && !authFailed[0]) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                if (ssid.equals(connectedSsid(wm))) {
                    joined = true;
                    boolean ok = false;
                    try { ok = Boolean.TRUE.equals(reach.call()); } catch (Exception ignored) {}
                    if (ok) {
                        Log.i(TAG, "on " + ssid + " and the MDM answers — keeping it");
                        return new JSONObject().put("ok", true).put("ssid", ssid)
                                .put("after_s", (SystemClock.elapsedRealtime() - start) / 1000);
                    }
                }
            }
        } finally {
            try { ctx.getApplicationContext().unregisterReceiver(rx); } catch (Exception ignored) {}
        }
        String reason = authFailed[0] ? "auth_failed" : joined ? "no_internet" : (!seen && !inRange(wm, ssid)) ? "not_in_range" : "timeout";
        Log.w(TAG, "switch to " + ssid + " failed (" + reason + ") — back to " + prevSsid);
        if (prevId != -1) join(wm, prevId);
        return new JSONObject().put("error", "switch_failed").put("reason", reason).put("ssid", ssid)
                .put("reverted_to", prevSsid == null ? "" : prevSsid);
    }

    /** Joins a saved network: the system's connect (the settings app's path), else enableNetwork. */
    private static void join(WifiManager wm, int netId) {
        try {
            wm.connect(netId, null);
        } catch (Throwable t) {
            Log.w(TAG, "connect(" + netId + "): " + t + " — enableNetwork instead");
            wm.enableNetwork(netId, true);
            wm.reconnect();
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

    /**
     * wifi_config "export": every saved network with its password, for the firmware's bootstrap
     * list (first_boot.json). The JSON is sealed with a fresh AES-256-GCM key, which is wrapped
     * with {@link #EXPORT_KEY} (RSA-OAEP, SHA-256/MGF1-SHA-256). Only the count is in the clear.
     * A key that is not a quoted passphrase (a 64-hex PSK) is sent as "psk".
     */
    static JSONObject export(Context ctx, String serial) throws Exception {
        WifiManager wm = wifi(ctx);
        if (wm == null) throw new IllegalStateException("no wifi");
        List<WifiConfiguration> list = wm.getPrivilegedConfiguredNetworks();
        if (list == null) throw new IllegalStateException("networks unreadable");
        String connected = connectedSsid(wm);
        JSONArray nets = new JSONArray();
        for (WifiConfiguration c : list) {
            String ssid = unquote(c.SSID);
            if (ssid == null || ssid.isEmpty()) continue;
            JSONObject o = new JSONObject();
            o.put("ssid", ssid);
            o.put("security", securityOf(c));
            o.put("hidden", c.hiddenSSID);
            o.put("connected", ssid.equals(connected));
            String k = c.preSharedKey;
            if (k != null && !k.isEmpty() && !"*".equals(k)) {
                if (k.length() >= 2 && k.startsWith("\"") && k.endsWith("\"")) o.put("password", unquote(k));
                else o.put("psk", k);
            }
            nets.put(o);
        }
        JSONObject plain = new JSONObject()
                .put("serial", serial)
                .put("at", System.currentTimeMillis())
                .put("networks", nets);

        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256);
        SecretKey aes = kg.generateKey();
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.ENCRYPT_MODE, aes, new GCMParameterSpec(128, iv));
        byte[] data = gcm.doFinal(plain.toString().getBytes(StandardCharsets.UTF_8));

        PublicKey pub = KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(Base64.decode(EXPORT_KEY, Base64.NO_WRAP)));
        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
        rsa.init(Cipher.ENCRYPT_MODE, pub, new OAEPParameterSpec("SHA-256", "MGF1",
                MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        byte[] wrapped = rsa.doFinal(aes.getEncoded());

        return new JSONObject()
                .put("enc", "rsa-oaep-sha256+aes-256-gcm")
                .put("count", nets.length())
                .put("key", Base64.encodeToString(wrapped, Base64.NO_WRAP))
                .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                .put("data", Base64.encodeToString(data, Base64.NO_WRAP));
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
