package ua.iben.recorder;

import java.time.*;
import java.util.Arrays;
import java.util.Random;

public final class FeaturesTest {
    private static int checks;
    public static void main(String[] args) {
        schedule(); envelope(); language();
        System.out.println("PASS: " + checks + " schedule / PCM envelope / language assertions");
    }
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    private static long at(String time) { return Instant.parse(time).toEpochMilli(); }
    private static WeeklySchedule.Day[] days() {
        WeeklySchedule.Day[] days = new WeeklySchedule.Day[7];
        Arrays.fill(days, new WeeklySchedule.Day(false, 540, 1080)); return days;
    }
    private static void schedule() {
        ZoneId utc = ZoneId.of("UTC"); WeeklySchedule.Day[] d = days();
        long monday = at("2026-09-28T00:00:00Z");
        check(!WeeklySchedule.at(d, monday, utc).active && WeeklySchedule.at(d, monday, utc).next == 0, "Empty schedule never starts");
        d[0] = new WeeklySchedule.Day(true, 9*60, 17*60);
        check(!WeeklySchedule.at(d, monday + 9*3600000L - 1, utc).active, "Start is not early");
        check(WeeklySchedule.at(d, monday + 9*3600000L, utc).active, "Start is inclusive");
        check(!WeeklySchedule.at(d, monday + 17*3600000L, utc).active, "End is exclusive");
        check(WeeklySchedule.at(d, monday + 17*3600000L, utc).next == monday + 7*86400000L + 9*3600000L, "Next start can be next week");
        d = days(); d[6] = new WeeklySchedule.Day(true, 22*60, 6*60);
        WeeklySchedule.State overnight = WeeklySchedule.at(d, monday + 3600000, utc);
        check(overnight.active && overnight.next == monday + 6*3600000L, "Sunday interval carries into Monday");
        check(!WeeklySchedule.allowed(overnight, WeeklySchedule.skipCurrent(overnight), monday + 3600000), "Manual stop skips the rest of the interval");
        d[0] = new WeeklySchedule.Day(true, 5*60, 9*60);
        check(WeeklySchedule.at(d, monday + 3600000, utc).next == monday + 9*3600000L, "Overlapping intervals have no stop/start gap");
        d = days(); d[0] = new WeeklySchedule.Day(true, 0, 0);
        check(WeeklySchedule.at(d, monday, utc).next == monday + 86400000, "Equal times mean a full day");
        Arrays.fill(d, new WeeklySchedule.Day(true, 0, 0));
        WeeklySchedule.State always = WeeklySchedule.at(d, monday, utc);
        check(always.active && always.next == 0 && WeeklySchedule.skipCurrent(always) == -1, "24/7 schedule has no artificial end");
        check(!WeeklySchedule.allowed(always, -1, monday + 100*86400000L), "Manual stop of continuous schedule remains paused");
        d = days(); d[6] = new WeeklySchedule.Day(true, 90, 210);
        ZoneId warsaw = ZoneId.of("Europe/Warsaw");
        WeeklySchedule.State spring = WeeklySchedule.at(d, at("2026-03-29T00:45:00Z"), warsaw);
        check(spring.active && spring.next == at("2026-03-29T01:30:00Z"), "Spring DST follows local clock times");
        WeeklySchedule.State autumn = WeeklySchedule.at(d, at("2026-10-25T00:45:00Z"), warsaw);
        check(autumn.active && autumn.next == at("2026-10-25T02:30:00Z"), "Autumn DST interval includes the repeated hour");
        d[6] = new WeeklySchedule.Day(true, 150, 210);
        check(!WeeklySchedule.at(d, at("2026-03-29T00:59:59Z"), warsaw).active, "Nonexistent local start does not run before the gap");
        check(!WeeklySchedule.at(d, at("2026-03-29T01:30:00Z"), warsaw).active, "Collapsed DST interval has no spurious recording");
        WeeklySchedule.Decision decision = WeeklySchedule.decide(true,false,false,true);
        check(decision.wanted && !decision.scheduled, "End of schedule never stops a manual recording");
        check(!WeeklySchedule.decide(true,true,false,true).wanted, "End/disable stops a scheduled recording");
        check(WeeklySchedule.decide(false,false,true,true).scheduled, "An active schedule starts idle capture");
        check(!WeeklySchedule.decide(false,false,true,false).wanted, "Missing permissions cannot start capture");
        check(!WeeklySchedule.decide(false,false,false,true).wanted, "A skipped interval cannot restart capture");
        Random random = new Random(81);
        for (int trial=0; trial<12; trial++) {
            d=days();
            for(int i=0;i<7;i++) d[i]=new WeeklySchedule.Day(random.nextBoolean(),random.nextInt(1440),random.nextInt(1440));
            for(int minute=0;minute<7*1440;minute+=13) {
                int day=minute/1440, clock=minute%1440;
                WeeklySchedule.Day today=d[day], previous=d[(day+6)%7];
                boolean expected=today.enabled && (today.start<today.end ? clock>=today.start&&clock<today.end : clock>=today.start);
                expected |= previous.enabled && previous.end<=previous.start && clock<previous.end;
                WeeklySchedule.State s=WeeklySchedule.at(d,monday+minute*60000L,utc);
                check(s.active==expected,"Schedule matches an independent wall-clock minute oracle");
                if(s.next>0) {
                    check(s.next>monday+minute*60000L,"Next transition always advances time");
                    check(WeeklySchedule.at(d,s.next-1,utc).active!=WeeklySchedule.at(d,s.next,utc).active,"A reported transition actually changes state");
                }
            }
        }
    }
    private static void envelope() {
        AudioEnvelope sine=new AudioEnvelope(48000,1);
        for(int i=0;i<48000;i++) sine.sample((float)Math.sin(2*Math.PI*1000*i/48000));
        float[] result=sine.finish();
        check(result.length==4,"One second has four 250 ms bins");
        for(float db:result) check(Math.abs(db+3.0103)<.01,"Full-scale sine has -3.01 dBFS RMS");
        AudioEnvelope stereo=new AudioEnvelope(48000,2);
        for(int i=0;i<12000;i++){stereo.sample(.5f);stereo.sample(-.5f);}
        check(Math.abs(stereo.finish()[0]+6.0206)<.01,"Both stereo channels contribute to RMS");
        AudioEnvelope silent=new AudioEnvelope(44100,1);
        for(int i=0;i<22050;i++)silent.sample(0);
        float[] quiet=silent.finish();check(quiet.length==2&&quiet[0]==-96,"Digital silence is finite and correct at 44.1 kHz");
        check(!AudioEnvelope.silence(quiet,-45)[0],"A half-second quiet gap is not marked as a long silence");
        boolean[] flags=AudioEnvelope.silence(new float[]{-10,-60,-60,-60,-10,-60,-10},-45);
        check(!flags[0]&&flags[1]&&flags[2]&&flags[3]&&!flags[4]&&!flags[5],"Only a sufficiently long quiet run is highlighted");
        check(!AudioEnvelope.silence(new float[]{-40,-40,-40},-45)[0],"Threshold differentiates low-volume audio");
        check(AudioEnvelope.silence(new float[]{-40,-40,-40},-35)[0],"Changing threshold updates the same waveform");
        AudioEnvelope partial=new AudioEnvelope(48000,1);partial.sample(.25f);
        check(Math.abs(partial.finish()[0]+12.0412)<.01,"The final partial window retains its real level");
    }
    private static void language() {
        I18n.use("en");check(I18n.s("record").equals("Record"),"English UI");
        check(I18n.tr("Запис зупинено; файли збережено").equals("Recording stopped; files saved"),"Longest status phrase wins");
        check(I18n.tr("Помилка запису: Кодек не надав аудіо; повтор через 5 с").equals("Recording error: The codec returned no audio; retry in 5 s"),"Composed errors and retry units translate");
        I18n.use("pl");check(I18n.s("weekly_schedule").equals("Harmonogram tygodniowy"),"Polish UI");
        check(I18n.s("gain",6).contains("+6 dB"),"Localized parameters format correctly");
        I18n.use("uk");check(I18n.s("listen").equals("Слухати"),"Ukrainian UI remains the default");
    }
}
