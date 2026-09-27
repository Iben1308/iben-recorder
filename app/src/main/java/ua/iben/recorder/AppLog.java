package ua.iben.recorder;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class AppLog {
    static synchronized void write(Context context, String text) {
        Log.i("IbenRecorder", text);
        try {
            File file = new File(context.getFilesDir(), "events.log");
            if (file.length() > 65536) {
                File previous = new File(context.getFilesDir(), "events.previous.log");
                if (previous.exists() && !previous.delete()) return;
                if (!file.renameTo(previous)) return;
            }
            String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
                    .format(new Date()) + " " + text + "\n";
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) { Log.w("IbenRecorder", "Log write failed", e); }
    }
    static synchronized String read(Context context) {
        try {
            File file = new File(context.getFilesDir(), "events.log");
            return file.exists() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                    : "Журнал поки порожній.";
        } catch (Exception e) { return "Не вдалося прочитати журнал: " + e.getMessage(); }
    }
}
