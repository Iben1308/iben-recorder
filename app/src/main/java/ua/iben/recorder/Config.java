package ua.iben.recorder;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.UUID;

final class Config {
    final Context context;
    final SharedPreferences prefs;
    Config(Context context) { this.context = context.getApplicationContext(); prefs = context.getSharedPreferences("recorder", Context.MODE_PRIVATE); }
    String origin() { return prefs.getString("origin", "manual"); }
    boolean scheduleEnabled() { return prefs.getBoolean("schedule_enabled", false); }
    WeeklySchedule.Day[] days() {
        WeeklySchedule.Day[] days = new WeeklySchedule.Day[7];
        for (int i = 0; i < 7; i++) days[i] = new WeeklySchedule.Day(prefs.getBoolean("day_" + i, false),
                prefs.getInt("from_" + i, 540), prefs.getInt("to_" + i, 1080));
        return days;
    }
    void schedule(boolean enabled, WeeklySchedule.Day[] days) {
        SharedPreferences.Editor edit = prefs.edit().putBoolean("schedule_enabled", enabled).putLong("schedule_skip", 0).putBoolean("schedule_paused", false);
        for (int i = 0; i < 7; i++) edit.putBoolean("day_" + i, days[i].enabled).putInt("from_" + i, days[i].start).putInt("to_" + i, days[i].end);
        if (!edit.commit()) throw new IllegalStateException("Не вдалося зберегти розклад");
    }
    String language() { return prefs.getString("language", "uk"); }
    int silenceDb() { return prefs.getInt("silence_db", -45); }
    int source() { return prefs.getInt("audio_source", android.media.MediaRecorder.AudioSource.MIC); }
    String input() { return prefs.getString("audio_input", "auto"); }
    int minutes() { return prefs.getInt("minutes", 60); }
    int bitrate() { return prefs.getInt("bitrate", 128); }
    int sampleRate() { return prefs.getInt("sample_rate", 44100); }
    int gainDb() { return Math.max(0, Math.min(24, prefs.getInt("gain_db", 0))); }
    void gainDb(int db) { prefs.edit().putInt("gain_db", Math.max(0, Math.min(24, db))).apply(); }
    int theme() { return Math.max(0, Math.min(2, prefs.getInt("theme", 0))); }
    void theme(int mode) { prefs.edit().putInt("theme", mode).apply(); }
    int quotaMiB() { return prefs.getInt("quota_mib", 2048); }
    long quota() { return quotaMiB() * StoragePolicy.MIB; }
    boolean deleteOldest() { return prefs.getBoolean("delete_oldest", true); }
    boolean resumeAtBoot() { return prefs.getBoolean("resume_boot", true); }
    boolean wanted() { return prefs.getBoolean("wanted", false); }
    void wanted(boolean value) { prefs.edit().putBoolean("wanted", value).commit(); }
    synchronized String owner() {
        String owner = prefs.getString("owner", null);
        if (owner == null) {
            owner = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            if (!prefs.edit().putString("owner", owner).commit())
                throw new IllegalStateException("Не вдалося зберегти ідентифікатор");
        }
        return owner;
    }
    void save(int minutes, int bitrate, int sampleRate, int quota, boolean delete, boolean boot, int source, String input) {
        if (minutes < 1 || minutes > 180 || quota < 128 || quota > 32768
                || (bitrate != 64 && bitrate != 96 && bitrate != 128 && bitrate != 192 && bitrate != 256)
                || (sampleRate != 44100 && sampleRate != 48000))
            throw new IllegalArgumentException("Перевірте інтервал, якість і ліміт пам’яті");
        if (2 * StoragePolicy.segmentBudget(minutes, bitrate) > quota * StoragePolicy.MIB)
            throw new IllegalArgumentException("Для безперервного запису ліміт має вміщувати два фрагменти із запасом");
        if (!prefs.edit().putInt("minutes", minutes).putInt("bitrate", bitrate)
                .putInt("sample_rate", sampleRate).putInt("quota_mib", quota)
                .putBoolean("delete_oldest", delete).putBoolean("resume_boot", boot)
                .putInt("audio_source", source).putString("audio_input", input).commit())
            throw new IllegalStateException("Не вдалося зберегти налаштування");
    }
    void status(String text, long segmentStart) {
        prefs.edit().putString("status", text).putLong("segment_start", segmentStart)
                .putLong("heartbeat", System.currentTimeMillis()).apply();
    }
}
