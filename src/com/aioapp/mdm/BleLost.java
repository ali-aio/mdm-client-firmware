package com.aioapp.mdm;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lost-device reporting over Bluetooth LE (1.13.0). Two halves:
 *
 * <ul>
 * <li><b>Beacon</b> — while lost mode carries a {@code beacon} token from the server, this device
 * advertises it (service data under {@link #SERVICE_UUID}, no name, non-connectable, low power).
 * The token is a random 8 bytes made per lost session, so nothing outside the server can tell
 * which device it is or follow it afterwards.</li>
 * <li><b>Listener</b> — while the server's config names tokens to listen for ({@code lost_listen}),
 * every {@code every_s} this device scans for {@code scan_s} and reports what it heard
 * ({@code extra.ble_sightings}). No key, no scanning: it costs nothing until a device at the
 * venue is lost.</li>
 * </ul>
 *
 * Bluetooth is off by default on our images. Either half switches it on when it needs it and
 * back off when neither needs it any more — only if it was this client that switched it on, so
 * a device someone turned Bluetooth on for keeps it on. The client is a platform-signed system
 * app, which is what lets it enable the adapter and scan without location mode
 * (BLUETOOTH_PRIVILEGED).
 */
final class BleLost {
    private static final String TAG = "BleLost";
    static final ParcelUuid SERVICE_UUID = ParcelUuid.fromString("6a1f0001-a10c-4d4d-9c5e-4c0574000001");

    private static final String PREFS = "mdm_ble_lost";
    private static final String K_BT_OURS = "bt_enabled_by_us"; // we switched the adapter on
    // When the adapter last failed to come on (wall clock ms). A T7 image whose Bluetooth
    // controller does not start (v2.1.034: the QTI UART HAL crash-loops while the setting is on)
    // must not be asked again every minute — each try leaves the HAL restarting.
    private static final String K_BT_FAILED_AT = "bt_failed_at";
    private static final long BT_RETRY_MS = 6 * 60 * 60 * 1000L;
    private static final String K_ADV_OK = "adv_supported";     // last known: 1 yes, 0 no, absent unknown
    private static final String K_LISTEN = "listen";            // the lost_listen object, as JSON

    private static final long ENABLE_WAIT_MS = 6_000;

    private BleLost() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static BluetoothAdapter adapter(Context ctx) {
        try {
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            return bm == null ? null : bm.getAdapter();
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean hasLe(Context ctx) {
        return ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
                && adapter(ctx) != null;
    }

    // ── capability flags (extra.ble_listen / extra.ble_beacon) ─────────────────────────────

    /** Puts the capability flags in extra: what this hardware can do, not what it is doing. */
    static void putCapabilities(Context ctx, JSONObject extra) throws JSONException {
        if (!hasLe(ctx)) return;
        // Not while the radio is known not to start: the server then leaves this device out of
        // listen lists instead of asking it to scan with a dead adapter.
        if (btBroken(ctx)) {
            extra.put("ble_broken", true);
            return;
        }
        extra.put("ble_listen", true);
        BluetoothAdapter a = adapter(ctx);
        // isMultipleAdvertisementSupported only answers while the adapter is on; remember the
        // last real answer and assume yes (LE hardware) until the adapter has been on once.
        if (a != null && a.isEnabled()) {
            boolean adv = a.isMultipleAdvertisementSupported() || a.getBluetoothLeAdvertiser() != null;
            prefs(ctx).edit().putInt(K_ADV_OK, adv ? 1 : 0).apply();
        }
        if (prefs(ctx).getInt(K_ADV_OK, 1) == 1) extra.put("ble_beacon", true);
    }

    // ── adapter on/off, owned between the two halves ────────────────────────────────────────

    /** Makes sure the adapter is on; returns false when it can't be (and why, in why[0]). */
    private static boolean btBroken(Context ctx) {
        long at = prefs(ctx).getLong(K_BT_FAILED_AT, 0);
        return at > 0 && System.currentTimeMillis() - at < BT_RETRY_MS;
    }

    private static boolean ensureOn(Context ctx, String[] why) {
        BluetoothAdapter a = adapter(ctx);
        if (a == null) { why[0] = "no bluetooth"; return false; }
        if (a.isEnabled()) return true;
        if (btBroken(ctx)) { why[0] = "bluetooth did not start on this device; next try in a few hours"; return false; }
        try {
            if (!a.enable()) { why[0] = "bluetooth could not be switched on"; return false; }
        } catch (SecurityException e) {
            why[0] = "not allowed to switch bluetooth on"; return false;
        }
        prefs(ctx).edit().putBoolean(K_BT_OURS, true).commit();
        long until = SystemClock.elapsedRealtime() + ENABLE_WAIT_MS;
        while (!a.isEnabled() && SystemClock.elapsedRealtime() < until) {
            SystemClock.sleep(200);
        }
        if (!a.isEnabled()) {
            // Leave the setting off again: left on, a controller that will not start keeps the
            // HAL restarting for as long as it is on.
            try { a.disable(); } catch (Throwable t) { Log.w(TAG, "disable after failed start: " + t.getMessage()); }
            prefs(ctx).edit().putBoolean(K_BT_OURS, false).putLong(K_BT_FAILED_AT, System.currentTimeMillis()).commit();
            why[0] = "bluetooth did not come on";
            return false;
        }
        prefs(ctx).edit().remove(K_BT_FAILED_AT).commit();
        Log.i(TAG, "switched bluetooth on");
        return true;
    }

    /** Switches the adapter back off if we switched it on and nothing of ours needs it now. */
    private static void releaseIfOurs(Context ctx) {
        if (beaconWanted(ctx) || scanning) return;
        SharedPreferences p = prefs(ctx);
        if (!p.getBoolean(K_BT_OURS, false)) return;
        BluetoothAdapter a = adapter(ctx);
        try {
            // Also when it never came on: the setting is still on and must go back off.
            if (a != null) a.disable();
            Log.i(TAG, "switched bluetooth back off");
        } catch (Throwable t) {
            Log.w(TAG, "could not switch bluetooth off: " + t.getMessage());
        }
        p.edit().putBoolean(K_BT_OURS, false).commit();
    }

    private static int stuckSeen = 0;

    /**
     * The Bluetooth setting is on while the radio stays off, and nothing of ours wants it: a
     * controller that will not start, left "on" (1.13.0 did that on T7 v2.1.034, whose QTI UART
     * HAL then restarts in a loop for as long as the setting holds). Seen on two checks in a
     * row it is switched off — on a working radio that state never lasts, so this never turns
     * off a Bluetooth someone is using.
     */
    private static void clearStuckSetting(Context ctx) {
        if (scanning) { stuckSeen = 0; return; }
        BluetoothAdapter a = adapter(ctx);
        if (a == null) return;
        int on;
        try {
            on = android.provider.Settings.Global.getInt(ctx.getContentResolver(), android.provider.Settings.Global.BLUETOOTH_ON, 0);
        } catch (Throwable t) {
            return;
        }
        if (on != 1 || a.getState() != BluetoothAdapter.STATE_OFF) { stuckSeen = 0; return; }
        if (++stuckSeen < 2) return;
        stuckSeen = 0;
        try {
            a.disable();
            prefs(ctx).edit().putBoolean(K_BT_OURS, false).putLong(K_BT_FAILED_AT, System.currentTimeMillis()).commit();
            Log.i(TAG, "bluetooth was set on but never started; switched the setting off");
        } catch (Throwable t) {
            Log.w(TAG, "could not clear the stuck bluetooth setting: " + t.getMessage());
        }
    }

    // ── beacon (the lost device) ────────────────────────────────────────────────────────────

    private static volatile boolean advertising = false;
    private static volatile JSONObject beaconStatus = null;
    private static final AdvertiseCallback ADV_CB = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            advertising = true;
            beaconStatus = status(true, null);
            Log.i(TAG, "lost beacon on air");
        }

        @Override
        public void onStartFailure(int errorCode) {
            // ALREADY_STARTED (3) means it is on air from an earlier start.
            advertising = errorCode == ADVERTISE_FAILED_ALREADY_STARTED;
            beaconStatus = advertising ? status(true, null) : status(false, "advertising failed (" + errorCode + ")");
            Log.w(TAG, "lost beacon failed to start: " + errorCode);
        }
    };

    private static boolean beaconWanted(Context ctx) {
        return LostMode.isOn(ctx) && !LostMode.beacon(ctx).isEmpty();
    }

    /**
     * Brings the beacon in line with lost mode: on air while lost with a token, off otherwise.
     * Safe to call often (every Wi-Fi scan tick, at boot, on every lost_mode command); it only
     * acts on a change. Blocks up to a few seconds when it has to switch Bluetooth on, so call
     * it off the main thread.
     */
    static synchronized void syncBeacon(Context ctx) {
        if (!beaconWanted(ctx)) {
            if (advertising) stopAdvertising(ctx);
            beaconStatus = null;
            releaseIfOurs(ctx);
            clearStuckSetting(ctx);
            return;
        }
        if (advertising) {
            BluetoothAdapter a = adapter(ctx);
            if (a != null && a.isEnabled()) return;
            advertising = false; // the adapter went off under us; start again
        }
        String[] why = new String[1];
        if (!ensureOn(ctx, why)) { beaconStatus = status(false, why[0]); return; }
        BluetoothAdapter a = adapter(ctx);
        BluetoothLeAdvertiser adv = a == null ? null : a.getBluetoothLeAdvertiser();
        if (adv == null || !a.isMultipleAdvertisementSupported()) {
            prefs(ctx).edit().putInt(K_ADV_OK, 0).apply();
            beaconStatus = status(false, "this hardware can't advertise");
            return;
        }
        byte[] token = hex(LostMode.beacon(ctx));
        if (token == null || token.length != 8) { beaconStatus = status(false, "bad beacon token"); return; }
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(false)
                .setTimeout(0)
                .build();
        // Flags + 128-bit service data with 8 bytes = 29 of the 31 bytes; nothing else fits,
        // and the name stays off so the device doesn't announce itself.
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceData(SERVICE_UUID, token)
                .build();
        try {
            adv.startAdvertising(settings, data, ADV_CB);
        } catch (Throwable t) {
            beaconStatus = status(false, "advertising refused: " + t.getMessage());
        }
    }

    private static void stopAdvertising(Context ctx) {
        try {
            BluetoothAdapter a = adapter(ctx);
            BluetoothLeAdvertiser adv = a == null ? null : a.getBluetoothLeAdvertiser();
            if (adv != null) adv.stopAdvertising(ADV_CB);
        } catch (Throwable t) {
            Log.w(TAG, "stop advertising: " + t.getMessage());
        }
        advertising = false;
        Log.i(TAG, "lost beacon off");
    }

    /** extra.lost_beacon while lost with a token, else null (not reported). */
    static JSONObject beaconReport(Context ctx) {
        return beaconWanted(ctx) ? beaconStatus : null;
    }

    // ── listener (every other device) ───────────────────────────────────────────────────────

    private static volatile boolean scanning = false;
    private static volatile long lastScanAt = 0;      // elapsedRealtime of the last scan
    private static volatile JSONArray lastSightings = null;
    private static volatile JSONObject scanStatus = null;

    /**
     * Keeps the server's lost_listen object. A config without the key means nobody nearby is
     * lost: stop listening and forget what was heard.
     */
    static void saveListen(Context ctx, JSONObject cfg) {
        JSONObject listen = cfg.optJSONObject("lost_listen");
        JSONArray b = listen == null ? null : listen.optJSONArray("beacons");
        if (b == null || b.length() == 0) {
            if (prefs(ctx).contains(K_LISTEN)) {
                prefs(ctx).edit().remove(K_LISTEN).apply();
                lastSightings = null;
                scanStatus = null;
            }
            return;
        }
        prefs(ctx).edit().putString(K_LISTEN, listen.toString()).apply();
    }

    private static JSONObject listen(Context ctx) {
        String raw = prefs(ctx).getString(K_LISTEN, null);
        if (raw == null) return null;
        try {
            return new JSONObject(raw);
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * Called every few seconds from the client's scheduler: runs a scan when one is due.
     * Returns true when the scan heard something new, so the caller can send a frame.
     */
    static boolean listenTick(Context ctx) {
        JSONObject l = listen(ctx);
        if (l == null) return false;
        int every = Math.max(30, Math.min(3600, l.optInt("every_s", 120)));
        int scanS = Math.max(2, Math.min(30, l.optInt("scan_s", 8)));
        long now = SystemClock.elapsedRealtime();
        if (lastScanAt != 0 && now - lastScanAt < every * 1000L) return false;
        lastScanAt = now;
        Set<String> wanted = new HashSet<>();
        JSONArray b = l.optJSONArray("beacons");
        for (int i = 0; b != null && i < b.length(); i++) wanted.add(b.optString(i).toLowerCase(Locale.ROOT));
        if (wanted.isEmpty()) return false;

        String[] why = new String[1];
        scanning = true;
        try {
            if (!ensureOn(ctx, why)) { scanStatus = status(false, why[0]); return false; }
            BluetoothAdapter a = adapter(ctx);
            BluetoothLeScanner sc = a == null ? null : a.getBluetoothLeScanner();
            if (sc == null) { scanStatus = status(false, "no LE scanner"); return false; }
            final Map<String, Integer> best = new HashMap<>();
            ScanCallback cb = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult r) { take(r); }

                @Override
                public void onBatchScanResults(List<ScanResult> rs) {
                    for (ScanResult r : rs) take(r);
                }

                private void take(ScanResult r) {
                    if (r == null || r.getScanRecord() == null) return;
                    byte[] d = r.getScanRecord().getServiceData(SERVICE_UUID);
                    if (d == null || d.length < 8) return;
                    String tok = hex(d, 8);
                    synchronized (best) {
                        Integer cur = best.get(tok);
                        if (cur == null || r.getRssi() > cur) best.put(tok, r.getRssi());
                    }
                }
            };
            List<ScanFilter> filters = Collections.singletonList(
                    new ScanFilter.Builder().setServiceData(SERVICE_UUID, new byte[0]).build());
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            try {
                sc.startScan(filters, settings, cb);
            } catch (Throwable t) {
                scanStatus = status(false, "scan refused: " + t.getMessage());
                return false;
            }
            SystemClock.sleep(scanS * 1000L);
            try { sc.stopScan(cb); } catch (Throwable ignored) { }
            scanStatus = null;
            long at = System.currentTimeMillis();
            JSONArray hits = new JSONArray();
            synchronized (best) {
                for (Map.Entry<String, Integer> e : best.entrySet()) {
                    if (!wanted.contains(e.getKey())) continue;
                    try {
                        hits.put(new JSONObject().put("beacon", e.getKey()).put("rssi", e.getValue()).put("at_ms", at));
                    } catch (JSONException ignored) { }
                }
            }
            lastSightings = hits.length() > 0 ? hits : null;
            if (hits.length() > 0) Log.i(TAG, "heard " + hits.length() + " lost beacon(s)");
            return hits.length() > 0;
        } finally {
            scanning = false;
            releaseIfOurs(ctx);
        }
    }

    /** extra.ble_sightings (the last scan's hits) and extra.ble_scan (only when it can't scan). */
    static void putListenReport(Context ctx, JSONObject extra) throws JSONException {
        if (listen(ctx) == null) return;
        if (lastSightings != null) extra.put("ble_sightings", lastSightings);
        if (scanStatus != null) extra.put("ble_scan", scanStatus);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private static JSONObject status(boolean ok, String why) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", ok);
            if (why != null) o.put("why", why);
        } catch (JSONException ignored) { }
        return o;
    }

    static byte[] hex(String s) {
        if (s == null || s.length() % 2 != 0) return null;
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(2 * i), 16), lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static String hex(byte[] b, int n) {
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) sb.append(String.format(Locale.ROOT, "%02x", b[i] & 0xff));
        return sb.toString();
    }
}
