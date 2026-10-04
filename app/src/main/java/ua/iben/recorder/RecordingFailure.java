package ua.iben.recorder;

import java.io.IOException;

/** Stable reasons, independent of translated messages. */
final class RecordingFailure extends IOException {
    enum Reason { PERMISSION, CONFIGURATION, INPUT_UNAVAILABLE, INPUT_BUSY, AUDIO_READ, CODEC, STORAGE_FULL, STORAGE_IO, STALLED, UNKNOWN }
    final Reason reason;
    RecordingFailure(Reason reason) { super(I18n.uk(key(reason)));this.reason=reason; }
    RecordingFailure(Reason reason,Throwable cause) { super(I18n.uk(key(reason)),cause);this.reason=reason; }
    static String key(Reason reason) { return "failure_"+reason.name().toLowerCase(java.util.Locale.ROOT); }
    static Reason classify(Throwable error) {
        Reason typed=null;
        for(Throwable cause=error;cause!=null;cause=cause.getCause()) {
            if(cause instanceof SecurityException)return Reason.PERMISSION;
            if(cause instanceof StorageFullException)return Reason.STORAGE_FULL;
            if(typed==null && cause instanceof RecordingFailure)typed=((RecordingFailure)cause).reason;
        }
        return typed==null ? Reason.UNKNOWN : typed;
    }
    /** Negative means wait for user correction; no hot loop on permissions or invalid settings. */
    static long retryDelay(Reason reason,int attempts) {
        int n=Math.max(0,attempts);
        switch(reason) {
            case PERMISSION: case CONFIGURATION:return -1;
            case STORAGE_FULL:return 60000;
            case INPUT_UNAVAILABLE:return 15000;
            case INPUT_BUSY:return n==0 ? 5000 : n==1 ? 15000 : 30000;
            case STORAGE_IO:return n==0 ? 10000 : n==1 ? 30000 : 60000;
            default:return n==0 ? 1000 : n==1 ? 3000 : n==2 ? 10000 : n==3 ? 30000 : 60000;
        }
    }
}
