package ua.iben.recorder;

import android.content.Context;
import android.content.Intent;
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
    private static final java.util.concurrent.ConcurrentHashMap<Thread,Transfer> ACTIVE=new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Uri,Integer> GRANTS=new java.util.HashMap<>();
    static synchronized void retain(Context context,Uri uri){
        if(uri==null)return;int existing=0;
        for(android.content.UriPermission p:context.getContentResolver().getPersistedUriPermissions())if(p.getUri().equals(uri))existing=(p.isReadPermission()?Intent.FLAG_GRANT_READ_URI_PERMISSION:0)|(p.isWritePermission()?Intent.FLAG_GRANT_WRITE_URI_PERMISSION:0);
        int flags=Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION;try{context.getContentResolver().takePersistableUriPermission(uri,flags);}
        catch(SecurityException e){flags=Intent.FLAG_GRANT_READ_URI_PERMISSION;try{context.getContentResolver().takePersistableUriPermission(uri,flags);}catch(SecurityException ignored){return;}}
        GRANTS.put(uri,flags & ~existing);
    }
    static synchronized void release(Context context,Uri uri){
        Integer flags=GRANTS.remove(uri);if(flags!=null && flags!=0)try{context.getContentResolver().releasePersistableUriPermission(uri,flags);}catch(SecurityException ignored){}
    }
    static void cancel(Thread thread){if(thread==null)return;thread.interrupt();Transfer active=ACTIVE.get(thread);if(active!=null)active.cancel();}
    private static final class Transfer implements AutoCloseable {
        final Thread thread=Thread.currentThread();volatile DavClient client;volatile boolean canceled;
        Transfer(){ACTIVE.put(thread,this);}
        void check()throws IOException{if(canceled || thread.isInterrupted())throw new java.io.InterruptedIOException();}
        void cancel(){canceled=true;thread.interrupt();DavClient dav=client;if(dav!=null)dav.cancel();}
        public void close(){ACTIVE.remove(thread,this);DavClient dav=client;if(dav!=null)dav.close();}
    }
    static void export(Context context,String id,Uri destination)throws IOException{
        try(RecordingFiles files=new RecordingFiles(context,new Config(context))){
            RecordingFiles.Item item=files.find(id);if(item==null)throw new IOException(I18n.s("unavailable"));export(context,item,destination);
        }
    }
    private static void export(Context context,RecordingFiles.Item item,Uri destination)throws IOException{
        try(Transfer transfer=new Transfer();RecordingFiles.Lease held=RecordingFiles.hold(item,transfer::cancel)){
            transfer.check();
            try(OutputStream output=context.getContentResolver().openOutputStream(destination,"wt")){
                if(output==null)throw new IOException(I18n.s("document_error"));
                if(item.local){
                    try(InputStream input=new FileInputStream(item.file)){
                        if(BoundedCopy.copy(input,output,item.bytes)!=item.bytes || item.file.length()!=item.bytes)throw new IOException(I18n.s("delete_changed"));
                    }
                }else{
                    CloudSettings cloud=new CloudSettings(context);CloudSettings.Connection connection=cloud.connection();
                    if(!connection.target.key.equals(item.cloudTarget))throw new IOException(I18n.s("connection_changed"));
                    DavClient client=connection.client((phase,done,total)->{if(cloud.revision()!=connection.revision)transfer.cancel();});transfer.client=client;transfer.check();
                    DavClient.RemoteInfo info=client.inspect(item.remoteName);client.download(item.remoteName,info,output);
                    if(cloud.revision()!=connection.revision)throw new IOException(I18n.s("connection_changed"));
                }
                transfer.check();
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
        try { export(context,item,created);copied=true; }
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
                if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException();
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
                    if (old.isFile()) sourceLease = RecordingFiles.lease(old,Thread.currentThread()::interrupt);
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
                        if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException();
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
