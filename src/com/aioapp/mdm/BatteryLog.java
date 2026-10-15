package com.aioapp.mdm;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Raw battery log — data to design the battery-health model from.
 *
 * The T7's gauge cannot report wear (see BatteryWear), so health has to be inferred from
 * how the pack behaves: how fast the voltage climbs on charge and falls on discharge,
 * against screen, temperature, charge current and the guest pad. Which of those signals
 * holds up is not known yet, so this keeps every battery broadcast and screen edge as a
 * CSV row and uploads the rows to the server (POST /api/v1/battery-log), where the model is
 * built from a few weeks of real data.
 *
 * One file per local day under files/battery/ (yyyy-MM-dd.csv, header first), the last
 * {@link #KEEP_DAYS} kept. Upload is by cursor (day + byte offset of the next row not yet
 * accepted, in files/battery/upload.cursor): a server that does not take the rows yet
 * leaves them on the device, and the backlog goes up once it does. A day is also small
 * enough (~1–2k rows) to pull by hand over the MDM shell:
 * {@code cat /data/user/0/com.aioapp.mdm/files/battery/2026-10-16.csv}.
 *
 * Rows whose state key (status, plug, screen, pad) is unchanged are kept at most one per
 * {@link #MIN_GAP_MS}: healthd re-broadcasts on every reading change, and the analysis
 * needs the curve, not every millivolt of it. Writes run on one background thread, in
 * order. Plain Java, no Android types, so it can be tested off-device.
 */
final class BatteryLog {
    static final int KEEP_DAYS = 21;
    static final long MIN_GAP_MS = 30_000L;
    static final int SCHEMA = 1;
    static final String HEADER =
            "t_ms,up_s,ev,mv,pct,status,plug,temp_c,screen,ichg_ma,iin_ma,flap,wlc,wlc_on";
    private static final String CURSOR_FILE = "upload.cursor";

    /** Rows after the upload cursor, and where the cursor moves once they are accepted. */
    static final class Batch {
        final String rows;      // CSV lines, each ending in \n, no header
        final int count;
        final String endDay;
        final long endOffset;

        Batch(String rows, int count, String endDay, long endOffset) {
            this.rows = rows;
            this.count = count;
            this.endDay = endDay;
            this.endOffset = endOffset;
        }
    }

    private final File dir;
    private final ExecutorService writer;
    private final SimpleDateFormat dayFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private String lastKey;
    private long lastAt = Long.MIN_VALUE / 2;
    private String openDay;

    BatteryLog(File dir) {
        this(dir, Executors.newSingleThreadExecutor());
    }

    BatteryLog(File dir, ExecutorService writer) {
        this.dir = dir;
        this.writer = writer;
    }

    /**
     * One row. ev is b (battery broadcast) or s (screen edge); key is the state that, when
     * it changes, bypasses the rate limit. Fields are written as given, comma-joined.
     */
    synchronized void log(long wallMs, long upMs, String ev, String key, Object... fields) {
        if (key.equals(lastKey) && upMs - lastAt < MIN_GAP_MS) return;
        lastKey = key;
        lastAt = upMs;
        StringBuilder row = new StringBuilder(96)
                .append(wallMs).append(',').append(upMs / 1000).append(',').append(ev);
        for (Object f : fields) row.append(',').append(f);
        row.append('\n');
        String line = row.toString();
        try {
            writer.execute(() -> append(wallMs, line));
        } catch (RejectedExecutionException ignored) {
            // A broadcast landing after shutdown(); the service is going away.
        }
    }

    void shutdown() {
        writer.shutdown();
    }

    /**
     * Up to maxRows complete rows after the upload cursor, oldest first; null when there
     * are none. A cursor on a day already pruned restarts at the oldest day kept. Safe
     * alongside the writer: only lines already ending in a newline are taken. peek and ack
     * are for one uploader at a time, and take no lock, so file reads never hold up log().
     */
    Batch peek(int maxRows) {
        String[] names = dayFiles();
        if (names == null) return null;
        String[] cur = readCursor();
        String curDay = cur != null ? cur[0] : null;
        long curOff = cur != null ? Long.parseLong(cur[1]) : 0;
        StringBuilder rows = new StringBuilder();
        int count = 0;
        String endDay = curDay;
        long endOff = curOff;
        for (String name : names) {
            String day = name.substring(0, name.length() - 4);
            if (curDay != null && day.compareTo(curDay) < 0) continue;
            long start = day.equals(curDay) ? curOff : 0;
            byte[] data;
            try (RandomAccessFile f = new RandomAccessFile(new File(dir, name), "r")) {
                long len = f.length();
                if (start >= len) continue;
                data = new byte[(int) (len - start)];
                f.seek(start);
                f.readFully(data);
            } catch (IOException e) {
                break;
            }
            int lineStart = 0;
            for (int i = 0; i < data.length && count < maxRows; i++) {
                if (data[i] != '\n') continue;
                String line = new String(data, lineStart, i - lineStart, StandardCharsets.US_ASCII);
                lineStart = i + 1;
                endDay = day;
                endOff = start + lineStart;
                if (line.equals(HEADER) || line.isEmpty()) continue;
                rows.append(line).append('\n');
                count++;
            }
            if (count >= maxRows) break;
        }
        return count == 0 ? null : new Batch(rows.toString(), count, endDay, endOff);
    }

    /** The server accepted batch: move the cursor past it. */
    void ack(Batch batch) {
        File tmp = new File(dir, CURSOR_FILE + ".tmp");
        try {
            Files.write(tmp.toPath(), (batch.endDay + " " + batch.endOffset)
                    .getBytes(StandardCharsets.US_ASCII));
            if (!tmp.renameTo(new File(dir, CURSOR_FILE))) tmp.delete();
        } catch (IOException ignored) {
            // Cursor not moved: the batch goes up again and the server dedupes it.
        }
    }

    private String[] readCursor() {
        try {
            String s = new String(Files.readAllBytes(new File(dir, CURSOR_FILE).toPath()),
                    StandardCharsets.US_ASCII).trim();
            String[] parts = s.split(" ");
            if (parts.length != 2) return null;
            Long.parseLong(parts[1]);
            return parts;
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    /** Day files, oldest first (names sort by date); null when the directory is missing. */
    private String[] dayFiles() {
        String[] names = dir.list((d, n) -> n.endsWith(".csv"));
        if (names != null) Arrays.sort(names);
        return names;
    }

    // Writer thread only.
    private void append(long wallMs, String line) {
        String day = dayFormat.format(new Date(wallMs));
        File f = new File(dir, day + ".csv");
        try {
            boolean newDay = !day.equals(openDay);
            if (newDay && !dir.isDirectory() && !dir.mkdirs()) return;
            boolean fresh = !f.exists();
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                if (fresh) out.write((HEADER + "\n").getBytes(StandardCharsets.US_ASCII));
                out.write(line.getBytes(StandardCharsets.US_ASCII));
            }
            if (newDay) {
                openDay = day;
                prune();
            }
        } catch (IOException ignored) {
            // A lost row is a gap in the curve, nothing more.
        }
    }

    /** Keeps the newest KEEP_DAYS day files. */
    private void prune() {
        String[] names = dayFiles();
        if (names == null || names.length <= KEEP_DAYS) return;
        for (int i = 0; i < names.length - KEEP_DAYS; i++) {
            new File(dir, names[i]).delete();
        }
    }
}
