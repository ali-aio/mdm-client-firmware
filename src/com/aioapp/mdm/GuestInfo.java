package com.aioapp.mdm;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * What a guest at the table is shown on a T7: the venue's name, the table the tablet sits
 * on, and the venue's guest Wi-Fi. The MDM sends it as a "guest" object — in the HTTP
 * check-in config, and as its own "guest" WebSocket frame on connect and whenever someone
 * changes it on the dashboard.
 *
 * Kept in DEVICE-PROTECTED storage, like the kiosk config: the status provider
 * ({@link StatusProvider}, read by the launcher and the lock screen) and the widgets can then
 * answer from the first moments of a boot, before the user storage is unlocked. The guest
 * Wi-Fi password is stored with it in the clear; it is the password the venue hands every
 * guest, and this app's private storage is only readable by its uid.
 *
 * Every change notifies {@code content://com.aioapp.status} (observers of /glance and
 * /welcome are reached through it) and redraws the two guest widgets.
 */
final class GuestInfo {
    private static final String TAG = "GuestInfo";
    private static final String PREFS = "mdm_guest";
    private static final String KEY = "guest";

    static final String SEC_WPA = "WPA";
    static final String SEC_WEP = "WEP";
    static final String SEC_NOPASS = "nopass";

    /**
     * The AIO guest ordering app the welcome card opens when the venue names none
     * (guest_app_package): production first, then the UAT build.
     */
    /** The AIO guest ordering app ships under aio.app.nugget and its variants (aio.app.nugget.*). */
    static final String GUEST_APP_PREFIX = "aio.app.nugget";

    final String restaurantName;
    final String tableLabel;
    final String wifiSsid;
    final String wifiPassword;
    /** WPA, WEP or nopass when a network is set; "" when none is. */
    final String wifiSecurity;
    final String appPackage;

    private GuestInfo(String restaurantName, String tableLabel, String wifiSsid,
            String wifiPassword, String wifiSecurity, String appPackage) {
        this.restaurantName = restaurantName;
        this.tableLabel = tableLabel;
        this.wifiSsid = wifiSsid;
        this.appPackage = appPackage;
        if (wifiSsid.isEmpty()) {
            this.wifiPassword = "";
            this.wifiSecurity = "";
        } else if (wifiPassword.isEmpty() || SEC_NOPASS.equalsIgnoreCase(wifiSecurity)) {
            this.wifiPassword = "";
            this.wifiSecurity = SEC_NOPASS;
        } else {
            this.wifiPassword = wifiPassword;
            this.wifiSecurity = SEC_WEP.equalsIgnoreCase(wifiSecurity) ? SEC_WEP : SEC_WPA;
        }
    }

    /** Reads the server's "guest" object; a missing key reads as "" (cleared). */
    static GuestInfo fromJson(JSONObject o) {
        if (o == null) o = new JSONObject();
        return new GuestInfo(str(o, "restaurant_name"), str(o, "table_label"),
                str(o, "wifi_ssid"), str(o, "wifi_password"), str(o, "wifi_security"),
                str(o, "guest_app_package"));
    }

    private static String str(JSONObject o, String k) {
        // optString turns a JSON null into "null"; a null here means unset.
        return o.isNull(k) ? "" : o.optString(k, "").trim();
    }

    JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("restaurant_name", restaurantName);
            o.put("table_label", tableLabel);
            o.put("wifi_ssid", wifiSsid);
            o.put("wifi_password", wifiPassword);
            o.put("wifi_security", wifiSecurity);
            o.put("guest_app_package", appPackage);
        } catch (JSONException ignored) {
            // put() only throws for non-finite numbers
        }
        return o;
    }

    boolean hasRestaurant() {
        return !restaurantName.isEmpty();
    }

    boolean hasTable() {
        return !tableLabel.isEmpty();
    }

    boolean hasWifi() {
        return !wifiSsid.isEmpty();
    }

    boolean isEmpty() {
        return !hasRestaurant() && !hasTable() && !hasWifi();
    }

    // ── Storage ──────────────────────────────────────────────────────────────

    private static SharedPreferences prefs(Context ctx) {
        return ctx.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The guest info last received; all empty before the first one arrives. */
    static GuestInfo load(Context ctx) {
        String raw = null;
        try {
            raw = prefs(ctx).getString(KEY, null);
            return fromJson(raw == null ? null : new JSONObject(raw));
        } catch (JSONException | RuntimeException e) {
            Log.w(TAG, "stored guest info unreadable: " + e.getMessage());
            return fromJson(null);
        }
    }

    /**
     * Keeps the server's "guest" object and, when it differs from what is kept, tells the
     * launcher, the lock screen and the widgets. A null object (a server without guest info,
     * or a frame that does not carry it) changes nothing.
     */
    static void save(Context ctx, JSONObject guest) {
        if (guest == null) return;
        GuestInfo next = fromJson(guest);
        String json = next.toJson().toString();
        SharedPreferences p = prefs(ctx);
        if (json.equals(p.getString(KEY, null))) return;
        // commit, not apply: observers re-query as soon as they are notified below.
        if (!p.edit().putString(KEY, json).commit()) {
            Log.w(TAG, "could not store guest info");
            return;
        }
        Log.i(TAG, "guest info: restaurant=" + !next.restaurantName.isEmpty()
                + " table=" + !next.tableLabel.isEmpty() + " wifi=" + !next.wifiSsid.isEmpty());
        changed(ctx);
    }

    /** Notifies the status provider's observers and redraws the guest widgets. */
    static void changed(Context ctx) {
        try {
            ctx.getContentResolver().notifyChange(StatusProvider.ROOT_URI, null);
        } catch (RuntimeException e) {
            Log.w(TAG, "status notify failed: " + e.getMessage());
        }
        GuestWelcomeWidget.updateAll(ctx);
    }

    // ── Guest app ────────────────────────────────────────────────────────────

    /**
     * The launch intent of the guest ordering app: the venue's own (guest_app_package)
     * when it names one that is installed, else the first installed default. Null when
     * none is installed — the welcome card then does nothing on a tap.
     */
    Intent guestAppIntent(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        if (!appPackage.isEmpty()) {
            Intent i = pm.getLaunchIntentForPackage(appPackage);
            if (i != null) return i;
        }
        // Any installed aio.app.nugget* app: aio.app.nugget itself first, then the others by name.
        java.util.List<String> candidates = new java.util.ArrayList<>();
        for (android.content.pm.ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if (ai.packageName.startsWith(GUEST_APP_PREFIX)) candidates.add(ai.packageName);
        }
        java.util.Collections.sort(candidates, (a, b) -> {
            if (a.equals(GUEST_APP_PREFIX)) return -1;
            if (b.equals(GUEST_APP_PREFIX)) return 1;
            return a.compareTo(b);
        });
        for (String pkg : candidates) {
            Intent i = pm.getLaunchIntentForPackage(pkg);
            if (i != null) return i;
        }
        return null;
    }

    // ── Wi-Fi QR ─────────────────────────────────────────────────────────────

    /**
     * The Wi-Fi network as a QR payload phones join from their camera:
     * {@code WIFI:T:<WPA|WEP|nopass>;S:<ssid>;P:<password>;;}, with \ ; , : " in the
     * name and password escaped by a backslash. Null when no network is set.
     */
    String wifiQrPayload() {
        if (!hasWifi()) return null;
        StringBuilder b = new StringBuilder("WIFI:T:").append(wifiSecurity)
                .append(";S:").append(qrEscape(wifiSsid)).append(';');
        if (!SEC_NOPASS.equals(wifiSecurity)) {
            b.append("P:").append(qrEscape(wifiPassword)).append(';');
        }
        return b.append(';').toString();
    }

    static String qrEscape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') b.append('\\');
            b.append(c);
        }
        return b.toString();
    }
}
