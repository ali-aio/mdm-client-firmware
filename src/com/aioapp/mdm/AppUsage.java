package com.aioapp.mdm;

import android.app.ActivityManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.BatteryManager;
import android.os.BatteryStatsManager;
import android.os.BatteryUsageStats;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.ServiceManager;
import android.os.UidBatteryConsumer;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-app usage, about every 15 minutes (client 1.17.0; dashboard Resource use card, A1,
 * 22 Oct 2026: "stats of individual apps and their time consumption of usage and
 * contribution to usage of memory cpu etc. so that we know what apps or services are
 * consuming most"). Posted to POST /api/v1/app-usage.
 *
 * Every source is open to the system uid this client runs as:
 * <pre>
 *   screen time, launches   UsageStatsManager events over the window
 *   Wi-Fi rx / tx           NetworkStatsManager summary per uid over the window
 *   memory (PSS)            ActivityManager.getProcessMemoryInfo for every running process
 *   CPU                     the "cpuinfo" service dump (recent load per process), in-process
 *   battery (mAh)           BatteryStatsManager usage per uid, as a delta between reports
 * </pre>
 * The window is [last report, now] and only moves on a 200, so a device that was offline
 * reports the whole stretch when it is back (capped at a day).
 */
final class AppUsage {
    private static final String TAG = "AppUsage";
    private static final String PREFS = "app_usage";
    private static final String K_LAST = "last_to_ms";
    static final long INTERVAL_MS = 15 * 60 * 1000L;
    private static final long MAX_WINDOW_MS = 24 * 60 * 60 * 1000L;
    private static final Pattern CPU_LINE = Pattern.compile("^\\s*([\\d.]+)%\\s+\\d+/([^:\\s]+)");

    private static final Map<Integer, Double> lastPower = new HashMap<>();

    private AppUsage() {}

    /** One report: builds the window's summary and posts it; returns true on a 200. */
    static boolean report(Context ctx, MdmApiService api, String serial) {
        SharedPreferences p = ctx.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long from = p.getLong(K_LAST, now - INTERVAL_MS);
        if (from >= now || now - from > MAX_WINDOW_MS) from = Math.max(now - MAX_WINDOW_MS, Math.min(from, now - INTERVAL_MS));
        Map<String, JSONObject> apps = new HashMap<>();
        PackageManager pm = ctx.getPackageManager();
        try { screenTime(ctx, from, now, apps); } catch (Exception e) { Log.w(TAG, "screen time: " + e.getMessage()); }
        try { network(ctx, pm, from, now, apps); } catch (Exception e) { Log.w(TAG, "network: " + e.getMessage()); }
        try { memory(ctx, apps); } catch (Exception e) { Log.w(TAG, "memory: " + e.getMessage()); }
        try { cpu(apps); } catch (Exception e) { Log.w(TAG, "cpu: " + e.getMessage()); }
        try { battery(ctx, pm, apps); } catch (Exception e) { Log.w(TAG, "battery: " + e.getMessage()); }
        try {
            JSONArray arr = new JSONArray();
            for (Map.Entry<String, JSONObject> e : apps.entrySet()) {
                JSONObject a = e.getValue();
                a.put("package", e.getKey());
                a.put("system", isSystem(pm, e.getKey()));
                arr.put(a);
                if (arr.length() >= 400) break;
            }
            JSONObject body = new JSONObject();
            body.put("serial", serial);
            body.put("from_ms", from);
            body.put("to_ms", now);
            body.put("apps", arr);
            int code = api.postAppUsage(body);
            if (code == 200) {
                p.edit().putLong(K_LAST, now).apply();
                return true;
            }
            Log.w(TAG, "report refused: HTTP " + code);
        } catch (Exception e) {
            Log.w(TAG, "report failed: " + e.getMessage());
        }
        return false;
    }

    private static JSONObject app(Map<String, JSONObject> apps, String pkg) {
        JSONObject a = apps.get(pkg);
        if (a == null) { a = new JSONObject(); apps.put(pkg, a); }
        return a;
    }

    private static void add(JSONObject a, String key, long v) throws Exception { a.put(key, a.optLong(key, 0) + v); }

    /** Foreground time and launches from resume/pause events, clipped to the window. */
    private static void screenTime(Context ctx, long from, long to, Map<String, JSONObject> apps) throws Exception {
        UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return;
        // Start earlier so an app already in front at `from` is counted from `from`.
        UsageEvents ev = usm.queryEvents(from - 3 * 60 * 60 * 1000L, to);
        UsageEvents.Event e = new UsageEvents.Event();
        String fg = null;
        long fgSince = 0;
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            int t = e.getEventType();
            long at = e.getTimeStamp();
            if (t == UsageEvents.Event.ACTIVITY_RESUMED) {
                String pkg = e.getPackageName();
                if (fg != null && !fg.equals(pkg)) addFg(apps, fg, fgSince, at, from, to);
                if (!pkg.equals(fg)) {
                    if (at >= from) add(app(apps, pkg), "launches", 1);
                    fg = pkg;
                    fgSince = at;
                }
            } else if (t == UsageEvents.Event.ACTIVITY_PAUSED || t == UsageEvents.Event.SCREEN_NON_INTERACTIVE) {
                if (fg != null && (t == UsageEvents.Event.SCREEN_NON_INTERACTIVE || fg.equals(e.getPackageName()))) {
                    addFg(apps, fg, fgSince, at, from, to);
                    fg = null;
                }
            }
        }
        if (fg != null) addFg(apps, fg, fgSince, to, from, to);
    }

    private static void addFg(Map<String, JSONObject> apps, String pkg, long a, long b, long from, long to) throws Exception {
        long s = Math.max(a, from), t = Math.min(b, to);
        if (t > s) add(app(apps, pkg), "fg_ms", t - s);
    }

    /** Wi-Fi bytes per uid; a shared uid (system, 1000) goes to its first package. */
    private static void network(Context ctx, PackageManager pm, long from, long to, Map<String, JSONObject> apps) throws Exception {
        NetworkStatsManager nsm = (NetworkStatsManager) ctx.getSystemService(Context.NETWORK_STATS_SERVICE);
        if (nsm == null) return;
        NetworkStats ns = nsm.querySummary(ConnectivityManager.TYPE_WIFI, null, from, to);
        NetworkStats.Bucket b = new NetworkStats.Bucket();
        Map<Integer, long[]> byUid = new HashMap<>();
        while (ns.hasNextBucket()) {
            ns.getNextBucket(b);
            long[] v = byUid.get(b.getUid());
            if (v == null) { v = new long[2]; byUid.put(b.getUid(), v); }
            v[0] += b.getRxBytes();
            v[1] += b.getTxBytes();
        }
        ns.close();
        for (Map.Entry<Integer, long[]> e : byUid.entrySet()) {
            String pkg = pkgForUid(pm, e.getKey());
            if (pkg == null || (e.getValue()[0] + e.getValue()[1]) == 0) continue;
            JSONObject a = app(apps, pkg);
            add(a, "rx_bytes", e.getValue()[0]);
            add(a, "tx_bytes", e.getValue()[1]);
        }
    }

    private static String pkgForUid(PackageManager pm, int uid) {
        if (uid == 1000) return "android";
        if (uid < 0) return null; // removed apps, tethering
        if (uid == 0) return "root";
        String[] pkgs = pm.getPackagesForUid(uid);
        if (pkgs != null && pkgs.length > 0) return pkgs[0];
        String name = pm.getNameForUid(uid);
        return name != null ? name : "uid " + uid;
    }

    /** Proportional set size of every running process, summed per package. */
    private static void memory(Context ctx, Map<String, JSONObject> apps) throws Exception {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return;
        List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
        if (procs == null || procs.isEmpty()) return;
        int[] pids = new int[procs.size()];
        for (int i = 0; i < pids.length; i++) pids[i] = procs.get(i).pid;
        android.os.Debug.MemoryInfo[] mi = am.getProcessMemoryInfo(pids);
        Map<String, Integer> kb = new HashMap<>();
        for (int i = 0; i < procs.size() && i < mi.length; i++) {
            ActivityManager.RunningAppProcessInfo pi = procs.get(i);
            String pkg = pi.pkgList != null && pi.pkgList.length > 0 ? pi.pkgList[0] : pi.processName;
            Integer cur = kb.get(pkg);
            kb.put(pkg, (cur == null ? 0 : cur) + mi[i].getTotalPss());
        }
        for (Map.Entry<String, Integer> e : kb.entrySet()) {
            int mb = Math.round(e.getValue() / 1024f);
            if (mb <= 0) continue;
            JSONObject a = app(apps, e.getKey());
            a.put("pss_mb", mb);
            a.put("pss_max_mb", mb);
        }
    }

    /** CPU load per process over the last minutes, from the cpuinfo service's own dump. */
    private static void cpu(Map<String, JSONObject> apps) throws Exception {
        IBinder b = ServiceManager.getService("cpuinfo");
        if (b == null) return;
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        Thread t = new Thread(() -> {
            try { b.dump(pipe[1].getFileDescriptor(), new String[0]); } catch (Exception ignored) {}
            try { pipe[1].close(); } catch (Exception ignored) {}
        }, "cpuinfo-dump");
        t.start();
        Map<String, Double> pct = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(pipe[0].getFileDescriptor())))) {
            String line;
            while ((line = r.readLine()) != null) {
                Matcher m = CPU_LINE.matcher(line);
                if (!m.find()) continue;
                String proc = m.group(2);
                int colon = proc.indexOf(':');
                String pkg = colon > 0 ? proc.substring(0, colon) : proc;
                Double cur = pct.get(pkg);
                pct.put(pkg, (cur == null ? 0 : cur) + Double.parseDouble(m.group(1)));
            }
        } finally {
            try { pipe[0].close(); } catch (Exception ignored) {}
        }
        t.join(2000);
        for (Map.Entry<String, Double> e : pct.entrySet()) {
            if (e.getValue() <= 0) continue;
            app(apps, e.getKey()).put("cpu_pct", Math.round(e.getValue() * 10) / 10.0);
        }
    }

    /** Battery used per uid since the last report (devices with a battery). */
    private static void battery(Context ctx, PackageManager pm, Map<String, JSONObject> apps) throws Exception {
        Intent bi = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bi == null || !bi.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)) return;
        BatteryStatsManager bsm = ctx.getSystemService(BatteryStatsManager.class);
        if (bsm == null) return;
        BatteryUsageStats stats = bsm.getBatteryUsageStats();
        Map<Integer, Double> now = new HashMap<>();
        for (UidBatteryConsumer c : stats.getUidBatteryConsumers()) now.put(c.getUid(), c.getConsumedPower());
        boolean first = lastPower.isEmpty();
        for (Map.Entry<Integer, Double> e : now.entrySet()) {
            Double before = lastPower.get(e.getKey());
            double d = before == null || e.getValue() < before ? (first ? 0 : e.getValue()) : e.getValue() - before;
            if (d <= 0.01) continue;
            String pkg = pkgForUid(pm, e.getKey());
            if (pkg == null) continue;
            JSONObject a = app(apps, pkg);
            a.put("power_mah", Math.round((a.optDouble("power_mah", 0) + d) * 100) / 100.0);
        }
        lastPower.clear();
        lastPower.putAll(now);
        try { ((java.io.Closeable) (Object) stats).close(); } catch (Exception ignored) {}
    }

    private static boolean isSystem(PackageManager pm, String pkg) {
        if ("android".equals(pkg) || "root".equals(pkg)) return true;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        } catch (Exception e) {
            return false;
        }
    }
}
