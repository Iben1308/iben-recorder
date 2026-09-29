package ua.iben.recorder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Local wall-clock intervals; Monday=0. Equal start/end means a full day. */
public final class WeeklySchedule {
    public static final class Day {
        public final boolean enabled;
        public final int start, end;
        public Day(boolean enabled, int start, int end) {
            if (start < 0 || start >= 1440 || end < 0 || end >= 1440) throw new IllegalArgumentException("Invalid time");
            this.enabled = enabled; this.start = start; this.end = end;
        }
    }
    public static final class State {
        public final boolean active;
        /** Next actual state change, or 0 for an empty/continuous schedule. */
        public final long next;
        State(boolean active, long next) { this.active = active; this.next = next; }
    }
    public static State at(Day[] days, long now, ZoneId zone) {
        if (days.length != 7) throw new IllegalArgumentException("Seven days required");
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        List<long[]> intervals = new ArrayList<>();
        // More than one whole week both sides distinguishes 24/7 from an artificial edge.
        for (int offset = -8; offset <= 15; offset++) {
            LocalDate date = today.plusDays(offset);
            Day d = days[date.getDayOfWeek().getValue() - 1];
            if (!d.enabled) continue;
            long start = date.atTime(LocalTime.of(d.start / 60, d.start % 60)).atZone(zone).toInstant().toEpochMilli();
            LocalDate endDate = d.end <= d.start ? date.plusDays(1) : date;
            long end = endDate.atTime(LocalTime.of(d.end / 60, d.end % 60)).atZone(zone).toInstant().toEpochMilli();
            if (end > start) intervals.add(new long[]{start, end});
        }
        intervals.sort(Comparator.comparingLong(a -> a[0]));
        List<long[]> merged = new ArrayList<>();
        for (long[] v : intervals) {
            if (merged.isEmpty() || merged.get(merged.size() - 1)[1] < v[0]) merged.add(v.clone());
            else merged.get(merged.size() - 1)[1] = Math.max(merged.get(merged.size() - 1)[1], v[1]);
        }
        long horizon = today.plusDays(8).atStartOfDay(zone).toInstant().toEpochMilli();
        for (long[] v : merged) {
            if (v[0] <= now && now < v[1]) return new State(true, v[1] >= horizon ? 0 : v[1]);
            if (v[0] > now) return new State(false, v[0]);
        }
        return new State(false, 0);
    }
    public static boolean allowed(State state, long skippedUntil, long now) {
        return state.active && skippedUntil != -1 && (skippedUntil == 0 || now >= skippedUntil);
    }
    public static long skipCurrent(State state) { return state.active ? state.next == 0 ? -1 : state.next : 0; }
    public static final class Decision {
        public final boolean wanted, scheduled;
        Decision(boolean wanted, boolean scheduled) { this.wanted = wanted; this.scheduled = scheduled; }
    }
    public static Decision decide(boolean wanted, boolean scheduled, boolean due, boolean permitted) {
        if (scheduled && !due) return new Decision(false, false);
        if (!wanted && due && permitted) return new Decision(true, true);
        return new Decision(wanted, scheduled);
    }
}
