package ua.iben.recorder;
/** Only actual failed transfer attempts count, never OS/network waiting. */
public final class IncidentPolicy {
    public static final long CLOUD_GRACE_MS=30*60000L;
    public static boolean cloudAlert(int failures,long since,long now) {
        return failures>=2 && since>0 && now>=since && now-since>=CLOUD_GRACE_MS;
    }
}
