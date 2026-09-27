package ua.iben.recorder;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public final class RecordingNames {
    private RecordingNames() { }
    public static String format(long startMillis, String zoneId, long durationMillis, int duplicate) {
        if (durationMillis < 0 || duplicate < 0) throw new IllegalArgumentException("Invalid recording metadata");
        SimpleDateFormat date = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);
        date.setTimeZone(TimeZone.getTimeZone(zoneId));
        long seconds = durationMillis / 1000L + (durationMillis % 1000L >= 500L ? 1 : 0);
        String duration = String.format(Locale.ROOT, "(%02d_%02d)", seconds / 60L, seconds % 60L);
        return date.format(new Date(startMillis)) + duration
                + (duplicate == 0 ? "" : "_" + (duplicate + 1)) + ".m4a";
    }

    public static boolean validPublishedName(String name) {
        return name != null && name.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{2}-[0-9]{2}-[0-9]{2}"
                + "\\([0-9]{2,}_[0-9]{2}\\)(_[0-9]+)?\\.m4a");
    }
}
