package com.aioapp.mdm;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

/**
 * OTA for this client itself — a new build of the MDM system app installed over the
 * running one, without a firmware image.
 *
 * This works because the app is not an ordinary app: it holds INSTALL_PACKAGES and runs
 * as android.uid.system, so it can drive PackageInstaller with no prompt. Android keeps
 * the update in /data/app as an "updated system app", which retains the shared system
 * uid, the privileged flag and the sysconfig entry — all of them keyed to the package
 * name, which must therefore never change. A factory reset drops the update and reverts
 * to the build baked into the image, which is the escape hatch if one ever goes bad.
 *
 * Committing the install kills this process, so the command cannot be acknowledged from
 * the install path. The intent is written down first ({@link #recordPending}) and settled
 * by the version that replaces it ({@link #settlePending}), which compares the running
 * version with the target.
 */
final class ClientUpdater {

    private static final String TAG = "MdmClient";
    private static final String PREFS = "mdm_client_update";
    private static final String KEY_PENDING = "pending";

    private ClientUpdater() {}

    /**
     * Prefix on the reason verify() returns when the APK is a genuine build of this app
     * that simply is not newer than the one running. The update has nothing to do — it is
     * not a failure, and callers distinguish the two with {@link #notNewer} rather than
     * matching on prose. Pushing an update at a device already on that build is a normal
     * thing for an operator to do (the Clients page offers it per device), and reporting
     * it as failed made an up-to-date device look broken.
     */
    static final String ALREADY_CURRENT = "already on ";

    /** True when verify() refused the APK only because the device is already on it. */
    static boolean notNewer(String why) {
        return why != null && why.startsWith(ALREADY_CURRENT);
    }

    /**
     * Is this APK a genuine newer build of this same app? Returns null when it is safe to
     * install, otherwise the reason it was refused. Android would reject a mismatched
     * signature or package anyway; checking here fails early and says why, and the version
     * check is ours alone — installing an older build would be a silent downgrade.
     */
    static String verify(Context ctx, File apk, String expectedSha256) {
        if (expectedSha256 != null && !expectedSha256.isEmpty()) {
            String got = sha256(apk);
            if (got == null) return "could not hash the download";
            if (!got.equalsIgnoreCase(expectedSha256)) return "checksum mismatch";
        }
        PackageManager pm = ctx.getPackageManager();
        PackageInfo fresh = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                PackageManager.GET_SIGNING_CERTIFICATES);
        if (fresh == null) return "not a valid APK";
        if (!ctx.getPackageName().equals(fresh.packageName)) {
            return "APK is " + fresh.packageName + ", not this client (" + ctx.getPackageName() + ")";
        }
        PackageInfo cur;
        try {
            cur = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        } catch (Exception e) {
            return "could not read the installed version: " + e.getMessage();
        }
        if (versionCode(fresh) <= versionCode(cur)) {
            return ALREADY_CURRENT + (fresh.versionName == null ? String.valueOf(versionCode(fresh))
                    : fresh.versionName) + " (" + versionCode(fresh) + "); this device runs "
                    + cur.versionName + " (" + versionCode(cur) + ")";
        }
        // The platform key differs per build variant (user vs userdebug), so a wrong-variant
        // APK is the likely mistake here, not a hostile one. Name it plainly.
        Set<String> a = signers(fresh), b = signers(cur);
        a.retainAll(b);
        if (a.isEmpty()) {
            return "APK is signed with a different key — wrong build variant for this device?";
        }
        return null;
    }

    /** Remember what we are about to install, before the install ends this process. */
    static void recordPending(Context ctx, String cmdId, File apk) {
        PackageInfo fresh = ctx.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        long target = fresh == null ? 0 : versionCode(fresh);
        String name = fresh == null || fresh.versionName == null ? "" : fresh.versionName;
        prefs(ctx).edit().putString(KEY_PENDING, cmdId + "|" + target + "|" + name).apply();
        Log.i(TAG, "self-update " + cmdId + " -> " + name + " (" + target + ")");
    }

    /** Forget a pending update that never got as far as the install. */
    static void clearPending(Context ctx) {
        prefs(ctx).edit().remove(KEY_PENDING).apply();
    }

    /**
     * At startup: settle an update the previous version asked for. Running the target
     * version (or newer) means it went in; anything else means it did not, and the
     * operator is told rather than watching the command hang forever.
     */
    static void settlePending(Context ctx, Settler settler) {
        String pending = prefs(ctx).getString(KEY_PENDING, "");
        if (pending == null || pending.isEmpty()) return;
        String[] parts = pending.split("\\|", -1);
        String cmdId = parts.length > 0 ? parts[0] : "";
        if (cmdId.isEmpty()) {
            clearPending(ctx);
            return;
        }
        long target = 0;
        try {
            target = parts.length > 1 ? Long.parseLong(parts[1]) : 0;
        } catch (NumberFormatException ignored) {}
        String name = parts.length > 2 ? parts[2] : "";
        long now = 0;
        try {
            now = versionCode(ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0));
        } catch (Exception ignored) {}
        clearPending(ctx);
        if (now >= target) {
            settler.settle(cmdId, "completed", "updated to " + (name.isEmpty() ? String.valueOf(target) : name)
                    + " (" + now + ")");
        } else {
            settler.settle(cmdId, "failed", "still running " + now + " after installing " + target);
        }
    }

    /** How the settled result gets back to the server (MdmService's terminal ack). */
    interface Settler {
        void settle(String cmdId, String status, String output);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @SuppressWarnings("deprecation")
    private static long versionCode(PackageInfo pi) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? pi.getLongVersionCode() : pi.versionCode;
    }

    /** Every certificate the package is signed with, including its rotation lineage. */
    private static Set<String> signers(PackageInfo pi) {
        Set<String> out = new HashSet<>();
        if (pi.signingInfo == null) return out;
        Signature[] current = pi.signingInfo.getApkContentsSigners();
        Signature[] history = pi.signingInfo.getSigningCertificateHistory();
        for (Signature[] set : new Signature[][]{current, history}) {
            if (set == null) continue;
            for (Signature s : set) {
                String d = digest(s.toByteArray());
                if (d != null) out.add(d);
            }
        }
        return out;
    }

    private static String sha256(File f) {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            return hex(md.digest());
        } catch (Exception e) {
            return null;
        }
    }

    private static String digest(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            return null;
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
