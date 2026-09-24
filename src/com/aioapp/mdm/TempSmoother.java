package com.aioapp.mdm;

/**
 * Smooths the battery temperature before it is reported.
 *
 * The T7's battery thermistor reading is noisy: sampled once a minute over 100 minutes on
 * six devices it jumped a median 0.6–1.05 °C per minute and up to 5.4 °C, and 1.3 °C inside
 * five seconds on a bench device — faster than any battery can change temperature. The old
 * client reported the instant reading behind a 1 °C deadband, which latched whichever spike
 * first crossed the step and held it (35.7 °C reported against a board at 31.6 °C). Against
 * the board's actual temperature that was within ±1 °C only 77% of the time.
 *
 * An exponential moving average with a three-minute time constant, fed every battery
 * broadcast, was within ±1 °C 95% of the time on the same data. It is integrated over real
 * elapsed time rather than per sample, because broadcasts arrive irregularly (a burst while
 * the reading flickers, then silence): between samples the reading is taken to hold its last
 * value, so a burst of noise does not outweigh minutes of a steady reading.
 */
final class TempSmoother {
    static final long TAU_MS = 180_000L;

    private double value = Double.NaN; // the smoothed temperature
    private double held;               // the last raw reading, assumed to hold until the next
    private long at;                   // elapsedRealtime value was last brought up to

    /** Feeds one raw reading taken at {@code now} (elapsedRealtime ms). */
    synchronized void add(long now, double raw) {
        if (Double.isNaN(value)) {
            value = raw;
        } else {
            advance(now);
        }
        held = raw;
        at = now;
    }

    /** The smoothed temperature at {@code now}, or NaN before the first reading. */
    synchronized double read(long now) {
        advance(now);
        return value;
    }

    private void advance(long now) {
        if (Double.isNaN(value)) return;
        long dt = now - at;
        if (dt <= 0) return;
        value += (1 - Math.exp(-dt / (double) TAU_MS)) * (held - value);
        at = now;
    }
}
