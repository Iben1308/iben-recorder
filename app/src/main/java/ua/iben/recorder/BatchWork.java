package ua.iben.recorder;

import android.content.Context;
import android.net.Uri;
import android.os.Process;
import java.util.ArrayList;
import java.util.List;

/** Sequential, explicitly requested operations. A failure never authorizes deleting another copy. */
final class BatchWork {
    enum Action { LISTENED, UNLISTENED, PIN, UNPIN, DELETE_LOCAL, DELETE_CLOUD, DELETE_BOTH, EXPORT }
    static final class Result {
        int completed,skipped,failed,remaining;
        final List<String> errors=new ArrayList<>();
        String text() {
            String value=I18n.s("batch_result",completed,skipped,failed,remaining);
            for(String error:errors)value+="\n"+error;
            return value;
        }
    }
    interface Listener { void progress(int current,int total,String name);void finished(Result result); }
    private final Context app;
    private final List<RecordingFiles.Item> items;
    private final Action action;
    private final Uri destination;
    private final Listener listener;
    private volatile boolean canceled;
    private volatile DavClient active;
    private Thread thread;
    BatchWork(Context context,List<RecordingFiles.Item> items,Action action,Uri destination,Listener listener) {
        app=context.getApplicationContext();this.items=new ArrayList<>(items);this.action=action;
        this.destination=destination;this.listener=listener;
    }
    void start() { thread=new Thread(this::run,"iben-batch");thread.start(); }
    void cancel() {
        canceled=true;DavClient client=active;if(client!=null)client.cancel();
        Thread t=thread;if(t!=null)t.interrupt();
    }
    private void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        Result result=new Result();result.remaining=items.size();
        boolean cloudAction=action==Action.DELETE_CLOUD || action==Action.DELETE_BOTH;
        boolean deleting=cloudAction || action==Action.DELETE_LOCAL;
        CloudSettings cloud=new CloudSettings(app);
        try(RecordingFiles files=new RecordingFiles(app,new Config(app))) {
            CloudSettings.Connection connection=cloudAction ? cloud.connection() : null;
            for(int i=0;i<items.size() && !canceled;i++) {
                if(cloudAction && cloud.revision()!=connection.revision) { canceled=true;break; }
                RecordingFiles.Item item=items.get(i);
                listener.progress(i+1,items.size(),item.name);
                try {
                    if(deleting && files.importantNow(item.id)) { result.skipped++;continue; }
                    if(action==Action.PIN || action==Action.UNPIN) {
                        if(files.important(item,action==Action.PIN))result.completed++;else result.skipped++;
                    } else if(action==Action.LISTENED || action==Action.UNLISTENED) {
                        if(files.listened(item,action==Action.LISTENED))result.completed++;else result.skipped++;
                    } else if(action==Action.EXPORT) {
                        DocumentTransfers.exportToFolder(app,item,destination);result.completed++;
                    } else if(action==Action.DELETE_LOCAL) {
                        LocalDeletion.Result local=files.deleteLocal(item,true,false);
                        if(local==LocalDeletion.Result.DELETED)result.completed++;
                        else if(local==LocalDeletion.Result.PROTECTED || local==LocalDeletion.Result.MISSING)result.skipped++;
                        else throw new java.io.IOException(local==LocalDeletion.Result.BUSY ? I18n.s("delete_busy") : I18n.s("delete_changed"));
                    } else {
                        try(RecordingFiles.CloudDelete deletion=files.prepareCloudDelete(item,connection.target.key,false);
                                DavClient client=connection.client((phase,done,total) -> { })) {
                            active=client;
                            if(canceled)client.cancel();
                            client.deleteRecording(deletion.remoteName,deletion.size,
                                    () -> !canceled && cloud.revision()==connection.revision && files.cloudDeletionAllowed(deletion));
                            LocalDeletion.Result local=files.cloudDeleted(deletion,item,action==Action.DELETE_BOTH && !canceled);
                            if(action==Action.DELETE_BOTH && local!=LocalDeletion.Result.DELETED && local!=LocalDeletion.Result.MISSING)
                                throw new java.io.IOException(I18n.s("cloud_delete_partial"));
                            result.completed++;
                        } finally { active=null; }
                    }
                } catch(Exception e) {
                    result.failed++;
                    String reason=e instanceof DavClient.Conflict ? I18n.s("cloud_delete_conflict")
                            : canceled ? I18n.s("batch_interrupted") : e.getMessage()==null ? I18n.s("unavailable") : I18n.tr(e.getMessage());
                    if(result.errors.size()<5)result.errors.add(item.name+": "+reason);
                    AppLog.write(app,"Batch: "+item.name+" · "+e.getClass().getSimpleName());
                } finally { result.remaining=items.size()-i-1; }
            }
        } catch(Exception e) {
            if(result.errors.size()<5)result.errors.add(I18n.s("batch_interrupted")+" · "+e.getClass().getSimpleName());
        }
        Thread.interrupted(); // Do not carry cancellation into the scheduling/database completion work.
        SyncScheduler.kick(app);
        listener.finished(result);
    }
}
