package com.aioapp.mdm;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * This device's own server credential (device-key plan, phase 2).
 *
 * The shared fleet key ships in this public repo, so it proves nothing about which device
 * is calling. Each device therefore makes its own key, registers it once
 * (POST /api/v1/device-key, sending only its SHA-256), and from then on sends it as
 * X-API-Key; the server then refuses the shared key for this serial.
 *
 * The key is generated here and never leaves the device except as that header. It is kept
 * in the app's private, device-protected storage (the service runs before first unlock),
 * readable only by this app's uid.
 *
 * 1.6.0 and 1.6.1 also encrypted it with an Android Keystore key. A firmware update can
 * make Keystore keys unusable, and on 30 Sep that left three devices unable to read their
 * key: they fell back to the shared key, which the server refuses for a device that has
 * its own, and were locked out. The wrapping protected nothing the private file doesn't
 * (anyone who can read another app's files on the device can use its Keystore keys too),
 * so 1.6.2 stores the key as is and rewrites a wrapped one the first time it can read it.
 *
 * Two slots: "pending" is a key generated but not yet confirmed by the server, kept so a
 * registration whose reply was lost is retried with the same key; "active" is the one in
 * use.
 */
final class DeviceKey {
    private static final String TAG = "DeviceKey";
    private static final String PREFS = "mdm_device_key";
    private static final String ALIAS = "aio_mdm_device_key";
    private static final String ACTIVE = "active";
    private static final String PENDING = "pending";

    private final SharedPreferences prefs;
    private volatile String cachedActive;

    DeviceKey(Context ctx) {
        prefs = ctx.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        migrate(ACTIVE);
        migrate(PENDING);
        cachedActive = read(ACTIVE);
    }

    /** Rewrite a Keystore-wrapped key (1.6.0/1.6.1) in plain form while it is still readable. */
    private void migrate(String slot) {
        String v = prefs.getString(slot, null);
        if (v == null || !v.startsWith("ks:")) return;
        String k = read(slot);
        if (k != null) {
            write(slot, k);
            Log.i(TAG, "moved the " + slot + " key out of Keystore");
        }
    }

    /** The registered key, or null while the device still uses the shared key. */
    String active() { return cachedActive; }

    /** The key being registered: the pending one, or a new one. */
    synchronized String pendingOrCreate() {
        String k = read(PENDING);
        if (k != null) return k;
        byte[] raw = new byte[24];
        new SecureRandom().nextBytes(raw);
        k = "dvk_" + hex(raw);
        write(PENDING, k);
        return k;
    }

    /** The server accepted the pending key: use it from now on. */
    synchronized void promote() {
        String k = read(PENDING);
        if (k == null) return;
        write(ACTIVE, k);
        prefs.edit().remove(PENDING).commit();
        cachedActive = k;
    }

    /** The server no longer knows our key (a restore, or an admin reset): start over. */
    synchronized void forget() {
        prefs.edit().remove(ACTIVE).remove(PENDING).commit();
        cachedActive = null;
    }

    static String sha256Hex(String s) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    // ── storage ─────────────────────────────────────────────────────────────

    private String read(String slot) {
        String v = prefs.getString(slot, null);
        if (v == null) return null;
        if (v.startsWith("plain:")) return v.substring(6);
        if (!v.startsWith("ks:")) return null;
        try {
            byte[] blob = Base64.decode(v.substring(3), Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, wrapKey(), new GCMParameterSpec(128, blob, 0, 12));
            return new String(c.doFinal(blob, 12, blob.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // The Keystore key is gone (e.g. keystore reset): the stored key is unreadable,
            // so the device goes back to the shared key and registers again.
            Log.w(TAG, "stored " + slot + " key unreadable: " + e.getMessage());
            return null;
        }
    }

    private void write(String slot, String key) {
        prefs.edit().putString(slot, "plain:" + key).commit();
    }

    /** The Keystore key 1.6.0/1.6.1 wrapped stored keys with; only read now, never created. */
    private static SecretKey wrapKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) throw new IllegalStateException("no Keystore key " + ALIAS);
        return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
    }
}
