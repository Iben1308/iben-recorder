package ua.iben.recorder;

import android.content.Context;
import android.net.Uri;
import android.os.Process;
import java.io.IOException;
import java.util.*;

/** Executed by OperationsService. Screen visibility does not own or cancel the work. */
final class BatchWork {
    enum Action { LISTENED, UNLISTENED, PIN, UNPIN, DELETE_LOCAL, DELETE_CLOUD, DELETE_BOTH, EXPORT }
    static final class Result {
        int completed,skipped,failed,remaining;final List<String> errors=new ArrayList<>();
        String text(){String value=I18n.s("batch_result",completed,skipped,failed,remaining);for(String error:errors)value+="\n"+error;return value;}
    }
    interface Listener{void progress(int current,int total,String name);void finished(Result result);}
    private final Context app;private final List<RecordingFiles.Item> items;private final Action action;
    private final Uri destination;private final Listener listener;private final boolean allowImportant,allowLocalFallback;
    private volatile boolean canceled;private volatile DavClient active;private volatile Thread thread;
    BatchWork(Context context,List<RecordingFiles.Item> items,Action action,Uri destination,boolean allowImportant,boolean allowLocalFallback,Listener listener){
        app=context.getApplicationContext();this.items=new ArrayList<>(items);this.action=action;this.destination=destination;
        this.allowImportant=allowImportant;this.allowLocalFallback=allowLocalFallback;this.listener=listener;
    }
    void cancel(){canceled=true;DavClient client=active;if(client!=null)client.cancel();Thread t=thread;if(t!=null)DocumentTransfers.cancel(t);}
    void run(){
        thread=Thread.currentThread();Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        Result result=new Result();result.remaining=items.size();
        boolean cloudAction=action==Action.DELETE_CLOUD || action==Action.DELETE_BOTH;
        boolean deleting=cloudAction || action==Action.DELETE_LOCAL;
        CloudSettings cloud=new CloudSettings(app);CloudSettings.Connection connection=null;
        try(RecordingFiles files=new RecordingFiles(app,new Config(app))){
            for(int i=0;i<items.size() && !canceled;i++){
                RecordingFiles.Item item=items.get(i);listener.progress(i+1,items.size(),item.name);
                try{
                    if(deleting && !allowImportant && files.importantNow(item.id)){result.skipped++;continue;}
                    if(action==Action.PIN || action==Action.UNPIN){if(files.important(item,action==Action.PIN))result.completed++;else result.skipped++;}
                    else if(action==Action.LISTENED || action==Action.UNLISTENED){if(files.listened(item,action==Action.LISTENED)){PlaybackService.marked(item.id,action==Action.LISTENED);result.completed++;}else result.skipped++;}
                    else if(action==Action.EXPORT){DocumentTransfers.exportToFolder(app,item,destination);result.completed++;}
                    else try(FileUseRegistry.Reservation reservation=RecordingFiles.reserve(item)){
                        // Reserve first, stop only this recording's readers, then wait for actual resource release.
                        // A timeout keeps the original intact instead of unlinking a live decoder/upload.
                        reservation.stopAndAwait(10000);if(canceled)throw new java.io.InterruptedIOException();
                        if(action==Action.DELETE_LOCAL || !item.hasCloud){
                            if(!item.local){result.skipped++;continue;}
                            if(cloudAction && !allowLocalFallback)throw new IOException(I18n.s("local_only_warning"));
                            LocalDeletion.Result local=files.deleteLocal(item,true,allowImportant);
                            if(local==LocalDeletion.Result.DELETED)result.completed++;
                            else if(local==LocalDeletion.Result.PROTECTED || local==LocalDeletion.Result.MISSING)result.skipped++;
                            else throw new IOException(I18n.s(local==LocalDeletion.Result.BUSY?"processing_stop_failed":"delete_changed"));
                        }else{
                            if(connection==null)connection=cloud.connection();
                            if(cloud.revision()!=connection.revision || !item.cloudTarget.equals(connection.target.key))throw new IOException(I18n.s("connection_changed"));
                            CloudSettings.Connection chosen=connection;
                            try(RecordingFiles.CloudDelete deletion=files.prepareCloudDelete(item,chosen.target.key,allowImportant);
                                DavClient client=chosen.client((phase,done,total)->{})){
                                active=client;if(canceled)client.cancel();
                                client.deleteRecording(deletion.remoteName,deletion.size,
                                        ()->!canceled && cloud.revision()==chosen.revision && files.cloudDeletionAllowed(deletion));
                                LocalDeletion.Result local=files.cloudDeleted(deletion,item,action==Action.DELETE_BOTH && !canceled);
                                if(action==Action.DELETE_BOTH && item.local && local!=LocalDeletion.Result.DELETED && local!=LocalDeletion.Result.MISSING)
                                    throw new IOException(I18n.s("cloud_delete_partial"));
                                result.completed++;
                            }finally{active=null;}
                        }
                    }
                }catch(Exception e){
                    result.failed++;String reason=e instanceof DavClient.Conflict?I18n.s("cloud_delete_conflict"):
                            canceled?I18n.s("batch_interrupted"):e.getMessage()==null?I18n.s("operation_failed"):I18n.tr(e.getMessage());
                    if(result.errors.size()<5)result.errors.add(item.name+": "+reason);
                    AppLog.write(app,"Operation "+action+": "+item.name+" · "+e.getClass().getSimpleName());
                }finally{result.remaining=items.size()-i-1;}
            }
        }catch(Exception e){if(result.errors.size()<5)result.errors.add(I18n.s("operation_failed"));AppLog.write(app,"Operation: "+e.getClass().getSimpleName());}
        Thread.interrupted();SyncScheduler.kick(app);listener.finished(result);
    }
}
