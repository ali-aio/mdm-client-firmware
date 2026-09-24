package com.aioapp.mdm;

import java.util.ArrayDeque;

/**
 * A charger that toggles on and off every second or two — a bad cable, port or supply.
 *
 * Every toggle is a charging edge, and every edge used to push a telemetry frame at once,
 * which the server stores at once (a state change skips its sample window by design). One
 * T7 with a faulty charger sent ~2,400 frames and wrote ~2,400 history rows in an hour,
 * more than half the fleet's rows (AT070AABU00509, 24 Sep 2026).
 *
 * So the client debounces: past {@link #ENTER_FLIPS} toggles inside {@link #WINDOW_MS} the
 * charger is flapping. While it is, the reported charging / charger_type are held at the
 * values they had going in (so no gated key changes and no frame is sent per toggle), and
 * charger_flapping=true says so instead — once. The flap ends after {@link #SETTLE_MS}
 * without a toggle, and one frame then reports the settled state. charger_flaps_5m keeps
 * the rate visible, so the dashboard's faulty-charger alert still fires for a device that
 * no longer sends the toggles themselves. The server applies the same thresholds to
 * clients that predate this (MDM server, internal/db/charger_flap.go).
 */
final class ChargerFlap {
    static final int ENTER_FLIPS = 6;
    static final long WINDOW_MS = 60_000L;
    static final long SETTLE_MS = 120_000L;
    private static final long COUNT_MS = 5 * 60_000L;

    private final ArrayDeque<Long> flips = new ArrayDeque<>(); // elapsedRealtime of each toggle
    private boolean flapping;
    private long lastFlipAt;
    private boolean heldCharging;
    private String heldType = "unknown";

    /** Records one toggle; true when this toggle is the one that starts a flap. */
    synchronized boolean onFlip(long now, boolean chargingBefore, String typeBefore) {
        flips.addLast(now);
        lastFlipAt = now;
        prune(now);
        if (flapping) return false;
        int recent = 0;
        for (long t : flips) if (now - t <= WINDOW_MS) recent++;
        if (recent < ENTER_FLIPS) return false;
        flapping = true;
        heldCharging = chargingBefore;
        heldType = typeBefore != null ? typeBefore : "unknown";
        return true;
    }

    /** Ends the flap once the charger has been quiet for SETTLE_MS; true when it just ended. */
    synchronized boolean settle(long now) {
        if (!flapping || now - lastFlipAt < SETTLE_MS) return false;
        flapping = false;
        return true;
    }

    synchronized boolean isFlapping() { return flapping; }

    synchronized boolean heldCharging() { return heldCharging; }

    synchronized String heldType() { return heldType; }

    /** Toggles in the last five minutes — the rate the server's flap alert compares. */
    synchronized int flipsIn5m(long now) {
        prune(now);
        return flips.size();
    }

    private void prune(long now) {
        while (!flips.isEmpty() && now - flips.peekFirst() > COUNT_MS) flips.removeFirst();
    }
}
