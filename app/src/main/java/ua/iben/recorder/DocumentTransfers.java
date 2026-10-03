package ua.iben.recorder;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/** Explicit user-chosen documents only. No broad storage permission on scoped-storage devices. */
final class DocumentTransfers {
    private static final java.util.concurrent.atomic.AtomicBoolean RESTORING = new java.util.concurrent.atomic.AtomicBoolean();
    static boolean restoring() { return RESTORING.get(); }
    static void export(Context context, String id, Uri destination) throws IOException {
        Config config = new Config(context);
        RecordingFiles.Lease lease = null;
        try (RecordingFiles files = new RecordingFiles(context, config)) {
            synchronized (RecordingFiles.LOCK) {
                for (RecordingFiles.Item item : files.recordings()) if (item.id.equals(id)) { lease = RecordingFiles.lease(item.file); break; }
            }
            if (lease == null) throw new IOException(I18n.s("unavailable"));
            try (RecordingFiles.Lease held = lease;
                 InputStream input = new FileInputStream(held.file);
                 OutputStream output = context.getContentResolver().openOutputStream(destination, "wt")) {
                long size = held.file.length();
                if (BoundedCopy.copy(input, output, size) != size) throw new IOException("File changed during export");
            }
        }
    }
    static void exportToFolder(Context context,RecordingFiles.Item item,Uri tree) throws IOException {
        String parentId=DocumentsContract.getTreeDocumentId(tree);
        Uri parent=DocumentsContract.buildDocumentUriUsingTree(tree,parentId);
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parentId);
        java.util.Set<String> names=new java.util.HashSet<>();
        try(Cursor cursor=context.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)) {
            if(cursor==null)throw new IOException(I18n.s("document_error"));
            while(cursor.moveToNext())names.add(cursor.getString(0));
        }
        String name=item.name;
        if(names.contains(name))name=name.substring(0,name.length()-4)+"_"+java.util.UUID.randomUUID()+".m4a";
        if(Thread.currentThread().isInterrupted())throw new IOException(I18n.s("batch_interrupted"));
        Uri created=DocumentsContract.createDocument(context.getContentResolver(),parent,"audio/mp4",name);
        if(created==null)throw new IOException(I18n.s("document_error"));
        boolean copied=false;
        try { export(context,item.id,created);copied=true; }
        finally {
            if(!copied)try { DocumentsContract.deleteDocument(context.getContentResolver(),created); }
            catch(Exception ignored) { }
        }
    }
    static int restore(Context context, Uri tree) throws IOException {
        // This only reconnects entries in this installation's existing ownership ledger.
        // It cannot import another application's recordings or make them eligible for cleanup.
        if (Platform.publicStorage()) return 0;
        String directory = DocumentsContract.getTreeDocumentId(tree);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, directory);
        Map<String, Uri> documents = new HashMap<>();
        String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE};
        try (Cursor cursor = context.getContentResolver().query(children, columns, null, null, null)) {
            if (cursor == null) throw new IOException(I18n.s("document_error"));
            while (cursor.moveToNext()) {
                String name = cursor.getString(1);
                if (RecordingNames.validPublishedName(name)
                        && !DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))) {
                    if (documents.containsKey(name)) throw new IOException("Ambiguous document name");
                    documents.put(name, DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)));
                }
            }
        }
        if (!RESTORING.compareAndSet(false, true)) throw new IOException(I18n.s("restore_busy"));
        int restored = 0;
        Config config = new Config(context);
        try (RecordingFiles files = new RecordingFiles(context, config); RecordIndex index = new RecordIndex(context)) {
            for (RecordIndex.Entry entry : index.all()) {
                if ((entry.state != RecordIndex.PUBLISHED && entry.state != RecordIndex.READY) || !documents.containsKey(entry.finalName)) continue;
                File target = new File(files.readyDir, entry.finalName);
                File staging = new File(files.pendingDir, entry.id + ".restore");
                long budget;
                RecordingFiles.Lease sourceLease = null;
                synchronized (RecordingFiles.LOCK) {
                    if (target.exists()) continue;
                    if (config.wanted() || config.prefs.getBoolean("engine_active", false) || ScheduleManager.standby(config))
                        throw new IOException(I18n.s("restore_stop"));
                    if (staging.exists() && !staging.delete()) throw new IOException(I18n.s("document_error"));
                    long usable = Math.max(0, files.readyDir.getUsableSpace() - 256L * 1048576L);
                    budget = Math.min(usable, Math.max(0, config.quota() - files.stats()[0]));
                    if (budget <= 0) throw new IOException(I18n.s("restore_space"));
                    File old = new File(RecordingFiles.publicDirectory(), entry.finalName);
                    if (old.isFile()) sourceLease = RecordingFiles.lease(old);
                }
                // Never hold the recording/index lock across provider I/O: cloud document
                // providers can block for minutes. New recording allocations are paused instead.
                try (RecordingFiles.Lease held = sourceLease) {
                    try (InputStream input = context.getContentResolver().openInputStream(documents.get(entry.finalName));
                         FileOutputStream output = new FileOutputStream(staging)) {
                        if (BoundedCopy.copy(input, output, budget) == 0) throw new IOException("Empty recording");
                        output.getFD().sync();
                    }
                    synchronized (RecordingFiles.LOCK) {
                        index.clearVerification(entry.id);
                        if (target.exists() || !staging.renameTo(target)) throw new IOException(I18n.s("document_error"));
                        index.state(entry.id, RecordIndex.PUBLISHED);
                        restored++;
                    }
                } finally { if (staging.exists()) staging.delete(); }
            }
        } finally { RESTORING.set(false); }
        SyncScheduler.kick(context);
        return restored;
    }
}
