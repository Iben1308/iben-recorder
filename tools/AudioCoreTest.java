package ua.iben.recorder;

import java.time.Instant;
import java.util.Arrays;

/** Executable checks for DSP, sample clocks and filename rules; no Android mocks. */
public final class AudioCoreTest {
    private static int checks;
    public static void main(String[] args) {
        gain(); names(); timeline();
        System.out.println("PASS: " + checks + " audio/name/timeline assertions");
    }
    private static void gain() {
        short[] all = new short[65536];
        for (int i = 0; i < all.length; i++) all[i] = (short) (i - 32768);
        short[] original = all.clone();
        AudioGain unity = new AudioGain(0);
        unity.process(all, all.length, 0);
        check(Arrays.equals(all, original), "0 dB preserves every PCM16 value exactly");
        check(unity.peak() == 1.0f && unity.limitedFraction() == 0f, "Unity meter handles -32768 without overflow");
        for (int db : new int[]{1, 6, 12, 18, 24}) {
            short[] wave = new short[1000];
            for (int i = 0; i < wave.length; i++) wave[i] = (short) Math.round(600 * Math.sin(2 * Math.PI * i / 100));
            short[] before = wave.clone();
            new AudioGain(db).process(wave, wave.length, db);
            for (int i = 0; i < wave.length; i++) check(Math.abs(wave[i] - before[i] * Math.pow(10, db / 20.0)) <= 0.51,
                    "Gain has expected linear amplitude below limiter knee");
        }
        AudioGain loud = new AudioGain(24);
        short[] extremes = {32767, -32768, 30000, -30000, 0};
        loud.process(extremes, extremes.length, 24);
        check(extremes[0] > 32000 && extremes[1] < -32000 && extremes[2] > 0 && extremes[3] < 0,
                "Amplification cannot wrap around PCM16 or invert the sign");
        check(extremes[4] == 0 && loud.limitedFraction() == 0.8f, "Limiter preserves silence and reports engaged samples");
        short[] ramp = new short[1024]; Arrays.fill(ramp, (short) 500);
        AudioGain changing = new AudioGain(0);
        changing.process(ramp, ramp.length, 24);
        check(ramp[0] < 520 && ramp[ramp.length - 1] > 7900, "Live change ramps rather than jumps at buffer start");
        for (int i = 1; i < ramp.length; i++) check(ramp[i] >= ramp[i - 1], "Upward gain ramp is monotonic");
        Arrays.fill(ramp, (short) 500); changing.process(ramp, ramp.length, 0);
        for (int i = 1; i < ramp.length; i++) check(ramp[i] <= ramp[i - 1], "Downward gain ramp is monotonic");
        check(ramp[ramp.length - 1] == 500, "Ramp returns to exact unity gain");
        short[] partial = {1000, 1000, 12345}; new AudioGain(6).process(partial, 2, 6);
        check(partial[2] == 12345, "Partial microphone read leaves unused buffer region unchanged");
        for (int db : new int[]{-1, 25}) {
            try { new AudioGain(db); throw new AssertionError("Invalid gain accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
    }
    private static void names() {
        long start = Instant.parse("2026-09-27T12:11:24Z").toEpochMilli();
        String name = RecordingNames.format(start, "Europe/Kyiv", 3600011L, 0);
        check(name.equals("2026-09-27_15-11-24(60_00).m4a"), "Local start time and hour duration");
        check(RecordingNames.validPublishedName(name), "Generated name is accepted");
        check(RecordingNames.format(start, "UTC", 59500, 0).endsWith("(01_00).m4a"), "Duration rounds across minute boundary");
        check(RecordingNames.format(start, "UTC", 59499, 0).endsWith("(00_59).m4a"), "Duration rounds down below half second");
        check(RecordingNames.format(start, "UTC", 10800000, 1).endsWith("(180_00)_2.m4a"), "Long duration and collision suffix");
        check(!RecordingNames.validPublishedName("../" + name), "No path traversal");
        check(!RecordingNames.validPublishedName(name + ".part"), "No unfinished published name");
        check(!RecordingNames.validPublishedName(null), "No missing name");
        check(!StoragePolicy.ownedName(name, "123456abcdef"), "Date-only names never imply legacy ownership");
    }
    private static void timeline() {
        for (int rate : new int[]{44100, 48000}) {
            long daySamples = 60L * 60 * 24 * 35 * rate;
            check(SegmentTimeline.sampleTimeUs(daySamples, rate) == 35L * 24 * 60 * 60 * 1000000,
                    "Sample clock remains exact beyond 32-bit frame and microsecond ranges");
            for (int minutes : new int[]{1, 60, 180}) {
                long length = minutes * 60L * 1000000;
                SegmentTimeline timeline = new SegmentTimeline(length);
                long previous = Long.MIN_VALUE;
                long segmentFirst = Long.MIN_VALUE;
                int segments = 0;
                // Includes a negative encoder priming timestamp and many file boundaries.
                long frames = (minutes == 1 ? 11 * 60L : 4 * 60 * 60L) * rate / 1024;
                for (long frame = 0; frame < frames; frame++) {
                    long pts = SegmentTimeline.sampleTimeUs(frame * 1024, rate) - 23220;
                    boolean newFile = timeline.accept(pts);
                    if (newFile) {
                        if (segments > 0) {
                            check(previous - segmentFirst < length, "No frame was left past the boundary in old file");
                            check(pts - segmentFirst >= length, "New file starts on first eligible AAC frame");
                        }
                        segmentFirst = pts; segments++;
                        check(timeline.relativeUs(pts) == 0, "Every M4A starts at timestamp zero");
                    } else check(timeline.relativeUs(pts) > 0, "Every remaining frame has a positive relative timestamp");
                    previous = pts;
                }
                check(segments >= 2, "Test actually crossed a file boundary");
                try { timeline.accept(previous); throw new AssertionError("Repeated timestamp accepted"); }
                catch (IllegalArgumentException expected) { checks++; }
            }
        }
    }
    private static void check(boolean condition, String description) {
        checks++; if (!condition) throw new AssertionError(description);
    }
}
