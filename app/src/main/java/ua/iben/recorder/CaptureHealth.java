package ua.iben.recorder;

/** Exact-zero diagnostics are not evidence of a system mute, and never restart capture. */
final class CaptureHealth {
    static final int NORMAL=0, SYSTEM_SILENCED=1, ZERO_SIGNAL=2;
    private final long threshold;
    private volatile long zeroSamples;
    CaptureHealth(int sampleRate) { if(sampleRate<=0)throw new IllegalArgumentException();threshold=sampleRate*15L; }
    void samples(short[] values,int count) {
        for(int i=0;i<count;i++)if(values[i]!=0) {zeroSamples=0;return;}
        zeroSamples=Math.min(threshold,zeroSamples+count);
    }
    int state(boolean systemSilenced) {return systemSilenced ? SYSTEM_SILENCED : zeroSamples>=threshold ? ZERO_SIGNAL : NORMAL;}
}
