package com.aioapp.mdm;

import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Split APK bundles (1.22.4, 4 Nov 2026): an .xapk (APKPure), .apks (bundletool, SAI) or
 * .apkm (APKMirror) is a ZIP of an app's APKs: the base, config splits (screen density,
 * language, CPU type) and feature splits. The server hands the file over as it is; this
 * picks the parts to install in one session, and the base for the notice.
 */
final class ApkBundle {
    private static final Pattern ABI_SPLIT = Pattern.compile(
            "^(?:split_)?config\\.(arm64_v8a|armeabi_v7a|armeabi|x86_64|x86|mips64|mips)\\.apk$",
            Pattern.CASE_INSENSITIVE);

    private ApkBundle() {}

    /** Not an APK, nor a bundle this client can install. */
    static final class NotInstallable extends Exception {
        NotInstallable(String why) { super(why); }
    }

    /**
     * The entries to install, base first, or null when the file is a plain APK. Every APK at
     * the top of the ZIP goes in, except CPU-type splits for other CPUs: of those only the
     * device's best one. A bundle with expansion files (OBB) is refused.
     */
    static List<String> parts(File file) throws NotInstallable {
        List<String> names = new ArrayList<>();
        Map<String, Long> sizes = new HashMap<>();
        String wantBase = null;
        try (ZipFile z = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                names.add(e.getName());
                sizes.put(e.getName(), e.getSize());
                if ("manifest.json".equalsIgnoreCase(e.getName()) && e.getSize() < (1 << 20)) {
                    try (InputStream in = z.getInputStream(e)) {
                        String pkg = new org.json.JSONObject(new String(readAll(in), "UTF-8")).optString("package_name", "");
                        if (!pkg.isEmpty()) wantBase = pkg + ".apk";
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (java.io.IOException e) {
            throw new NotInstallable("not an APK or an APK bundle: " + e.getMessage());
        }
        if (names.contains("AndroidManifest.xml")) return null;
        List<String> apks = new ArrayList<>();
        for (String n : names) {
            String low = n.toLowerCase(Locale.ROOT);
            if (low.startsWith("android/obb/") || low.endsWith(".obb")) {
                throw new NotInstallable("the bundle carries expansion files (OBB), which the MDM can't install");
            }
            if (low.endsWith(".apk") && n.indexOf('/') < 0) apks.add(n);
        }
        if (apks.isEmpty()) throw new NotInstallable("no APKs inside: is it a split APK bundle?");
        // CPU-type splits: keep the device's best one only.
        Map<String, String> abiOf = new HashMap<>();
        Set<String> have = new HashSet<>();
        for (String n : apks) {
            Matcher m = ABI_SPLIT.matcher(n);
            if (m.find()) {
                String abi = m.group(1).toLowerCase(Locale.ROOT);
                abiOf.put(n, abi);
                have.add(abi);
            }
        }
        String best = null;
        if (!have.isEmpty()) {
            for (String a : Build.SUPPORTED_ABIS) {
                String k = a.replace('-', '_').toLowerCase(Locale.ROOT);
                if (have.contains(k)) { best = k; break; }
            }
            if (best == null) {
                throw new NotInstallable("the bundle has no build for this device's CPU (" + String.join(", ", Build.SUPPORTED_ABIS) + ")");
            }
        }
        List<String> out = new ArrayList<>();
        String base = null;
        for (String n : apks) if (n.equals(wantBase) || (base == null && n.equals("base.apk"))) base = n;
        if (base == null) {
            long biggest = -1;
            for (String n : apks) {
                String low = n.toLowerCase(Locale.ROOT);
                if (low.startsWith("config.") || low.startsWith("split_config.")) continue;
                long s = sizes.get(n) == null ? 0 : sizes.get(n);
                if (s > biggest) { biggest = s; base = n; }
            }
        }
        if (base == null) throw new NotInstallable("no base APK inside");
        out.add(base);
        for (String n : apks) {
            if (n.equals(base)) continue;
            String abi = abiOf.get(n);
            if (abi != null && !abi.equals(best)) continue;
            out.add(n);
        }
        return out;
    }

    /** Copies one entry out of the bundle (the base, for its name and icon). */
    static void extract(File bundle, String entry, File to) throws java.io.IOException {
        try (ZipFile z = new ZipFile(bundle);
             InputStream in = z.getInputStream(z.getEntry(entry));
             OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    /** The size of one entry. */
    static long sizeOf(ZipFile z, String entry) {
        ZipEntry e = z.getEntry(entry);
        return e == null ? -1 : e.getSize();
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) b.write(buf, 0, n);
        return b.toByteArray();
    }
}
