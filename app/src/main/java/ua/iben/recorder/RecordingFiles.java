package ua.iben.recorder;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.os.Environment;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/** Android 10 only: private staging and public output live on the same primary volume. */
final class RecordingFiles {
    private final Context context;
    private final Config config;
    private final String owner;
    final File pendingDir;
    final File readyDir;

    RecordingFiles(Context context, Config config) throws IOException {
        this.context = context;
        this.config = config;
        this.owner = config.owner();
        File external = context.getExternalFilesDir(null);
        if (external == null) throw new IOException("Сховище телефона недоступне");
        pendingDir = new File(external, "pending");
        readyDir = publicDirectory();
        mkdir(pendingDir);
        mkdir(readyDir);
    }

    @SuppressWarnings("deprecation")
    static File publicDirectory() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "J7Recorder");
    }

    private static void mkdir(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Не вдалося створити папку: " + directory);
    }

    private boolean owned(File file, File directory) throws IOException {
        return file.isFile() && StoragePolicy.ownedName(file.getName(), owner)
                && file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile());
    }

    File newPart() throws IOException {
        SimpleDateFormat date = new SimpleDateFormat("yyyyMMdd'T'HHmmss_SSS", Locale.ROOT);
        date.setTimeZone(TimeZone.getTimeZone("UTC"));
        File file = new File(pendingDir, "j7_" + owner + "_" + date.format(new Date())
                + "_" + UUID.randomUUID().toString().substring(0, 8) + ".part");
        if (!file.createNewFile()) throw new IOException("Конфлікт імені нового запису");
        return file;
    }

    /** Recheck actual bytes after deletions: a failed unlink never counts as freed storage. */
    boolean ensureRoom(long required) throws IOException {
        Snapshot snapshot = snapshot();
        StoragePolicy.Plan plan = StoragePolicy.plan(snapshot.closed, snapshot.pending,
                required, config.quota(), readyDir.getUsableSpace(), config.deleteOldest());
        if (!plan.enoughSpace) return false;
        for (String path : plan.deleteIds) {
            File file = new File(path);
            boolean inOutput = owned(file, readyDir) && file.getName().endsWith(".m4a");
            boolean failed = owned(file, pendingDir) && file.getName().endsWith(".failed");
            if (!(inOutput || failed)) throw new IOException("Відхилено сторонній шлях видалення");
            if (file.delete()) {
                AppLog.write(context, "Ліміт пам’яті: видалено " + file.getName());
                MediaScannerConnection.scanFile(context, new String[]{path}, null, null);
            } else {
                throw new IOException("Не вдалося видалити старий фрагмент");
            }
        }
        snapshot = snapshot();
        return StoragePolicy.plan(snapshot.closed, snapshot.pending, required,
                config.quota(), readyDir.getUsableSpace(), false).enoughSpace;
    }

    long[] stats() throws IOException {
        Snapshot s = snapshot();
        long used = s.pending;
        for (StoragePolicy.Entry f : s.closed) used += f.bytes;
        return new long[]{used, readyDir.getUsableSpace(), s.closed.size()};
    }

    private Snapshot snapshot() throws IOException {
        Snapshot s = new Snapshot();
        File[] publicFiles = readyDir.listFiles();
        File[] pendingFiles = pendingDir.listFiles();
        if (publicFiles == null || pendingFiles == null) throw new IOException("Немає доступу до папки записів");
        for (File file : publicFiles) {
            if (owned(file, readyDir) && file.getName().endsWith(".m4a"))
                s.closed.add(new StoragePolicy.Entry(file.getAbsolutePath(), file.length(), file.lastModified()));
        }
        for (File file : pendingFiles) {
            if (!owned(file, pendingDir)) continue;
            if (file.getName().endsWith(".failed"))
                s.closed.add(new StoragePolicy.Entry(file.getAbsolutePath(), file.length(), file.lastModified()));
            else s.pending += file.length();
        }
        return s;
    }

    private static final class Snapshot {
        final List<StoragePolicy.Entry> closed = new ArrayList<>();
        long pending;
    }

    void finish(File part) throws IOException {
        if (part == null || !part.exists()) return;
        if (!owned(part, pendingDir)) throw new IOException("Сторонній файл у завершенні запису");
        if (validAudio(part)) {
            File ready = suffix(part, ".ready");
            move(part, ready);
            publish(ready);
        } else {
            move(part, suffix(part, ".failed"));
            AppLog.write(context, "Незавершений файл ізольовано: " + part.getName());
        }
    }

    /** Called only before a recording starts, never against an active .part file. */
    void recover() throws IOException {
        File[] files = pendingDir.listFiles();
        if (files == null) throw new IOException("Немає доступу до робочої папки");
        for (File file : files) {
            if (!owned(file, pendingDir)) continue;
            if (file.getName().endsWith(".part")) finish(file);
            else if (file.getName().endsWith(".ready")) publish(file);
        }
    }

    private void publish(File file) throws IOException {
        File target = new File(readyDir, suffix(file, ".m4a").getName());
        move(file, target);
        MediaScannerConnection.scanFile(context, new String[]{target.getAbsolutePath()},
                new String[]{"audio/mp4"}, null);
        AppLog.write(context, "Готовий файл: " + target.getName() + " (" + target.length() + " байтів)");
    }

    private static File suffix(File file, String suffix) {
        String name = file.getName();
        return new File(file.getParentFile(), name.substring(0, name.lastIndexOf('.')) + suffix);
    }

    private static void move(File source, File target) throws IOException {
        // Never copy a partially written file into the watched public directory.
        if (target.exists() || !source.renameTo(target))
            throw new IOException("Не вдалося перемістити завершений файл: " + source.getName());
    }

    private static boolean validAudio(File file) {
        if (file.length() == 0) return false;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return duration != null && Long.parseLong(duration) > 0;
        } catch (Exception e) { return false; }
        finally { try { retriever.release(); } catch (Exception ignored) { } }
    }
}
