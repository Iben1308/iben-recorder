package ua.iben.recorder;

/** PCM16 mono gain, smoothed over one buffer, with a soft knee above 90% full scale. */
public final class AudioGain {
    private double previous;
    private volatile float peak;
    private volatile float limitedFraction;

    public AudioGain(int initialDb) { previous = factor(initialDb); }

    public void process(short[] pcm, int count, int gainDb) {
        if (count < 0 || count > pcm.length) throw new IllegalArgumentException("Invalid PCM length");
        if (count == 0) return;
        double target = factor(gainDb);
        double step = (target - previous) / count;
        double current = previous;
        double maximum = 0;
        int limited = 0;
        boolean identity = previous == 1.0 && target == 1.0;
        for (int i = 0; i < count; i++) {
            current += step;
            if (!identity) {
                double value = pcm[i] / 32768.0 * current;
                double magnitude = Math.abs(value);
                if (magnitude > 0.90) {
                    limited++;
                    value = Math.copySign(0.90 + 0.10 * Math.tanh((magnitude - 0.90) / 0.10), value);
                }
                long sample = Math.round(value * 32768.0);
                pcm[i] = (short) Math.max(-32768L, Math.min(32767L, sample));
            }
            maximum = Math.max(maximum, Math.abs((int) pcm[i]) / 32768.0);
        }
        previous = target;
        peak = (float) maximum;
        limitedFraction = limited / (float) count;
    }

    public float peak() { return peak; }
    public float limitedFraction() { return limitedFraction; }
    private static double factor(int db) {
        if (db < 0 || db > 24) throw new IllegalArgumentException("Gain must be 0..24 dB");
        return Math.pow(10.0, db / 20.0);
    }
}
