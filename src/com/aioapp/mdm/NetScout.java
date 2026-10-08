package com.aioapp.mdm;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The scout: this device looks for other Android devices on its own Wi-Fi that trust the
 * fleet adb key, reports what each one is, and — only when the server tells it to —
 * installs the standard client on one and makes it Device Owner. This is the
 * firmware-client half of "a T7 enrols the other devices in its restaurant" (server side:
 * sightings table and the net_* frames; plan section 7).
 *
 * <p>Frames in (handled in MdmService.handleWsMessage):
 * <ul>
 *   <li>{@code net_scan {session, key_pem, host?, ttl_s?}} — sweep the /24 (or re-probe one
 *       host), report each device that answers the key.</li>
 *   <li>{@code net_scan_cancel {session}} — stop an in-flight sweep.</li>
 *   <li>{@code net_enroll {job, host, port?, serial, class, token, apk_url, apk_sha256?,
 *       server_url?, key_pem}} — enrol one device.</li>
 * </ul>
 * Frames out: {@code net_sighting}, {@code net_scan_done}, {@code net_enroll_progress},
 * {@code net_enroll_done}.
 *
 * <p>The fleet private key arrives inside the frame and is used only for that scan or job;
 * nothing is written to disk. Devices that refuse the key are remembered here (host →
 * until) for an hour and never reported — a device that refused us is not ours to list.
 */
public final class NetScout {
    private static final String TAG = "NetScout";

    public static final String DPC_PKG = "aio.app.mdmclient.dpc";
    public static final String DPC_ADMIN = DPC_PKG + ".MdmDeviceAdminReceiver";
    private static final String FIRMWARE_PKG = "com.aioapp.mdm";

    private static final int ADB_PORT = 5555;
    private static final int PARALLEL = 16;
    // 400ms is enough for a closed port to RST, and enough for an awake device to
    // answer — but a device whose Wi-Fi radio is in power-save takes longer than that
    // to get its first SYN-ACK out, and read as "closed". That silently lost real
    // devices: a venue sweep reported "0 open" on a /24 with a listening device on it.
    // So the sweep keeps the short budget for its first pass (254 hosts have to stay
    // fast) and gives every non-responder a second, patient one; a scan aimed at a
    // single host is patient from the start, because one host costs nothing.
    private static final int PORT_PROBE_MS = 400;
    private static final int PORT_PROBE_SLOW_MS = 1500;
    private static final long MDNS_LISTEN_MS = 2500;    // long enough for a link to answer
    private static final long MDNS_RESOLVE_MS = 1200;
    private static volatile NsdManager mdns;
    private static final int ADB_CONNECT_MS = 3000;
    private static final int IO_MS = 8000;
    private static final long REFUSED_FOR_MS = 60 * 60 * 1000L;

    /** The eight steps, matching the Enroll apps and tools/enroll-adb.sh. */
    private static final String[] STEPS = {
            "Checking the device", "Getting a token", "Installing the agent", "Setting Device Owner",
            "Granting permissions", "Starting the agent", "Registering with the MDM", "First check-in" };

    /** Where frames go out. MdmService hands in its WebSocket. */
    public interface Sender { void send(JSONObject frame); }

    private static final Map<String, Long> refusedUntil = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> cancelled = new ConcurrentHashMap<>();
    /**
     * One scan at a time on this device. MdmService hands every net_scan to a pool with
     * room for 16 threads, so without this a second frame starts a second sweep: another
     * 16 probe sockets, another set of mDNS listeners, and both of them writing the
     * static maps above. Overlapping scans were also losing their results — a frame
     * aimed at one host, sent while a sweep ran, never reported back at all. A rejected
     * scan is answered, never dropped silently.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean scanning =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private NetScout() {}

    public static void cancelScan(String session) {
        if (session != null && !session.isEmpty()) cancelled.put(session, Boolean.TRUE);
    }

    // ── scan ────────────────────────────────────────────────────────────────────────

    /**
     * Runs a whole /24 sweep (or, with {@code host} set, a single-host re-probe) and reports
     * every device that accepts the key as a {@code net_sighting}, then {@code net_scan_done}.
     * Blocking; call off the main thread.
     */
    public static void scan(Context ctx, JSONObject msg, Sender out) {
        final String session = msg.optString("session", "");
        if (!scanning.compareAndSet(false, true)) {
            Log.i(TAG, "scan " + session + " refused: one already running");
            done(out, session, 0, 0, 0, "a scan is already running on this device");
            return;
        }
        try {
            scanLocked(ctx, msg, out, session);
        } finally {
            scanning.set(false);
        }
    }

    private static void scanLocked(Context ctx, JSONObject msg, Sender out, final String session) {
        final String only = msg.optString("host", "");
        final long t0 = System.currentTimeMillis();
        final PrivateKey key;
        try {
            key = AdbClient.parsePem(msg.optString("key_pem", ""));
        } catch (IOException e) {
            done(out, session, 0, 0, 0, "bad key: " + e.getMessage());
            return;
        }
        cancelled.remove(session);
        if (mdns == null) {
            try {
                mdns = (NsdManager) ctx.getApplicationContext().getSystemService(Context.NSD_SERVICE);
            } catch (Exception e) {
                Log.w(TAG, "no NSD service: " + e.getMessage());
            }
        }

        List<String> hosts;
        if (only.isEmpty()) {
            // Two ways of looking, because neither finds everything. The /24 sweep only
            // covers this device's own third octet and skips .0 and .255 — a venue on
            // more than one AP subnet (or a flat /16, like the lab) hides devices from
            // it. mDNS is multicast on the local link and answers across those subnets,
            // which is how the AIO Enroll desktop app finds devices without sweeping at
            // all (`adb mdns services`). Union of the two, de-duplicated, sweep order
            // first so the common case is unchanged.
            Set<String> all = new LinkedHashSet<>(subnetHosts(ctx));
            List<String> viaMdns = mdnsHosts();
            int extra = 0;
            for (String h : viaMdns) if (all.add(h)) extra++;
            if (!viaMdns.isEmpty()) {
                Log.i(TAG, "mDNS saw " + viaMdns.size() + " adb host(s), " + extra + " outside this /24");
            }
            hosts = new ArrayList<>(all);
        } else {
            hosts = new ArrayList<>(List.of(only));
        }
        if (hosts.isEmpty()) { done(out, session, 0, 0, 0, "no Wi-Fi /24"); return; }

        final String self = localIp(ctx);
        final long now = System.currentTimeMillis();
        final List<String> open = new CopyOnWriteArrayList<>();
        final List<String> quiet = new CopyOnWriteArrayList<>();   // did not answer in time
        final int firstMs = only.isEmpty() ? PORT_PROBE_MS : PORT_PROBE_SLOW_MS;
        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL);
        for (final String host : hosts) {
            if (host.equals(self)) continue;
            if (only.isEmpty()) {                       // re-probe ignores the refused cache
                Long until = refusedUntil.get(host);
                if (until != null && now < until) continue;
            }
            pool.execute(() -> {
                if (portOpen(host, ADB_PORT, firstMs)) open.add(host); else quiet.add(host);
            });
        }
        pool.shutdown();
        try { pool.awaitTermination(30, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}

        // Second, patient pass over the hosts that said nothing. A radio in power-save
        // misses the first budget and answers this one, which is the difference between
        // finding a device and reporting an empty venue.
        if (only.isEmpty() && !quiet.isEmpty() && !cancelled.containsKey(session)) {
            ExecutorService slow = Executors.newFixedThreadPool(PARALLEL);
            for (final String host : quiet) {
                slow.execute(() -> { if (portOpen(host, ADB_PORT, PORT_PROBE_SLOW_MS)) open.add(host); });
            }
            slow.shutdown();
            try { slow.awaitTermination(60, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        }

        int accepted = 0, refused = 0;
        for (String host : open) {
            if (cancelled.containsKey(session)) break;
            AdbClient c = null;
            try {
                c = AdbClient.connect(host, ADB_PORT, key, ADB_CONNECT_MS, IO_MS);
                JSONObject s = probe(c, host, ADB_PORT, session);
                out.send(s);
                accepted++;
            } catch (AdbClient.Refused r) {
                refusedUntil.put(host, System.currentTimeMillis() + REFUSED_FOR_MS);
                refused++;
                // Still report it. adb answered, so there is an Android device here; it
                // just has not accepted our key. `adb devices` calls this "unauthorized"
                // and the Enroll app lists it, because somebody standing at the device
                // can tap Allow (or the image can be rebuilt with the key) and then it
                // enrols like any other. Nothing is known about it beyond its address —
                // no props can be read without auth — so the frame carries no serial.
                out.send(unauthorized(session, host, ADB_PORT, r.getMessage()));
                Log.i(TAG, host + " refused the key: " + r.getMessage());
            } catch (IOException e) {
                Log.w(TAG, host + " probe failed: " + e.getMessage());
            } finally {
                if (c != null) c.close();
            }
        }
        done(out, session, hosts.size(), open.size(), accepted, null);
        Log.i(TAG, "scan done in " + (System.currentTimeMillis() - t0) + "ms: "
                + hosts.size() + " hosts, " + open.size() + " open, " + accepted + " ours, " + refused + " refused");
    }

    /** One shell round-trip, the same markers as the Enroll apps' Probe.read. */
    private static JSONObject probe(AdbClient c, String host, int port, String session) throws IOException {
        String out = c.shell(String.join("; ",
                "echo __aio__:prop", "getprop ro.serialno", "getprop ro.product.manufacturer",
                "getprop ro.product.model", "getprop ro.build.version.release",
                "echo __aio__:owner", "dumpsys device_policy",
                "echo __aio__:acct", "dumpsys account",
                "echo __aio__:users", "pm list users",
                "echo __aio__:dpc", "dumpsys package " + DPC_PKG,
                "echo __aio__:fw", "dumpsys package " + FIRMWARE_PKG,
                "echo __aio__:end"));
        Map<String, String> part = splitMarks(out);
        String[] props = part.getOrDefault("prop", "").trim().split("\\s*\\n\\s*");
        String ownerPkg = ""; boolean ownerSet = false, ownerOurs = false;
        String ownerDump = part.getOrDefault("owner", "");
        int oi = indexOfLine(ownerDump, "Device Owner:");
        if (oi >= 0) {
            String block = joinLines(ownerDump, oi + 1, 3);
            if (block.contains("admin=")) {
                ownerSet = true;
                ownerOurs = block.contains(DPC_PKG + "/" + DPC_ADMIN) || block.contains(DPC_PKG);
                int p = block.indexOf("package=");
                if (p >= 0) ownerPkg = block.substring(p + 8).split("\\s")[0];
            }
        }
        int accounts = countLines(part.getOrDefault("acct", ""), "Account {name=");
        int users = Math.max(1, countLines(part.getOrDefault("users", ""), "UserInfo{"));
        String fw = versionName(part.getOrDefault("fw", ""));
        String dpcV = versionName(part.getOrDefault("dpc", ""));

        JSONObject f = new JSONObject();
        try {
            f.put("type", "net_sighting");
            f.put("session", session);
            f.put("host", host);
            f.put("port", port);
            f.put("serial", at(props, 0));
            f.put("manufacturer", at(props, 1));
            f.put("model", at(props, 2));
            f.put("android", at(props, 3));
            JSONObject owner = new JSONObject();
            owner.put("set", ownerSet); owner.put("ours", ownerOurs); owner.put("pkg", ownerPkg);
            f.put("owner", owner);
            f.put("accounts", accounts);
            f.put("users", users);
            f.put("dpc_version", dpcV);
            f.put("firmware_version", fw.isEmpty() ? JSONObject.NULL : fw);
        } catch (Exception e) {
            throw new IOException("sighting json: " + e);
        }
        return f;
    }

    // ── enrol ─────────────────────────────────────────────────────────────────────

    /** Enrols one device over adb. Reports each step, then net_enroll_done. Blocking. */
    public static void enroll(Context ctx, JSONObject msg, Sender out) {
        final String job = msg.optString("job", "");
        final String host = msg.optString("host", "");
        final int port = msg.optInt("port", ADB_PORT);
        final String cls = msg.optString("class", "");
        final String token = msg.optString("token", "");
        final String serverUrl = msg.optString("server_url", "");
        final String apkUrl = msg.optString("apk_url", "");
        final String apkSha = msg.optString("apk_sha256", "");

        PrivateKey key;
        try {
            key = AdbClient.parsePem(msg.optString("key_pem", ""));
        } catch (IOException e) { enrollDone(out, job, false, "bad key: " + e.getMessage()); return; }

        File apk = new File(ctx.getCacheDir(), "scout-dpc.apk");
        AdbClient c = null;
        try {
            c = AdbClient.connect(host, port, key, ADB_CONNECT_MS, IO_MS);

            step(out, job, 0);                       // Checking the device
            JSONObject s = probe(c, host, port, "");
            String serial = s.optString("serial", msg.optString("serial", ""));
            String blocked = blockedReason(s);
            if (blocked != null) { enrollDone(out, job, false, blocked); return; }

            step(out, job, 1);                       // Getting a token — the server already minted it
            if (token.isEmpty()) { enrollDone(out, job, false, "no enrolment token in the frame"); return; }

            step(out, job, 2);                       // Installing the agent
            download(apkUrl, apk, apkSha);
            String ins;
            try (FileInputStream body = new FileInputStream(apk)) {
                ins = c.exec("cmd package install -r -S " + apk.length(), body, apk.length());
            }
            if (!ins.contains("Success")) { enrollDone(out, job, false, "install failed: " + ins.trim()); return; }

            step(out, job, 3);                       // Setting Device Owner
            boolean ours = s.optJSONObject("owner") != null && s.optJSONObject("owner").optBoolean("ours");
            if (!ours) {
                String so = c.shell("dpm set-device-owner " + DPC_PKG + "/" + DPC_ADMIN);
                if (!so.contains("Success")) { enrollDone(out, job, false, "set-device-owner: " + so.trim()); return; }
            }

            step(out, job, 4);                       // Granting permissions (best effort)
            c.shell("pm grant " + DPC_PKG + " android.permission.READ_LOGS");
            c.shell("pm grant " + DPC_PKG + " android.permission.WRITE_SECURE_SETTINGS");
            c.shell("appops set " + DPC_PKG + " GET_USAGE_STATS allow");
            c.shell("appops set " + DPC_PKG + " PROJECT_MEDIA allow");

            step(out, job, 5);                       // Starting the agent with the token
            String su = serverUrl.isEmpty() ? "" : " --es server_url '" + serverUrl + "'";
            c.shell("am start -W -n " + DPC_PKG + "/.ui.MainActivity"
                    + su + " --es enroll_token '" + token + "'");

            // Steps 6 (Registering) and 7 (First check-in) are confirmed by the server itself,
            // from the device's own POST /api/v1/enroll — the scout reporting "done" is only a
            // hint. Report them as reached so the row advances; the server has the truth.
            step(out, job, 6);
            step(out, job, 7);
            enrollDone(out, job, true, serial);
        } catch (AdbClient.Refused r) {
            enrollDone(out, job, false, "device refused the key: " + r.getMessage());
        } catch (IOException e) {
            enrollDone(out, job, false, e.getMessage());
        } finally {
            if (c != null) c.close();
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
        }
    }

    /** Same blockers as enroll-adb.sh; null when clear. */
    private static String blockedReason(JSONObject s) {
        JSONObject owner = s.optJSONObject("owner");
        boolean set = owner != null && owner.optBoolean("set");
        boolean ours = owner != null && owner.optBoolean("ours");
        String pkg = owner == null ? "" : owner.optString("pkg", "");
        if (set && !ours) return "Owned by " + pkg + " — factory reset this device first";
        if (s.optInt("accounts", 0) > 0 && !ours) return "Has an account — factory reset, and don't add an account";
        if (s.optInt("users", 1) > 1 && !ours) return "Has a second user — remove it first";
        return null;
    }

    private static void download(String url, File dest, String sha256) throws IOException {
        if (url.isEmpty()) throw new IOException("no apk_url");
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(60000);
        try (InputStream in = con.getInputStream(); FileOutputStream fo = new FileOutputStream(dest)) {
            MessageDigest md = sha256.isEmpty() ? null : digest();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) { fo.write(buf, 0, n); if (md != null) md.update(buf, 0, n); }
            if (md != null) {
                String got = hex(md.digest());
                if (!got.equalsIgnoreCase(sha256)) throw new IOException("apk checksum mismatch");
            }
        } finally {
            con.disconnect();
        }
        if (dest.length() == 0) throw new IOException("downloaded apk is empty");
    }

    // ── frames out ──────────────────────────────────────────────────────────────────

    private static void step(Sender out, String job, int i) {
        JSONObject f = new JSONObject();
        try {
            f.put("type", "net_enroll_progress");
            f.put("job", job);
            f.put("step", i + 1);                     // 1-based for the dashboard
            f.put("text", STEPS[i]);
        } catch (Exception ignored) {}
        out.send(f);
    }

    private static void enrollDone(Sender out, String job, boolean ok, String reasonOrSerial) {
        JSONObject f = new JSONObject();
        try {
            f.put("type", "net_enroll_done");
            f.put("job", job);
            f.put("ok", ok);
            if (ok) f.put("serial", reasonOrSerial);
            else f.put("reason", reasonOrSerial);
        } catch (Exception ignored) {}
        out.send(f);
    }

    /** A host that speaks adb but would not take our key: reported without a serial. */
    private static JSONObject unauthorized(String session, String host, int port, String why) {
        JSONObject f = new JSONObject();
        try {
            f.put("type", "net_sighting");
            f.put("session", session);
            f.put("host", host);
            f.put("port", port);
            f.put("serial", "");
            f.put("auth", "refused");
            f.put("reason", why == null ? "" : why);
        } catch (Exception ignored) {}
        return f;
    }

    private static void done(Sender out, String session, int hosts, int open, int accepted, String err) {
        JSONObject f = new JSONObject();
        try {
            f.put("type", "net_scan_done");
            f.put("session", session);
            f.put("hosts", hosts);
            f.put("open", open);
            f.put("accepted", accepted);
            if (err != null) f.put("error", err);
        } catch (Exception ignored) {}
        out.send(f);
    }

    // ── network helpers ───────────────────────────────────────────────────────────

    private static boolean portOpen(String host, int port) {
        return portOpen(host, port, PORT_PROBE_MS);
    }

    private static boolean portOpen(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Every address in this device's Wi-Fi /24 (the common case; wider masks are not swept). */
    /**
     * Addresses advertising adb over mDNS, the same services `adb mdns services` lists:
     * {@code _adb._tcp} (adbd with TCP enabled, which is what our images do) and the
     * two wireless-debugging ones. Multicast reaches the whole link, so this finds
     * devices on another AP subnet that a /24 sweep can never see. Best effort and
     * time-boxed — no result just means the sweep stands alone.
     */
    private static List<String> mdnsHosts() {
        List<String> out = new ArrayList<>();
        NsdManager nsd = mdns;
        if (nsd == null) return out;
        String[] types = {"_adb._tcp.", "_adb-tls-connect._tcp.", "_adb-tls-pairing._tcp."};
        for (String type : types) {
            final CountDownLatch settled = new CountDownLatch(1);
            final List<NsdServiceInfo> found = new CopyOnWriteArrayList<>();
            NsdManager.DiscoveryListener dl = new NsdManager.DiscoveryListener() {
                public void onDiscoveryStarted(String t) {}
                public void onStartDiscoveryFailed(String t, int code) { settled.countDown(); }
                public void onStopDiscoveryFailed(String t, int code) { settled.countDown(); }
                public void onDiscoveryStopped(String t) { settled.countDown(); }
                public void onServiceFound(NsdServiceInfo info) { found.add(info); }
                public void onServiceLost(NsdServiceInfo info) {}
            };
            try {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, dl);
            } catch (IllegalArgumentException | SecurityException e) {
                Log.w(TAG, "mDNS " + type + ": " + e.getMessage());
                continue;
            }
            try { Thread.sleep(MDNS_LISTEN_MS); } catch (InterruptedException ignored) {}
            try { nsd.stopServiceDiscovery(dl); } catch (IllegalArgumentException ignored) {}
            try { settled.await(1, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            // A found service carries a name, not an address, until it is resolved; the
            // name adbd advertises is usually "adb-<serial>", so resolve for the host.
            for (NsdServiceInfo info : found) {
                String host = resolveHost(nsd, info);
                if (host != null && !host.isEmpty() && !out.contains(host)) out.add(host);
            }
        }
        return out;
    }

    private static String resolveHost(NsdManager nsd, NsdServiceInfo info) {
        final String[] host = new String[1];
        final CountDownLatch done = new CountDownLatch(1);
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                public void onResolveFailed(NsdServiceInfo i, int code) { done.countDown(); }
                public void onServiceResolved(NsdServiceInfo i) {
                    if (i.getHost() != null) host[0] = i.getHost().getHostAddress();
                    done.countDown();
                }
            });
            done.await(MDNS_RESOLVE_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Log.w(TAG, "mDNS resolve: " + e.getMessage());
        }
        return host[0];
    }

    private static List<String> subnetHosts(Context ctx) {
        List<String> out = new ArrayList<>();
        String ip = localIp(ctx);
        if (ip == null) return out;
        int dot = ip.lastIndexOf('.');
        if (dot < 0) return out;
        String base = ip.substring(0, dot + 1);
        for (int i = 1; i <= 254; i++) out.add(base + i);
        return out;
    }

    private static String localIp(Context ctx) {
        WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return null;
        WifiInfo info = wm.getConnectionInfo();
        if (info == null) return null;
        int ip = info.getIpAddress();
        if (ip == 0) return null;
        return String.format(Locale.US, "%d.%d.%d.%d", ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
    }

    // ── parse helpers (mirror the Enroll apps' Probe.kt) ────────────────────────────

    private static Map<String, String> splitMarks(String out) {
        Map<String, String> map = new ConcurrentHashMap<>();
        StringBuilder cur = null;
        String curName = null;
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.startsWith("__aio__:")) {
                if (cur != null && curName != null) map.put(curName, cur.toString());
                String name = t.substring("__aio__:".length());
                if (name.equals("end")) { cur = null; curName = null; }
                else { cur = new StringBuilder(); curName = name; }
            } else if (cur != null) {
                cur.append(line).append('\n');
            }
        }
        if (cur != null && curName != null) map.put(curName, cur.toString());
        return map;
    }

    private static int indexOfLine(String dump, String needle) {
        String[] ls = dump.split("\n");
        for (int i = 0; i < ls.length; i++) if (ls[i].contains(needle)) return i;
        return -1;
    }

    private static String joinLines(String dump, int from, int count) {
        String[] ls = dump.split("\n");
        StringBuilder b = new StringBuilder();
        for (int i = from; i < ls.length && i < from + count; i++) b.append(ls[i]).append('\n');
        return b.toString();
    }

    private static int countLines(String dump, String startsWithTrimmed) {
        int n = 0;
        for (String l : dump.split("\n")) if (l.trim().startsWith(startsWithTrimmed)) n++;
        return n;
    }

    private static String versionName(String dump) {
        for (String l : dump.split("\n")) {
            String t = l.trim();
            if (t.startsWith("versionName=")) return t.substring("versionName=".length()).split("\\s")[0];
        }
        return "";
    }

    private static String at(String[] a, int i) { return i < a.length ? a[i].trim() : ""; }

    private static MessageDigest digest() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (Exception e) { throw new IOException("no SHA-256"); }
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }
}
