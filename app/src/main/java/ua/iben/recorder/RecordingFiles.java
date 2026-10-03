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

/** Atomic local publication. API 27–28 uses Music; scoped-storage devices use app-owned files. */
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
        readyDir = outputDirectory(context);
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
    static File outputDirectory(Context context) {
        if (Platform.publicStorage()) return publicDirectory();
        File external = context.getExternalFilesDir(null);
        return new File(external == null ? context.getFilesDir() : external, "recordings");
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
        // Indexed public files may still be accessible after an OS upgrade. Never lose their ledger.
        if (!Platform.publicStorage() && !f.exists()) {
            File old = new File(publicDirectory(), name);
            if (old.isFile() && old.canRead() && inside(old, publicDirectory())) return old;
        }
        return f;
    }
    private boolean legacyOwned(File f, File directory) throws IOException {
        return f.isFile() && StoragePolicy.ownedName(f.getName(), owner) && inside(f, directory);
    }

    Part create(long start, String zone, long budget) throws IOException {
        synchronized (LOCK) {
            if (!ensureRoom(budget)) throw new StorageFullException("Недостатньо місця для наступного фрагмента");
            String id = UUID.randomUUID().toString();
            File f = temp(id);
            index.create(id, start, zone);
            if (!f.createNewFile()) { index.remove(id); throw new IOException("Не вдалося створити робочий файл"); }
            return new Part(id, start, zone, f);
        }
    }

    /** Caller has already stopped AND released the muxer. No media operation holds the quota lock. */
    void finish(Part part) throws IOException { finish(part, null); }
    void finish(Part part, float[] envelope) throws IOException {
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
                target = new File(readyDir, name);
            } while (target.exists() || index.nameReserved(name, part.id));
            // Persist the target BEFORE rename, so a crash after rename is recoverable.
            index.prepared(part.id, duration, name);
            move(part.file, target);
            index.state(part.id, RecordIndex.PUBLISHED);
        }
        if (envelope != null && envelope.length > 0) try {
            WaveformAnalyzer.store(context, target, new WaveformAnalyzer.Data(duration, envelope));
        } catch (Exception e) { AppLog.write(context, "Waveform cache: " + e.getClass().getSimpleName()); }
        scan(target);
        AppLog.write(context, "Готовий файл: " + target.getName() + " (" + target.length() + " байтів)");
        SyncScheduler.kick(context);
    }
    void failed(Part part) {
        synchronized (LOCK) { index.state(part.id, RecordIndex.FAILED); }
    }

    /** Run only with all capture, writer and finalizer threads stopped. */
    void recover() throws IOException {
        // Explicit folder imports retain their original source. Partial copies can be retried.
        if (!DocumentTransfers.restoring()) {
            File[] interrupted = pendingDir.listFiles();
            if (interrupted != null) for (File f : interrupted)
                if (f.getName().matches("[0-9a-f-]{36}\\.restore")) f.delete();
        }
        for (RecordIndex.Entry e : index.all()) {
            File source = temp(e.id);
            if (e.state == RecordIndex.PUBLISHED) {
                // Missing can mean scoped-storage access was revoked after an OS upgrade.
                // Keep ownership and receipts so an explicit folder import can recover the row.
            } else if (e.state != RecordIndex.FAILED) {
                if (e.state == RecordIndex.READY && !source.exists() && published(e.finalName).isFile()) {
                    index.state(e.id, RecordIndex.PUBLISHED);
                    scan(published(e.finalName));
                } else if (source.exists()) {
                    if (duration(source) > 0) finish(new Part(e.id, e.start, e.zone, source));
                    else { index.state(e.id, RecordIndex.FAILED); AppLog.write(context, "Незавершений файл ізольовано у .temp"); }
                } else if (e.state != RecordIndex.READY) index.remove(e.id);
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
            if (DocumentTransfers.restoring()) throw new IOException(I18n.s("restore_busy"));
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
                    if (e.state == RecordIndex.PUBLISHED && !e.important && isVerified(e, f, target) && !READERS.containsKey(f.getAbsolutePath())) s.add(f, e.id);
                    else s.pending += f.length();
                }
                // Keep inaccessible published rows; absence is not proof of deletion.
            } else {
                s.pending += f.length();
                if (e.state == RecordIndex.READY && !f.exists()) s.pending += published(e.finalName).length();
            }
        }
        File[] staged = pendingDir.listFiles();
        if (staged != null) for (File f : staged) if (f.getName().endsWith(".restore")) s.pending += f.length();
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
        return !entry.cloudDeletes.containsKey(target) && TransferPolicy.verified(target, entry.verifiedTarget, file.length(), entry.verifiedSize,
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
        final long start, duration, bytes, modified;
        final boolean uploaded;
        final String cloudTarget;
        final int cloudDeleteState;
        long position;
        boolean listened;
        boolean important;
        String heardRanges;
        Item(RecordIndex.Entry e, File f, boolean uploaded, String target) {
            id = e.id; name = e.finalName; file = f; start = e.start; duration = e.duration;
            bytes = f.length(); modified = f.lastModified(); this.uploaded = uploaded;
            cloudTarget = target;
            cloudDeleteState = !e.cloudDeletes.containsKey(target) ? 0 : e.cloudDeletes.get(target) ? 2 : 1;
            position=PlaybackProgress.resume(e.position,duration);listened=e.listened;heardRanges=e.heardRanges;important=e.important;
        }
    }
    List<Item> recordings() throws IOException {
        synchronized (LOCK) {
            List<Item> result = new ArrayList<>();
            String target = new CloudSettings(context).targetKey();
            for (RecordIndex.Entry e : index.all()) if (e.state == RecordIndex.PUBLISHED) {
                File f = published(e.finalName);
                if (f.isFile()) result.add(new Item(e, f, isVerified(e, f, target), target));
            }
            java.util.Collections.reverse(result);
            return result;
        }
    }
    /** Only the explicitly confirmed, unchanged closed recording can be removed. No WebDAV DELETE. */
    LocalDeletion.Result deleteLocal(Item expected, boolean allowUnverified, boolean allowImportant) throws IOException {
        synchronized (LOCK) {
            if (DocumentTransfers.restoring()) return LocalDeletion.Result.BUSY;
            RecordIndex.Entry entry = null;
            for (RecordIndex.Entry candidate : index.all()) if (candidate.id.equals(expected.id)) { entry = candidate; break; }
            if (entry == null) return LocalDeletion.Result.MISSING;
            if (entry.state != RecordIndex.PUBLISHED) return LocalDeletion.Result.NOT_READY;
            if (entry.important && !allowImportant) return LocalDeletion.Result.PROTECTED;
            File file = published(entry.finalName);
            if (!entry.finalName.equals(expected.name) || !file.getCanonicalFile().equals(expected.file.getCanonicalFile()))
                return LocalDeletion.Result.CHANGED;
            LocalDeletion.Result result = LocalDeletion.remove(file, expected.bytes, expected.modified, true,
                    READERS.containsKey(file.getAbsolutePath()),
                    isVerified(entry, file, new CloudSettings(context).targetKey()), allowUnverified,entry.important,allowImportant);
            if (result == LocalDeletion.Result.DELETED) {
                index.remove(entry.id);
                if (entry.id.equals(config.prefs.getString("last_recording", ""))) config.prefs.edit().remove("last_recording").apply();
                WaveformAnalyzer.forget(context, file, expected.bytes, expected.modified);
                scan(file);
                AppLog.write(context, I18n.s("delete_log", file.getName()));
            }
            return result;
        }
    }
    static final class CloudDelete implements AutoCloseable {
        final String id, target, remoteName;
        final long size;
        final Lease lease;
        final boolean allowImportant;
        CloudDelete(RecordIndex.Entry entry, String target, Lease lease, boolean allowImportant) {
            this.allowImportant=allowImportant;
            id=entry.id; this.target=target; remoteName=entry.remoteName == null ? entry.finalName : entry.remoteName;
            size=entry.verifiedSize; this.lease=lease;
        }
        @Override public void close() { lease.close(); }
    }
    CloudDelete prepareCloudDelete(Item expected, String target, boolean allowImportant) throws IOException {
        synchronized (LOCK) {
            if (!target.equals(expected.cloudTarget) || !target.equals(new CloudSettings(context).targetKey()))
                throw new IOException(I18n.s("delete_changed"));
            if (DocumentTransfers.restoring()) throw new IOException(I18n.s("delete_busy"));
            for (RecordIndex.Entry entry : index.all()) if (entry.id.equals(expected.id)) {
                if (entry.state != RecordIndex.PUBLISHED) throw new IOException(I18n.s("delete_not_ready"));
                if(entry.important && !allowImportant)throw new IOException(I18n.s("important_protected"));
                File file=published(entry.finalName);
                if (!file.isFile() || !file.getCanonicalFile().equals(expected.file.getCanonicalFile())
                        || file.length()!=expected.bytes || file.lastModified()!=expected.modified)
                    throw new IOException(I18n.s("delete_changed"));
                if (READERS.containsKey(file.getAbsolutePath())) throw new IOException(I18n.s("delete_busy"));
                // Keep the original receipt after cloud deletion, to make a retry safe after a lost response.
                if (!TransferPolicy.verified(target, entry.verifiedTarget, file.length(), entry.verifiedSize,
                        file.lastModified(), entry.verifiedModified, entry.verifiedHash))
                    throw new IOException(I18n.s("cloud_delete_no_receipt"));
                Lease lease=lease(file);
                try {
                    index.beginCloudDelete(entry.id,target); // Durable before the first network operation.
                    return new CloudDelete(entry,target,lease,allowImportant);
                } catch (RuntimeException e) { lease.close(); throw e; }
            }
            throw new IOException(I18n.s("delete_missing"));
        }
    }
    boolean cloudDeletionAllowed(CloudDelete deletion) {
        synchronized(LOCK) { return deletion.allowImportant || !index.isImportant(deletion.id); }
    }
    boolean important(Item item, boolean value) {
        synchronized(LOCK) { return item.file.isFile() && index.important(item.id,value); }
    }
    boolean listened(Item item, boolean value) {
        synchronized(LOCK) { return item.file.isFile() && index.listened(item.id,value); }
    }
    boolean importantNow(String id) { synchronized(LOCK) { return index.isImportant(id); } }
    LocalDeletion.Result cloudDeleted(CloudDelete deletion, Item item, boolean removeLocal) throws IOException {
        synchronized (LOCK) {
            index.finishCloudDelete(deletion.id,deletion.target);
            if (!removeLocal) return null;
            deletion.close(); // Release our reader and delete under the same lock.
            return deleteLocal(item,true,deletion.allowImportant);
        }
    }
    void resumeCloudUpload(Item item) throws IOException {
        synchronized (LOCK) {
            if (!item.cloudTarget.equals(new CloudSettings(context).targetKey())) throw new IOException(I18n.s("delete_changed"));
            if (READERS.containsKey(item.file.getAbsolutePath())) throw new IOException(I18n.s("delete_busy"));
            index.resumeCloudUpload(item.id,item.cloudTarget);
        }
    }
    /** Claim a queued upload atomically against a user's deletion after the queue snapshot. */
    Lease leaseUpload(Upload upload, String target) throws IOException {
        synchronized (LOCK) {
            for (RecordIndex.Entry entry : index.all()) if (entry.id.equals(upload.id)) {
                if (entry.state != RecordIndex.PUBLISHED || entry.cloudDeletes.containsKey(target)) return null;
                File file = published(entry.finalName);
                if (!file.isFile() || !file.getCanonicalFile().equals(upload.file.getCanonicalFile())) return null;
                return lease(file);
            }
            return null;
        }
    }
    List<Upload> uploads(String target) throws IOException {
        synchronized (LOCK) {
            List<Upload> list = new ArrayList<>();
            for (RecordIndex.Entry e : index.all()) if (e.state == RecordIndex.PUBLISHED) {
                File f = published(e.finalName);
                if (f.isFile() && !e.cloudDeletes.containsKey(target) && !isVerified(e, f, target)) list.add(new Upload(e, f));
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
        if (!Platform.publicStorage()) return;
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
