package com.aioapp.mdm;

/**
 * Battery wear from the charge curve — the only capacity signal the T7 has.
 *
 * The T7's gauge (CellWise CW2015) is voltage-only: no sense resistor, so no coulomb count,
 * no charge_full and no cycle count, and the charger (SGM41513) has no ADC — the "current"
 * healthd reports is the charger's programmed charge current (ICHG), not a measurement. So
 * Android's health / cycle-count fields are meaningless here, which is why the server
 * retired its battery-health alerts.
 *
 * What does hold is the constant-current phase: the charger pushes ICHG into the pack until
 * the cell reaches its regulation voltage (4.35 V), so the time the cell takes to climb a
 * fixed voltage span is proportional to the charge that span holds. A worn pack holds less
 * and climbs faster. Voltage under charge sits I·R above the resting voltage; that offset is
 * near-equal at both ends of a span, so it mostly cancels out of the time.
 *
 * While charging, this times the climb across a ladder of rungs ({@link #RUNG0_MV} up in
 * {@link #RUNG_STEP_MV} steps, all below regulation so the whole ladder is CC) and returns
 * each finished climb. The server compares a device against its own history and against
 * new units (charge ≈ seconds × ICHG, less the system's own draw). Cumulative screen-on
 * time at each rung rides along so the server can keep screen-off spans only.
 *
 * Fed from ACTION_BATTERY_CHANGED, whose arrival is the sample clock: each crossing is
 * interpolated between the two samples either side, and the widest bracketing gap is
 * reported so a sparse climb can be discounted. A climb starts only below a rung (a pack
 * plugged in above the top rung yields nothing), ends at the top rung, on unplug, or when
 * charging stops (thermal suspend or full), and is kept when it crossed at least two rungs.
 * A flapping charger voids it. Plain Java, no Android types, so it can be tested off-device.
 */
final class BatteryWear {
    static final int RUNG0_MV = 3700;
    static final int RUNG_STEP_MV = 100;
    static final int RUNGS = 5;                 // 3700, 3800, 3900, 4000, 4100 mV

    static final String END_TOP = "top";
    static final String END_UNPLUG = "unplug";
    static final String END_STOP = "stop";

    /** One finished climb. Times are seconds from the first rung crossed. */
    static final class Climb {
        final int firstRungMv;
        final int[] sec;           // per rung crossed; sec[0] == 0
        final int[] screenSec;     // cumulative screen-on seconds at each rung crossed
        final String end;
        final int ichgMinMa, ichgMaxMa;   // -1 when healthd gave none
        final int inputLimitMa;           // -1 when unknown
        final String plug;
        final double tempMinC, tempMaxC;
        final int maxGapSec;
        final int samples;

        Climb(int firstRungMv, int[] sec, int[] screenSec, String end, int ichgMinMa,
                int ichgMaxMa, int inputLimitMa, String plug, double tempMinC,
                double tempMaxC, int maxGapSec, int samples) {
            this.firstRungMv = firstRungMv;
            this.sec = sec;
            this.screenSec = screenSec;
            this.end = end;
            this.ichgMinMa = ichgMinMa;
            this.ichgMaxMa = ichgMaxMa;
            this.inputLimitMa = inputLimitMa;
            this.plug = plug;
            this.tempMinC = tempMinC;
            this.tempMaxC = tempMaxC;
            this.maxGapSec = maxGapSec;
            this.samples = samples;
        }
    }

    private boolean tracking;      // charging, with a previous sample to interpolate from
    private long prevAt;
    private int prevMv;
    private int nextRung;          // index of the next rung to cross; RUNGS when done
    private int firstRung = -1;    // index of the first rung crossed, -1 before any
    private final long[] crossAt = new long[RUNGS];
    private final long[] screenAt = new long[RUNGS];

    private boolean screenOn;
    private long screenMark;       // when screenOnMs was last brought up to date
    private long screenOnMs;       // screen-on time since tracking started

    private int ichgMin, ichgMax, inputLimit;
    private String plug;
    private double tempMin, tempMax;
    private long maxGap;
    private int samples;

    BatteryWear(long now, boolean screenOn) {
        this.screenOn = screenOn;
        this.screenMark = now;
    }

    /** Screen on/off edge — keeps the screen-on clock exact between battery samples. */
    synchronized void onScreen(long now, boolean on) {
        advanceScreen(now);
        screenOn = on;
    }

    /**
     * One battery sample. Returns a climb when this sample finished one worth keeping.
     *
     * @param charging    plugged in and status CHARGING (not FULL, not NOT_CHARGING)
     * @param pluggedIn   any power source attached — tells unplug from a charge stop
     * @param ichgUa      healthd's current_now (ICHG on the T7); <= 0 or MIN_VALUE when absent
     * @param inputLimitUa healthd's max_charging_current; <= 0 when absent
     */
    synchronized Climb onSample(long now, boolean charging, boolean pluggedIn, boolean flapping,
            int mv, double tempC, int ichgUa, int inputLimitUa, String plugType) {
        advanceScreen(now);
        if (flapping) {
            reset();
            return null;
        }
        if (!charging) {
            Climb c = tracking ? finish(pluggedIn ? END_STOP : END_UNPLUG) : null;
            reset();
            return c;
        }
        if (mv <= 0) return null;
        if (!tracking) {
            tracking = true;
            prevAt = now;
            prevMv = mv;
            nextRung = 0;
            while (nextRung < RUNGS && mv >= rungMv(nextRung)) nextRung++;
            return null;
        }
        while (nextRung < RUNGS && mv >= rungMv(nextRung)) {
            long at = interpolate(prevAt, prevMv, now, mv, rungMv(nextRung));
            if (firstRung < 0) {
                firstRung = nextRung;
                ichgMin = ichgMax = inputLimit = -1;
                tempMin = Double.MAX_VALUE;
                tempMax = -Double.MAX_VALUE;
                maxGap = 0;
                samples = 0;
            }
            crossAt[nextRung] = at;
            screenAt[nextRung] = screenOnMsAt(at, now);
            maxGap = Math.max(maxGap, now - prevAt);
            nextRung++;
        }
        if (firstRung >= 0) {
            samples++;
            int ichgMa = ichgUa == Integer.MIN_VALUE || ichgUa == 0 ? -1 : Math.abs(ichgUa) / 1000;
            if (ichgMa > 0) {
                ichgMin = ichgMin < 0 ? ichgMa : Math.min(ichgMin, ichgMa);
                ichgMax = Math.max(ichgMax, ichgMa);
            }
            if (inputLimitUa > 0) inputLimit = inputLimitUa / 1000;
            if (tempC > -100) {
                tempMin = Math.min(tempMin, tempC);
                tempMax = Math.max(tempMax, tempC);
            }
            plug = plugType;
        }
        prevAt = now;
        prevMv = mv;
        if (nextRung == RUNGS && firstRung >= 0) {
            Climb c = finish(END_TOP);
            firstRung = -1;     // stay tracking, so the rest of this charge yields nothing more
            return c;
        }
        return null;
    }

    static int rungMv(int i) { return RUNG0_MV + i * RUNG_STEP_MV; }

    /** When the voltage crossed target (v0 < target <= v1) between two samples, on a straight line. */
    static long interpolate(long t0, int v0, long t1, int v1, int target) {
        if (v1 <= v0) return t1;
        return t0 + Math.round((double) (t1 - t0) * (target - v0) / (v1 - v0));
    }

    private Climb finish(String end) {
        if (firstRung < 0) return null;
        int n = nextRung - firstRung;
        if (n < 2) return null;
        int[] sec = new int[n];
        int[] scr = new int[n];
        long t0 = crossAt[firstRung];
        long s0 = screenAt[firstRung];
        for (int i = 0; i < n; i++) {
            sec[i] = (int) Math.round((crossAt[firstRung + i] - t0) / 1000.0);
            scr[i] = (int) Math.round((screenAt[firstRung + i] - s0) / 1000.0);
        }
        boolean hasTemp = tempMax >= tempMin;
        return new Climb(rungMv(firstRung), sec, scr, end, ichgMin, ichgMax, inputLimit, plug,
                hasTemp ? tempMin : Double.NaN, hasTemp ? tempMax : Double.NaN,
                (int) Math.round(maxGap / 1000.0), samples);
    }

    private void reset() {
        tracking = false;
        firstRung = -1;
        nextRung = 0;
    }

    private void advanceScreen(long now) {
        if (now <= screenMark) return;
        if (screenOn) screenOnMs += now - screenMark;
        screenMark = now;
    }

    /** Screen-on total at time at, which lies at or before now (screenMark == now here). */
    private long screenOnMsAt(long at, long now) {
        long back = Math.max(0, now - at);
        return screenOn ? Math.max(0, screenOnMs - back) : screenOnMs;
    }
}
