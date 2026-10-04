package ua.iben.recorder;

/** Pure policies only: this does not simulate the Android audio stack. */
public final class CaptureRecoveryTest {
    private static int checks;
    public static void main(String[] args) {
        for(int rate:new int[]{8000,16000,44100,48000}) {
            CaptureHealth health=new CaptureHealth(rate);
            short[] zero=new short[rate];
            for(int second=0;second<14;second++)health.samples(zero,zero.length);
            check(health.state(false)==CaptureHealth.NORMAL,"Less than 15 seconds of zero is not flagged");
            health.samples(zero,rate-1);
            check(health.state(false)==CaptureHealth.NORMAL,"Exact boundary before 15 seconds");
            health.samples(zero,1);
            check(health.state(false)==CaptureHealth.ZERO_SIGNAL,"15 seconds of digital zero is diagnostic");
            check(health.state(true)==CaptureHealth.SYSTEM_SILENCED,"Explicit policy mute takes priority over zeros");
            health.samples(new short[]{1},1);
            check(health.state(false)==CaptureHealth.NORMAL,"Even quiet nonzero input restores signal status");
            check(health.state(true)==CaptureHealth.SYSTEM_SILENCED,"System mute does not depend on amplitude");
            for(int second=0;second<60;second++)health.samples(new short[]{0,1,0,-1},4);
            check(health.state(false)==CaptureHealth.NORMAL,"Quiet sound is never inferred to be a system mute");
        }
        for(int sdk:new int[]{27,28,29,30,31,33,35,36}) {
            check(AudioInputPolicy.sampleRate(sdk,"bt:sco",48000)==(sdk<31 ? 8000 : 16000),"Bluetooth voice rate by platform");
            check(AudioInputPolicy.sampleRate(sdk,"bt:26:address",44100)==(sdk<31 ? 8000 : 16000),"Named Bluetooth route uses voice profile");
            check(AudioInputPolicy.sampleRate(sdk,"default",44100)==44100,"Built-in rate is unchanged");
            check(AudioInputPolicy.sampleRate(sdk,"usb:address",48000)==48000,"USB rate is unchanged");
        }
        check(!AudioInputPolicy.bluetooth(null) && !AudioInputPolicy.bluetooth("default"),"Default input never requests Bluetooth");
        check(AudioInputPolicy.bitrate("bt:sco",256)==64 && AudioInputPolicy.bitrate("bt:sco",32)==32,"Bluetooth does not waste high AAC bitrate");
        check(AudioInputPolicy.bitrate("default",256)==256,"Non-Bluetooth bitrate preserved");
        for(RecordingFailure.Reason reason:RecordingFailure.Reason.values()) {
            RecordingFailure failure=new RecordingFailure(reason);
            check(RecordingFailure.classify(failure)==reason,"Every typed failure retains its reason");
            check(RecordingFailure.classify(new java.io.IOException("wrapper",failure))==reason,"Nested failure retains reason");
            check(!I18n.uk(RecordingFailure.key(reason)).equals(RecordingFailure.key(reason)),"Every reason has a UI message");
            long previous=-1;
            for(int attempt=0;attempt<100;attempt++) {
                long delay=RecordingFailure.retryDelay(reason,attempt);
                check(delay>=previous && delay<=60000,"Backoff never decreases or exceeds one minute");
                previous=delay;
            }
        }
        check(RecordingFailure.classify(new RecordingFailure(RecordingFailure.Reason.CODEC,new SecurityException()))==RecordingFailure.Reason.PERMISSION,
                "Permission failures inside another stage remain actionable");
        check(RecordingFailure.classify(new RecordingFailure(RecordingFailure.Reason.STORAGE_IO,new StorageFullException("full")))==RecordingFailure.Reason.STORAGE_FULL,
                "Storage exhaustion is distinct from disk I/O");
        check(RecordingFailure.classify(new java.io.IOException())==RecordingFailure.Reason.UNKNOWN,"Unknown remains explicit");
        for(RecordingFailure.Reason reason:new RecordingFailure.Reason[]{RecordingFailure.Reason.PERMISSION,RecordingFailure.Reason.CONFIGURATION})
            check(RecordingFailure.retryDelay(reason,0)==-1,"Permission/configuration waits for user correction");
        long[] expected={1000,3000,10000,30000,60000,60000};
        for(int i=0;i<expected.length;i++)check(RecordingFailure.retryDelay(RecordingFailure.Reason.CODEC,i)==expected[i],"Codec recovery uses bounded fast-first retries");
        check(RecordingFailure.retryDelay(RecordingFailure.Reason.INPUT_UNAVAILABLE,0)==15000,"Disconnected microphone does not spin");
        check(RecordingFailure.retryDelay(RecordingFailure.Reason.STORAGE_FULL,0)==60000,"Full storage waits for space");
        check(RecordingFailure.retryDelay(RecordingFailure.Reason.INPUT_BUSY,0)==5000,"Busy input uses a distinct retry interval");
        System.out.println("PASS: "+checks+" capture health, Bluetooth format and recovery policy assertions (no Android device)");
    }
    private static void check(boolean value,String why) {checks++;if(!value)throw new AssertionError(why);}
}
