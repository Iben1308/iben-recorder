package ua.iben.recorder;

import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.List;

/** Callbacks observe capture policy; they do not try to override Android's microphone policy. */
final class CaptureMonitor implements AutoCloseable {
    private final AudioRecord record;
    private final AudioManager manager;
    private final CaptureHealth health;
    private volatile boolean closed,policySilenced;
    private Runnable unregister=() -> { };
    CaptureMonitor(AudioRecord record,AudioManager manager,int rate) {
        this.record=record;this.manager=manager;health=new CaptureHealth(rate);
        if(Build.VERSION.SDK_INT>=29)try {unregister=Api29.register(record,this);}catch(RuntimeException ignored) { }
    }
    void started() {if(Build.VERSION.SDK_INT>=29)try {Api29.initial(record,this);}catch(RuntimeException ignored) { }}
    void samples(short[] pcm,int count) {health.samples(pcm,count);}
    int state() {
        boolean mute=policySilenced;
        try {mute|=manager.isMicrophoneMute();}catch(RuntimeException ignored) { }
        return health.state(mute);
    }
    @Override public void close() {closed=true;unregister.run();}
    @android.annotation.TargetApi(29)
    private static final class Api29 {
        static Runnable register(AudioRecord record,CaptureMonitor monitor) {
            Handler main=new Handler(Looper.getMainLooper());
            AudioManager.AudioRecordingCallback callback=new AudioManager.AudioRecordingCallback() {
                @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                    if(monitor.closed)return;
                    for(AudioRecordingConfiguration config:configs)
                        if(config.getClientAudioSessionId()==record.getAudioSessionId())monitor.policySilenced=config.isClientSilenced();
                }
            };
            record.registerAudioRecordingCallback(command -> main.post(command),callback);
            return () -> {try {record.unregisterAudioRecordingCallback(callback);}catch(RuntimeException ignored) { }};
        }
        static void initial(AudioRecord record,CaptureMonitor monitor) {
            AudioRecordingConfiguration current=record.getActiveRecordingConfiguration();
            if(current!=null)monitor.policySilenced=current.isClientSilenced();
        }
    }
}
