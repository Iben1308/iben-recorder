package ua.iben.recorder;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.os.Environment;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Private staging and public output stay on the same primary volume (Android 8.1). */
final class RecordingFiles implements AutoCloseable {
    static final class Part {
        final String id;
        final long start;
        final String zone;
        final File file;
        Part(String id, long start, String zone, File file) {
            this.id = id; this.start = start; this.zone = zone; this.file = file;
        }
    }
    private final Context context;
    private final Config config;
    private final String owner;
    private final RecordIndex index;
    // Shared by recording, upload, and settings instances in this process.
    static final Object LOCK = new Object();
    private static final Map<String, Integer> READERS = new HashMap<>();
    static final class Lease implements AutoCloseable {
        final File file;
        private boolean closed;
        Lease(File file) { this.file = file; }
        @Override public void close() {
            synchronized (LOCK) {
                if (closed) return;
                closed = true;
                String key = file.getAbsolutePath();
                int count = READERS.getOrDefault(key, 1) - 1;
                if (count == 0) READERS.remove(key); else READERS.put(key, count);
            }
        }
    }
    static Lease lease(File file) throws IOException {
        synchronized (LOCK) {
            if (!file.isFile()) throw new IOException("Локальний запис уже недоступний");
            String key = file.getAbsolutePath();
            READERS.put(key, READERS.getOrDefault(key, 0) + 1);
            return new Lease(file);
        }
    }
    final File pendingDir;
    final File readyDir;
    private final File legacyDir;

    RecordingFiles(Context context, Config config) throws IOException {
        this.context = context; this.config = config; owner = config.owner();
        File external = context.getExternalFilesDir(null);
        if (external == null) throw new IOException("Сховище телефона недоступне");
        pendingDir = new File(external, ".temp");
        legacyDir = new File(external, "pending");
        readyDir = publicDirectory();
        mkdir(pendingDir); mkdir(readyDir);
        File noMedia = new File(pendingDir, ".nomedia");
        if (!noMedia.exists() && !noMedia.createNewFile()) throw new IOException("Не вдалося приховати робочу папку");
        index = new RecordIndex(context);
    }

    @SuppressWarnings("deprecation")
    static File publicDirectory() {
        // Keep this prototype isolated from the Android 10 app and its archive.
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "IbenRecorder81");
    }
    private static void mkdir(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Не вдалося створити папку: " + directory);
    }
    private static boolean inside(File file, File directory) throws IOException {
        return file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile());
    }
    private File temp(String id) throws IOException {
        if (id == null || !id.matches("[0-9a-f-]{36}")) throw new IOException("Некоректний ідентифікатор запису");
        File f = new File(pendingDir, id + ".part");
        if (!inside(f, pendingDir)) throw new IOException("Сторонній робочий шлях");
        return f;
    }
    private File published(String name) throws IOException {
        if (!RecordingNames.validPublishedName(name)) throw new IOException("Некоректна назва запису");
        File f = new File(readyDir, name);
        if (!inside(f, readyDir)) throw new IOException("Сторонній шлях запису");
        return f;
    }
    private boolean legacyOwned(File f, File directory) throws IOException {
        return f.isFile() && StoragePolicy.ownedName(f.getName(), owner) && inside(f, directory);
    }

    Part create(long start, String zone, long budget) throws IOException {
        synchronized (LOCK) {
            if (!ensureRoom(budget)) throw new IOException("Недостатньо місця для наступного фрагмента");
            String id = UUID.randomUUID().toString();
            File f = temp(id);
            index.create(id, start, zone);
            if (!f.createNewFile()) { index.remove(id); throw new IOException("Не вдалося створити робочий файл"); }
            return new Part(id, start, zone, f);
        }
    }

    /** Caller has already stopped AND released the muxer. No media operation holds the quota lock. */
    void finish(Part part) throws IOException {
        long duration = duration(part.file);
        if (duration <= 0) {
            synchronized (LOCK) { index.state(part.id, RecordIndex.FAILED); }
            throw new IOException("Фрагмент не має читабельного аудіо; залишено у .temp");
        }
        File target;
        synchronized (LOCK) {
            int duplicate = 0;
            String name;
            do {
                name = RecordingNames.format(part.start, part.zone, duration, duplicate++);
                target = published(name);
            } while (target.exists() || index.nameReserved(name, part.id));
            // Persist the target BEFORE rename, so a crash after rename is recoverable.
            index.prepared(part.id, duration, name);
            move(part.file, target);
            index.state(part.id, RecordIndex.PUBLISHED);
        }
        scan(target);
        AppLog.write(context, "Готовий файл: " + target.getName() + " (" + target.length() + " байтів)");
        SyncScheduler.kick(context);
    }
    void failed(Part part) {
        synchronized (LOCK) { index.state(part.id, RecordIndex.FAILED); }
    }

    /** Run only with all capture, writer and finalizer threads stopped. */
    void recover() throws IOException {
        for (RecordIndex.Entry e : index.all()) {
            File source = temp(e.id);
            if (e.state == RecordIndex.PUBLISHED) {
                if (!published(e.finalName).exists()) index.remove(e.id);
            } else if (e.state != RecordIndex.FAILED) {
                if (e.state == RecordIndex.READY && !source.exists() && published(e.finalName).isFile()) {
                    index.state(e.id, RecordIndex.PUBLISHED);
                    scan(published(e.finalName));
                } else if (source.exists()) {
                    if (duration(source) > 0) finish(new Part(e.id, e.start, e.zone, source));
                    else { index.state(e.id, RecordIndex.FAILED); AppLog.write(context, "Незавершений файл ізольовано у .temp"); }
                } else index.remove(e.id);
            } else if (!source.exists()) index.remove(e.id);
        }
        // v0.1 files keep their names: do not cause duplicate uploads by renaming an archive.
        if (legacyDir.isDirectory()) {
            File[] old = legacyDir.listFiles();
            if (old == null) throw new IOException("Немає доступу до старої робочої папки");
            for (File f : old) {
                if (!legacyOwned(f, legacyDir) || !(f.getName().endsWith(".part") || f.getName().endsWith(".ready"))) continue;
                String stem = f.getName().substring(0, f.getName().lastIndexOf('.'));
                if (duration(f) > 0) { File target = new File(readyDir, stem + ".m4a"); move(f, target); scan(target); }
                else move(f, new File(legacyDir, stem + ".failed"));
            }
        }
    }

    boolean ensureRoom(long required) throws IOException {
        synchronized (LOCK) {
            Snapshot s = snapshot();
            StoragePolicy.Plan plan = StoragePolicy.plan(s.closed, s.pending, required,
                    config.quota(), readyDir.getUsableSpace(), config.deleteOldest());
            if (!plan.enoughSpace) return false;
            for (String path : plan.deleteIds) {
                // Only closed files with a matching verified cloud receipt can reach this list.
                File f = new File(path);
                if (!f.delete()) throw new IOException("Не вдалося видалити старий фрагмент");
                String id = s.rowIds.get(path);
                if (id != null) index.remove(id);
                scan(f);
                AppLog.write(context, "Ліміт пам’яті: видалено " + f.getName());
            }
            s = snapshot();
            return StoragePolicy.plan(s.closed, s.pending, required, config.quota(),
                    readyDir.getUsableSpace(), false).enoughSpace;
        }
    }
    long[] stats() throws IOException {
        synchronized (LOCK) {
            Snapshot s = snapshot();
            long used = s.pending;
            for (StoragePolicy.Entry f : s.closed) used += f.bytes;
            return new long[]{used, readyDir.getUsableSpace(), s.closed.size()};
        }
    }
    private Snapshot snapshot() throws IOException {
        Snapshot s = new Snapshot();
        String target = new CloudSettings(context).targetKey();
        for (RecordIndex.Entry e : index.all()) {
            File f = e.state == RecordIndex.PUBLISHED ? published(e.finalName) : temp(e.id);
            if (e.state == RecordIndex.PUBLISHED || e.state == RecordIndex.FAILED) {
                if (f.isFile()) {
                    if (e.state == RecordIndex.PUBLISHED && isVerified(e, f, target) && !READERS.containsKey(f.getAbsolutePath())) s.add(f, e.id);
                    else s.pending += f.length();
                }
                else index.remove(e.id);
            } else {
                s.pending += f.length();
                if (e.state == RecordIndex.READY && !f.exists()) s.pending += published(e.finalName).length();
            }
        }
        File[] output = readyDir.listFiles();
        if (output == null) throw new IOException("Немає доступу до папки записів");
        for (File f : output) if (legacyOwned(f, readyDir) && f.getName().endsWith(".m4a")) s.pending += f.length();
        if (legacyDir.isDirectory()) {
            File[] old = legacyDir.listFiles();
            if (old == null) throw new IOException("Немає доступу до старої робочої папки");
            for (File f : old) if (legacyOwned(f, legacyDir)) {
                s.pending += f.length();
            }
        }
        return s;
    }
    private boolean isVerified(RecordIndex.Entry entry, File file, String target) {
        return TransferPolicy.verified(target, entry.verifiedTarget, file.length(), entry.verifiedSize,
                file.lastModified(), entry.verifiedModified, entry.verifiedHash);
    }
    static final class Upload {
        final String id;
        final File file;
        final String name;
        final String remoteName;
        Upload(RecordIndex.Entry e, File file) {
            id = e.id; this.file = file; name = e.finalName;
            remoteName = e.remoteName == null ? e.finalName : e.remoteName;
        }
        String collisionName() {
            return name.substring(0, name.length() - 4) + "_" + id + ".m4a";
        }
    }
    static final class Item {
        final String id, name;
        final File file;
        final long start, duration, bytes;
        final boolean uploaded;
        Item(RecordIndex.Entry e, File f, boolean uploaded) {
            id = e.id; name = e.finalName; file = f; start = e.start; duration = e.duration;
            bytes = f.length(); this.uploaded = uploaded;
        }
    }
    List<Item> recordings() throws IOException {
        synchronized (LOCK) {
            List<Item> result = new ArrayList<>();
            String target = new CloudSettings(context).targetKey();
            for (RecordIndex.Entry e : index.all()) if (e.state == RecordIndex.PUBLISHED) {
                File f = published(e.finalName);
                if (f.isFile()) result.add(new Item(e, f, isVerified(e, f, target)));
            }
            java.util.Collections.reverse(result);
            return result;
        }
    }
    List<Upload> uploads(String target) throws IOException {
        synchronized (LOCK) {
            List<Upload> list = new ArrayList<>();
            for (RecordIndex.Entry e : index.all()) if (e.state == RecordIndex.PUBLISHED) {
                File f = published(e.finalName);
                if (f.isFile() && !isVerified(e, f, target)) list.add(new Upload(e, f));
            }
            return list;
        }
    }
    void uploadName(Upload file, String remoteName) {
        synchronized (LOCK) { index.remoteName(file.id, remoteName); }
    }
    void uploaded(Upload file, String target, String remoteName, DavClient.Receipt receipt) throws IOException {
        synchronized (LOCK) {
            // The file was protected throughout transfer; also reject external modification.
            if (!new CloudSettings(context).targetKey().equals(target)) throw new IOException("Папку призначення змінено; попереднє підтвердження не застосовано");
            if (!file.file.isFile() || file.file.length() != receipt.size || file.file.lastModified() != receipt.modified)
                throw new IOException("Локальний файл змінився; його не дозволено видаляти");
            index.verified(file.id, target, receipt, remoteName);
        }
    }
    private static final class Snapshot {
        final List<StoragePolicy.Entry> closed = new ArrayList<>();
        final Map<String, String> rowIds = new HashMap<>();
        long pending;
        void add(File f, String id) {
            closed.add(new StoragePolicy.Entry(f.getAbsolutePath(), f.length(), f.lastModified()));
            if (id != null) rowIds.put(f.getAbsolutePath(), id);
        }
    }
    private void scan(File f) {
        MediaScannerConnection.scanFile(context, new String[]{f.getAbsolutePath()}, new String[]{"audio/mp4"}, null);
    }
    private static void move(File source, File target) throws IOException {
        if (target.exists() || !source.renameTo(target)) throw new IOException("Не вдалося опублікувати файл: " + source.getName());
    }
    private static long duration(File f) {
        if (!f.isFile() || f.length() == 0) return 0;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(f.getAbsolutePath());
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value == null ? 0 : Long.parseLong(value);
        } catch (Exception e) { return 0; }
        finally { try { retriever.release(); } catch (Exception ignored) { } }
    }
    @Override public void close() { index.close(); }
}
