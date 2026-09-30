package ua.iben.recorder;

import java.util.Arrays;
import java.util.Random;

/** Behavioral tests for history, bounded recording envelopes, alerts and schedule pause. */
public final class WorkflowTest {
    private static int checks;
    private static void check(boolean ok, String reason) { checks++; if (!ok) throw new AssertionError(reason); }
    public static void main(String[] args) {
        playback(); envelope(); alerts(); pause(); language();
        System.out.println("PASS: " + checks + " playback / capture-cache / alert / schedule-pause assertions");
    }
    private static void playback() {
        long hour = 3600000;
        PlaybackProgress p = new PlaybackProgress(hour, "");
        p.add(0, 10000); p.add(hour-10000, hour);
        check(p.covered() == 20000 && !p.complete(), "Seeking to the last seconds never marks the entire file heard");
        p.add(5000, 15000);
        check(p.covered() == 25000, "Overlapping playback is counted only once");
        p = new PlaybackProgress(hour, p.encode()); p.add(15000, 3420000);
        check(p.complete(), "Saved disjoint coverage survives a session restart and reaches 95 percent");
        check(PlaybackProgress.resume(-1, hour) == 0, "Negative resume clamps to start");
        check(PlaybackProgress.resume(hour+1, hour) == hour-1, "Resume clamps below completion");
        check(PlaybackProgress.resume(100, 0) == 0, "An empty file cannot have a resume position");
        p = new PlaybackProgress(1000, "bad;-1:200;900:5000;20:10;0:9223372036854775808");
        check(p.covered() == 300 && !p.complete(), "Malformed/oversized history is ignored and ranges are bounded");
        p = new PlaybackProgress(hour, ""); p.add(0, 3419999);
        check(!p.complete(), "Below 95 percent remains unlistened");
        p.add(3419999,3420000); check(p.complete(), "95 percent boundary is inclusive");
        Random random = new Random(20260930); boolean[] heard = new boolean[60000];
        p = new PlaybackProgress(heard.length, ""); long expected = 0;
        for (int i=0; i<1000; i++) {
            int from=random.nextInt(heard.length), to=Math.min(heard.length,from+random.nextInt(6000));
            p.add(from,to);
            for(int j=from;j<to;j++)if(!heard[j]){heard[j]=true;expected++;}
            p = new PlaybackProgress(heard.length,p.encode());
            check(p.covered()==expected,"Coverage matches an independent bitmap across repeated sessions");
        }
        p = new PlaybackProgress(1000000, "");
        for(int i=0;i<1000;i++)p.add(i*1000,i*1000+100);
        check(p.encode().split(";").length<=256 && p.covered()<=100000, "Fragmentation stays bounded and never invents coverage");
    }
    private static void envelope() {
        short[] pcm=new short[1000];Arrays.fill(pcm,(short)16384);
        CaptureEnvelope e = new CaptureEnvelope(1000,1);
        int capacity=e.capacity(); e.add(pcm,0,731); e.add(pcm,731,269);
        float[] bins=e.range(0,1000000);
        check(bins.length==4,"A second produces four quarter-second samples across PCM blocks");
        for(float db:bins)check(Math.abs(db+6.0206)<.01,"PCM amplitude is represented in RMS dBFS");
        e.add(new short[125],0,125);
        float[] partial=e.range(1000000,1125000);
        check(partial.length==1 && partial[0]==-96,"The final partial silent window is retained");
        check(e.range(1000000,1000000)==null,"Empty intervals do not produce a cache");
        for(int i=0;i<600;i++)e.add(pcm,0,pcm.length);
        check(e.capacity()==capacity,"Long recordings do not grow the ring buffer");
        check(e.range(0,1000000)==null,"Evicted samples trigger later analysis instead of a wrong cache");
        bins=e.range(540012000,600000000);
        check(bins!=null && bins.length==240,"A recent minute remains available after hundreds of rotations");
        for(float db:bins)check(Math.abs(db+6.0206)<.01,"Ring wrap retains recent PCM levels");
        check(e.range(0,1000000000)==null,"Unbounded cache allocations are rejected");
    }
    private static void alerts() {
        long since=1000, grace=IncidentPolicy.CLOUD_GRACE_MS;
        check(!IncidentPolicy.cloudAlert(1,since,since+grace+1),"One failure is not a repeated incident");
        check(!IncidentPolicy.cloudAlert(2,since,since+grace-1),"No alert before 30 minutes");
        check(IncidentPolicy.cloudAlert(2,since,since+grace),"An actual repeat at 30 minutes alerts");
        check(!IncidentPolicy.cloudAlert(2,0,since+grace),"No failure time means no incident");
        check(!IncidentPolicy.cloudAlert(2,since,since-1),"Clock rollback cannot cause an early alert");
    }
    private static void pause() {
        for(boolean wanted:new boolean[]{false,true})for(boolean scheduled:new boolean[]{false,true}) {
            WeeklySchedule.Decision d=WeeklySchedule.decide(wanted,scheduled,false,true);
            check(d.wanted==(wanted&&!scheduled) && !d.scheduled,"Pausing automatic work preserves manual recording only");
        }
    }
    private static void language() {
        String[] keys={"general","schedule_tab","schedule_pause_only","problem_alerts","problem_storage","problem_cloud",
                "filter_all","filter_unlistened","filter_listened","mark_listened","mark_unlistened","today","yesterday","wave_building","wave_retry"};
        for(String lang:new String[]{"uk","en","pl"}) {
            I18n.use(lang); for(String key:keys)check(!I18n.s(key).isEmpty()&&!I18n.s(key).equals(key),"Workflow text exists in "+lang);
        }
        I18n.use("uk");
    }
}
