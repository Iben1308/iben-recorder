package ua.iben.recorder;
import java.util.ArrayList;
import java.util.List;
/** Bounded union of intervals actually played. Seeking does not add coverage. */
public final class PlaybackProgress {
    private final long duration;
    private final List<long[]> ranges = new ArrayList<>();
    public PlaybackProgress(long duration, String encoded) {
        this.duration = Math.max(0, duration);
        if (encoded == null || encoded.length() > 16384) return;
        for (String part : encoded.split(";")) {
            String[] pair = part.split(":");
            if (pair.length == 2) try { add(Long.parseLong(pair[0]), Long.parseLong(pair[1])); }
            catch (NumberFormatException ignored) { }
        }
    }
    public void add(long from, long to) {
        from = Math.max(0, from); to = Math.min(duration, to); if (to <= from) return;
        int i = 0;
        while (i < ranges.size() && ranges.get(i)[1] < from) i++;
        while (i < ranges.size() && ranges.get(i)[0] <= to) {
            long[] old = ranges.remove(i); from = Math.min(from, old[0]); to = Math.max(to, old[1]);
        }
        ranges.add(i, new long[]{from, to});
        if (ranges.size() > 256) {
            int smallest = 0;
            for (int n = 1; n < ranges.size(); n++)
                if (ranges.get(n)[1]-ranges.get(n)[0] < ranges.get(smallest)[1]-ranges.get(smallest)[0]) smallest = n;
            ranges.remove(smallest);
        }
    }
    public long covered() { long total=0; for (long[] range:ranges) total+=range[1]-range[0]; return total; }
    public boolean complete() { return duration>0 && covered()>=duration-duration/20; }
    public String encode() {
        StringBuilder text=new StringBuilder();
        for(long[] range:ranges) { if(text.length()>0)text.append(';'); text.append(range[0]).append(':').append(range[1]); }
        return text.toString();
    }
    public static long resume(long position,long duration) { return Math.max(0,Math.min(position,Math.max(0,duration-1))); }
}
