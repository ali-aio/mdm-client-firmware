package com.aioapp.mdm;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Save to library (1.21.0, pull_apk): upload an installed app's APK into the MDM's app
 * library, by hand from the device page or when the server sees this device run a newer
 * build of one of our apps than the library holds (every channel: aio.app.nugget,
 * .uatv2, .stagev2… are separate packages, each pulled at its newest version).
 *
 * Same protocol as the Standard client (0.11.0): the server hands out a presigned S3 URL
 * for this one command, the file goes there directly (no device key goes with it), and
 * the server then reads it and adds it to the library, checking the package and that it
 * is signed like the versions it already has. An app installed as split APKs can't be
 * saved: the library holds single APKs.
 */
final class ApkPuller {
    private static final String TAG = "ApkPuller";
    private static final Pattern S3_CODE = Pattern.compile("<Code>([^<]+)</Code>");

    interface Progress { void on(int percent); }

    static final class Result {
        final boolean ok; final String message;
        Result(boolean ok, String message) { this.ok = ok; this.message = message; }
    }

    private ApkPuller() {}

    static Result pull(Context ctx, MdmApiService api, String serial, String commandId, String pkg, Progress progress) {
        if (pkg == null || pkg.isEmpty()) return new Result(false, "no package named");
        ApplicationInfo info;
        try {
            info = ctx.getPackageManager().getApplicationInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return new Result(false, pkg + " is not installed");
        }
        if (info.splitSourceDirs != null && info.splitSourceDirs.length > 0) {
            return new Result(false, pkg + " is installed as split APKs (an app bundle); only a single APK can be saved");
        }
        File file = new File(info.sourceDir == null ? "" : info.sourceDir);
        if (!file.canRead()) return new Result(false, "can't read the APK of " + pkg);

        try {
            JSONObject ask = new JSONObject().put("serial_number", serial).put("command_id", commandId);
            MdmApiService.Answer url = api.postForAnswer("/api/v1/apk-pull/url", ask, 30_000);
            if (url.body == null) return new Result(false, "no upload URL: " + url.error);
            String ct = url.body.optString("content_type", "");
            if (ct.isEmpty()) ct = "application/vnd.android.package-archive";
            String put = putFile(url.body.optString("url", ""), file, ct, progress);
            if (!put.isEmpty()) return new Result(false, put);
            // Reading and checking the upload takes the server a moment: wait longer than usual.
            ask.put("key", url.body.optString("key", ""));
            MdmApiService.Answer done = api.postForAnswer("/api/v1/apk-pull/done", ask, 180_000);
            if (done.body == null) return new Result(false, done.error);
            String res = done.body.optString("result", "");
            return new Result(true, res.isEmpty() ? "saved" : res);
        } catch (Exception e) {
            Log.w(TAG, "pull " + pkg + ": " + e);
            return new Result(false, String.valueOf(e.getMessage()));
        }
    }

    /** PUT the file to a presigned URL, reporting 0-100. Returns "" on success or why it failed. */
    private static String putFile(String target, File file, String contentType, Progress progress) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(target).openConnection();
            conn.setRequestMethod("PUT");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", contentType);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(120_000);
            long total = file.length();
            conn.setFixedLengthStreamingMode(total);
            try (InputStream in = new FileInputStream(file); OutputStream out = conn.getOutputStream()) {
                byte[] buf = new byte[64 * 1024];
                long sent = 0;
                int last = -1, n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    sent += n;
                    int pct = total > 0 ? (int) (sent * 100 / total) : 0;
                    if (pct != last) { last = pct; progress.on(pct); }
                }
            }
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) return "";
            // S3 says why in an XML body: <Code>RequestTimeout</Code> and so on.
            String body = "";
            InputStream err = conn.getErrorStream();
            if (err != null) {
                try (InputStream e = err) {
                    byte[] b = new byte[8192];
                    int r = e.read(b);
                    if (r > 0) body = new String(b, 0, r, StandardCharsets.UTF_8);
                }
            }
            Matcher m = S3_CODE.matcher(body);
            return "upload refused: HTTP " + code + (m.find() ? " (" + m.group(1) + ")" : "");
        } catch (Exception e) {
            return "upload failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
