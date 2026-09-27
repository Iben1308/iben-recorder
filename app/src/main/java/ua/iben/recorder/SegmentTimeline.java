package ua.iben.recorder;

/** Boundaries follow encoded sample timestamps, never wall-clock timers or file completion. */
public final class SegmentTimeline {
    private final long durationUs;
    private long startUs = Long.MIN_VALUE;
    private long lastUs = Long.MIN_VALUE;
    public SegmentTimeline(long durationUs) {
        if (durationUs <= 0) throw new IllegalArgumentException("Invalid segment duration");
        this.durationUs = durationUs;
    }
    public boolean accept(long presentationUs) {
        if (lastUs != Long.MIN_VALUE && presentationUs <= lastUs)
            throw new IllegalArgumentException("Non-monotonic audio timestamp");
        boolean startsSegment = startUs == Long.MIN_VALUE || presentationUs - startUs >= durationUs;
        if (startsSegment) startUs = presentationUs;
        lastUs = presentationUs;
        return startsSegment;
    }
    public long relativeUs(long presentationUs) { return presentationUs - startUs; }
    public long startUs() { return startUs; }
    public static long sampleTimeUs(long samples, int sampleRate) {
        if (samples < 0 || sampleRate <= 0) throw new IllegalArgumentException("Invalid sample clock");
        return samples / sampleRate * 1000000L + samples % sampleRate * 1000000L / sampleRate;
    }
}
