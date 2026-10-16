package com.aioapp.mdm;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The app a device is there to run — on a T7, the AIO guest ordering app (aio.app.nugget and
 * its build variants: .uatv2, .qa, .internal, .stagev2…). The server names it in the config as
 * host_app_pattern ("aio.app.nugget*": a trailing * is a prefix, anything else is exact; several
 * patterns are comma-separated), and this reports how that app is doing, read from OUTSIDE it.
 *
 * Nothing here needs the app's cooperation: the client runs as the system uid, which every
 * check below lets through (UsageStatsService, NetworkStatsAccess, getProcessMemoryInfo and
 * getHistoricalProcessExitReasons all treat SYSTEM_UID as privileged), so no permission is
 * declared for any of it. What it cannot see is the app's own state — which restaurant it is
 * signed in to, its connection to its backend — since the app's data is closed to other uids.
 *
 * Reported as extra.host_app: the variant in use at the top level (package, version_name,
 * version_code — the same keys MDM-lite uses, so the dashboard reads one shape), plus every
 * installed variant under "variants". A device with four builds installed is common in the
 * lab, and "installed" alone says nothing about which one is actually used.
 *
 * Values are coarse (MB, whole minutes, 100 KB) and the snapshot is cached for a minute:
 * host_app is a gated key, so it only costs a frame when something a person would notice
 * changed — the app came to the front, crashed, or was updated.
 */
final class HostApp {
    private static final String TAG = "HostApp";
    private static final String PREFS = "mdm_host_app";
    private static final String KEY_PATTERN = "pattern";

    private static final long REFRESH_MS = 60_000L;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final int MAX_VARIANTS = 8;
    private static final int MAX_EXITS = 50;

    private static JSONObject cached;
    private static String cachedPattern;
    private static long cachedAt;

    private HostApp() {}

    /** Stores the server's host_app_pattern. Called only when the config carries the key. */
    static void savePattern(Context ctx, String pattern) {
        String p = pattern == null ? "" : pattern.trim();
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (p.equals(sp.getString(KEY_PATTERN, ""))) return;
        sp.edit().putString(KEY_PATTERN, p).apply();
        synchronized (HostApp.class) { cachedAt = 0; } // a new pattern answers at once
        Log.i(TAG, "host app pattern: \"" + p + "\"");
    }

    /** The current snapshot, or null when the server names no host app. */
    static synchronized JSONObject snapshot(Context ctx) {
        String pattern = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PATTERN, "");
        if (pattern.isEmpty()) return null;
        long now = SystemClock.elapsedRealtime();
        if (pattern.equals(cachedPattern) && cachedAt != 0 && now - cachedAt < REFRESH_MS) return cached;
        JSONObject snap;
        try {
            snap = collect(ctx, pattern);
        } catch (Throwable t) {
            // Keep the last good snapshot: a missing key would read as "no host app" upstream.
            Log.w(TAG, "host app snapshot failed: " + t.getMessage());
            snap = pattern.equals(cachedPattern) ? cached : null;
        }
        cached = snap;
        cachedPattern = pattern;
        cachedAt = now;
        return snap;
    }

    static boolean matches(String pattern, String pkg) {
        for (String p : pattern.split(",")) {
            p = p.trim();
            if (p.isEmpty()) continue;
            if (p.endsWith("*") ? pkg.startsWith(p.substring(0, p.length() - 1)) : pkg.equals(p)) return true;
        }
        return false;
    }

    private static JSONObject collect(Context ctx, String pattern) throws JSONException {
        PackageManager pm = ctx.getPackageManager();
        List<PackageInfo> pkgs = new ArrayList<>();
        for (PackageInfo pi : pm.getInstalledPackages(0)) {
            if (pi.packageName != null && matches(pattern, pi.packageName)) pkgs.add(pi);
            if (pkgs.size() >= MAX_VARIANTS) break;
        }

        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        Map<String, ActivityManager.RunningAppProcessInfo> procs = new HashMap<>();
        if (am != null) {
            List<ActivityManager.RunningAppProcessInfo> running = am.getRunningAppProcesses();
            if (running != null) {
                for (ActivityManager.RunningAppProcessInfo p : running) {
                    // The main process carries the package name; :service processes do not.
                    if (p.processName != null && !procs.containsKey(p.processName)) procs.put(p.processName, p);
                }
            }
        }
        String top = KioskManager.foregroundPackage(ctx);

        long wall = System.currentTimeMillis();
        long midnight = localMidnight(wall);
        Map<String, UsageStats> usage = usageSince(ctx, midnight, wall);
        Map<Integer, long[]> net = wifiBytesSince(ctx, midnight, wall);

        JSONArray variants = new JSONArray();
        JSONObject active = null;
        long activeRank = Long.MIN_VALUE;
        for (PackageInfo pi : pkgs) {
            String pkg = pi.packageName;
            JSONObject v = new JSONObject();
            v.put("package", pkg);
            v.put("version_name", pi.versionName == null ? "" : pi.versionName);
            v.put("version_code", pi.getLongVersionCode());
            v.put("installed_at", pi.firstInstallTime);
            v.put("updated_at", pi.lastUpdateTime);
            ApplicationInfo ai = pi.applicationInfo;
            if (ai != null) v.put("enabled", ai.enabled);
            try {
                String inst = pm.getInstallSourceInfo(pkg).getInstallingPackageName();
                if (inst != null) v.put("installer", inst);
            } catch (Exception ignored) { }

            boolean fg = pkg.equals(top);
            ActivityManager.RunningAppProcessInfo proc = procs.get(pkg);
            v.put("running", proc != null);
            v.put("foreground", fg);
            if (proc != null && am != null) {
                try {
                    android.os.Debug.MemoryInfo[] mi = am.getProcessMemoryInfo(new int[]{proc.pid});
                    if (mi != null && mi.length > 0 && mi[0].getTotalPss() > 0) {
                        v.put("pss_mb", mi[0].getTotalPss() / 1024);
                    }
                } catch (Exception ignored) { }
                // To 10 s: the wall/elapsed clocks drift a little between reads, and an exact
                // value would change the gated key (and cost a frame) every minute.
                long started = processStartedAt(proc.pid, wall) / 10_000L * 10_000L;
                if (started > 0) v.put("started_at", started);
            }

            UsageStats us = usage.get(pkg);
            long lastUsed = 0;
            if (us != null) {
                v.put("fg_today_min", us.getTotalTimeInForeground() / 60_000L);
                lastUsed = us.getLastTimeUsed();
                if (lastUsed > 0) v.put("last_used_at", lastUsed / 60_000L * 60_000L);
            }
            if (ai != null) {
                long[] b = net.get(ai.uid);
                if (b != null) {
                    v.put("rx_today_kb", b[0] / 102_400L * 100);
                    v.put("tx_today_kb", b[1] / 102_400L * 100);
                }
            }
            putExits(am, pkg, wall, v);
            variants.put(v);

            // The variant in use: the one on screen, then a running one, then the one used last.
            long rank = (fg ? 2L << 60 : 0) + (proc != null ? 1L << 60 : 0) + Math.max(0, lastUsed);
            if (rank > activeRank) {
                activeRank = rank;
                active = v;
            }
        }

        JSONObject out = new JSONObject();
        out.put("pattern", pattern);
        if (active != null) {
            out.put("package", active.getString("package"));
            out.put("version_name", active.optString("version_name"));
            out.put("version_code", active.optLong("version_code"));
        }
        out.put("variants", variants);
        return out;
    }

    /**
     * Why the app's process ended over the last day (crash, ANR, low memory, killed…) and the
     * most recent end. Covers what DropBox does for crashes and adds the quiet deaths — a
     * low-memory kill leaves no crash report, and on a tablet it looks just like a crash.
     */
    private static void putExits(ActivityManager am, String pkg, long wall, JSONObject v) {
        if (am == null) return;
        try {
            List<ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(pkg, 0, MAX_EXITS);
            if (exits == null || exits.isEmpty()) return;
            JSONObject counts = new JSONObject();
            for (ApplicationExitInfo e : exits) {
                if (wall - e.getTimestamp() > DAY_MS) continue;
                String r = exitReason(e.getReason());
                counts.put(r, counts.optInt(r, 0) + 1);
            }
            if (counts.length() > 0) v.put("exits_24h", counts);
            ApplicationExitInfo last = exits.get(0); // newest first
            JSONObject l = new JSONObject();
            l.put("at", last.getTimestamp());
            l.put("reason", exitReason(last.getReason()));
            String desc = last.getDescription();
            if (desc != null && !desc.isEmpty()) l.put("description", desc.length() > 160 ? desc.substring(0, 160) : desc);
            v.put("last_exit", l);
        } catch (Exception e) {
            Log.w(TAG, "exit reasons for " + pkg + ": " + e.getMessage());
        }
    }

    private static String exitReason(int r) {
        switch (r) {
            case ApplicationExitInfo.REASON_CRASH: return "crash";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "native_crash";
            case ApplicationExitInfo.REASON_ANR: return "anr";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "low_memory";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "excessive_resources";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "init_failure";
            case ApplicationExitInfo.REASON_EXIT_SELF: return "exit_self";
            case ApplicationExitInfo.REASON_SIGNALED: return "signaled";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "user_requested";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "user_stopped";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "dependency_died";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "permission_change";
            case ApplicationExitInfo.REASON_FREEZER: return "freezer";
            case ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE: return "package_state_change";
            case ApplicationExitInfo.REASON_PACKAGE_UPDATED: return "package_updated";
            default: return "other";
        }
    }

    private static Map<String, UsageStats> usageSince(Context ctx, long from, long to) {
        try {
            UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm != null) {
                Map<String, UsageStats> m = usm.queryAndAggregateUsageStats(from, to);
                if (m != null) return m;
            }
        } catch (Exception e) {
            Log.w(TAG, "usage stats: " + e.getMessage());
        }
        return new HashMap<>();
    }

    /** Wi-Fi bytes {rx, tx} per uid since from. The T7 has no other network to count. */
    private static Map<Integer, long[]> wifiBytesSince(Context ctx, long from, long to) {
        Map<Integer, long[]> out = new HashMap<>();
        NetworkStats ns = null;
        try {
            NetworkStatsManager nsm = (NetworkStatsManager) ctx.getSystemService(Context.NETWORK_STATS_SERVICE);
            if (nsm == null) return out;
            ns = nsm.querySummary(ConnectivityManager.TYPE_WIFI, null, from, to);
            NetworkStats.Bucket b = new NetworkStats.Bucket();
            while (ns.hasNextBucket()) {
                ns.getNextBucket(b);
                long[] t = out.get(b.getUid());
                if (t == null) out.put(b.getUid(), t = new long[2]);
                t[0] += b.getRxBytes();
                t[1] += b.getTxBytes();
            }
        } catch (Exception e) {
            Log.w(TAG, "network stats: " + e.getMessage());
        } finally {
            if (ns != null) ns.close();
        }
        return out;
    }

    /**
     * When the process started, from /proc/<pid>/stat (field 22: start time in clock ticks
     * since boot). 0 when it cannot be read. A start well after the device booted, with no
     * exit to explain it, is a restart nobody saw.
     */
    private static long processStartedAt(int pid, long wall) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/" + pid + "/stat"))) {
            String line = r.readLine();
            if (line == null) return 0;
            // The command name is in parentheses and may hold spaces: count fields after it.
            String[] f = line.substring(line.lastIndexOf(')') + 2).split(" ");
            long ticks = Long.parseLong(f[19]); // field 22 overall; 3 is the first after ')'
            long hz = android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK);
            if (hz <= 0) hz = 100;
            long startedSinceBootMs = ticks * 1000L / hz;
            return wall - (SystemClock.elapsedRealtime() - startedSinceBootMs);
        } catch (Exception e) {
            return 0;
        }
    }

    private static long localMidnight(long wall) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(wall);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
}
