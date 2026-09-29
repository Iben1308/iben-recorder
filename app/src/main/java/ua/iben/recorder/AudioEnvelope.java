package ua.iben.recorder;

import java.util.Arrays;

/** RMS envelope of decoded PCM, in 250 ms windows. No compressed-byte approximation. */
public final class AudioEnvelope {
    public static final int STEP_MS = 250;
    private final int window;
    private int used, count;
    private double power;
    private float[] values = new float[256];
    public AudioEnvelope(int sampleRate, int channels) {
        if (sampleRate <= 0 || channels <= 0 || channels > 8) throw new IllegalArgumentException("Invalid PCM format");
        window = sampleRate * channels / 4;
    }
    public void sample(float value) {
        if (!Float.isFinite(value)) value = 0;
        value = Math.max(-1, Math.min(1, value));
        power += value * (double) value;
        if (++used == window) flush();
    }
    private void flush() {
        if (used == 0) return;
        if (count == values.length) values = Arrays.copyOf(values, values.length * 2);
        values[count++] = (float) Math.max(-96, 10 * Math.log10(Math.max(1e-12, power / used)));
        power = 0; used = 0;
    }
    public float[] finish() { flush(); return Arrays.copyOf(values, count); }
    public static boolean[] silence(float[] db, int threshold) {
        boolean[] result = new boolean[db.length];
        int start = -1;
        for (int i = 0; i <= db.length; i++) {
            if (i < db.length && db[i] <= threshold) { if (start < 0) start = i; }
            else if (start >= 0) {
                // Mark only quiet stretches of at least 0.75 seconds.
                if (i - start >= 3) Arrays.fill(result, start, i, true);
                start = -1;
            }
        }
        return result;
    }
}
