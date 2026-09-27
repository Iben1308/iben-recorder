package ua.iben.recorder;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.UUID;

final class Config {
    final SharedPreferences prefs;
    Config(Context context) { prefs = context.getSharedPreferences("recorder", Context.MODE_PRIVATE); }
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
    void save(int minutes, int bitrate, int sampleRate, int quota, boolean delete, boolean boot) {
        if (minutes < 1 || minutes > 180 || quota < 128 || quota > 32768
                || (bitrate != 64 && bitrate != 96 && bitrate != 128 && bitrate != 192 && bitrate != 256)
                || (sampleRate != 44100 && sampleRate != 48000))
            throw new IllegalArgumentException("Перевірте інтервал, якість і ліміт пам’яті");
        if (2 * StoragePolicy.segmentBudget(minutes, bitrate) > quota * StoragePolicy.MIB)
            throw new IllegalArgumentException("Для безперервного запису ліміт має вміщувати два фрагменти із запасом");
        prefs.edit().putInt("minutes", minutes).putInt("bitrate", bitrate)
                .putInt("sample_rate", sampleRate).putInt("quota_mib", quota)
                .putBoolean("delete_oldest", delete).putBoolean("resume_boot", boot).commit();
    }
    void status(String text, long segmentStart) {
        prefs.edit().putString("status", text).putLong("segment_start", segmentStart)
                .putLong("heartbeat", System.currentTimeMillis()).apply();
    }
}
