package com.aioapp.mdm;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Readings kept while the MDM can't be reached (1.7.0; plan: aio-mdm-web
 * static/offline-queue-plan.html). While the device is out of contact it adds a reading
 * every few minutes; when it can reach the server again it sends them, oldest first, to
 * /api/v1/checkins/backfill and deletes only what the server answered for. The server
 * stores them as late history (no alerts) and the device page shades that time.
 *
 * One JSON reading per line in a file in the client's device-protected storage, so it
 * survives a reboot and a client update. Capped at 7 days of readings or 2 MB; when full,
 * the oldest go first and are counted, so the server can say "no data" for that time
 * rather than pretend nothing happened.
 */
final class OfflineQueue {
    private static final String TAG = "OfflineQueue";
    static final int MAX_READINGS = 7 * 24 * 12; // 7 days at one reading every 5 minutes
    private static final long MAX_BYTES = 2L * 1024 * 1024;

    private final File file;
    private final SharedPreferences prefs;

    OfflineQueue(Context ctx) {
        Context de = ctx.createDeviceProtectedStorageContext();
        file = new File(de.getFilesDir(), "offline_queue.jsonl");
        prefs = de.getSharedPreferences("mdm_offline_queue", Context.MODE_PRIVATE);
    }

    synchronized void add(JSONObject reading) {
        List<String> lines = read();
        lines.add(reading.toString());
        long bytes = 0;
        for (String l : lines) bytes += l.length() + 1;
        int drop = 0;
        while (lines.size() > MAX_READINGS || (bytes > MAX_BYTES && lines.size() > 1)) {
            bytes -= lines.remove(0).length() + 1;
            drop++;
        }
        write(lines);
        if (drop > 0) {
            prefs.edit().putInt("dropped", dropped() + drop).apply();
            Log.w(TAG, "queue full: dropped the oldest " + drop + " reading(s)");
        }
    }

    synchronized List<String> peek(int n) {
        List<String> lines = read();
        return new ArrayList<>(lines.subList(0, Math.min(n, lines.size())));
    }

    /** The server answered for the first n readings: forget them. */
    synchronized void remove(int n) {
        List<String> lines = read();
        write(new ArrayList<>(lines.subList(Math.min(n, lines.size()), lines.size())));
    }

    synchronized int size() { return read().size(); }

    int dropped() { return prefs.getInt("dropped", 0); }

    void clearDropped() { prefs.edit().putInt("dropped", 0).apply(); }

    private List<String> read() {
        List<String> out = new ArrayList<>();
        if (!file.exists()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isEmpty()) out.add(line);
            }
        } catch (Exception e) {
            Log.w(TAG, "read failed: " + e.getMessage());
        }
        return out;
    }

    /** Written to a temporary file and renamed, so a crash mid-write can't leave half a queue. */
    private void write(List<String> lines) {
        File tmp = new File(file.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            for (String l : lines) {
                w.write(l);
                w.write('\n');
            }
        } catch (Exception e) {
            Log.w(TAG, "write failed: " + e.getMessage());
            return;
        }
        if (!tmp.renameTo(file)) Log.w(TAG, "could not replace the queue file");
    }
}
