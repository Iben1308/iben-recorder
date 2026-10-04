package ua.iben.recorder;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.*;
import java.util.*;

/** A user-started operation owns its lifetime, wake lock and cancellation, independently of Activity.
 * Deliberately NOT_STICKY: destructive requests are never replayed after process death. */
public final class OperationsService extends Service {
    private static final int NOTIFICATION=8120,RESULT=8121;
    private static final String CANCEL="ua.iben.recorder.oreo.CANCEL_OPERATION";
    private static final long MAX_TIME=5*60*60*1000L;
    static final class Request {
        final String token=UUID.randomUUID().toString(),kind,id;final List<RecordingFiles.Item> items;
        final BatchWork.Action action;final Uri destination;final boolean allowImportant,allowLocalFallback;final int unavailable;
        Request(String kind,String id,List<RecordingFiles.Item> items,BatchWork.Action action,Uri destination,boolean important,boolean fallback,int unavailable){
            this.kind=kind;this.id=id;this.items=items==null?Collections.emptyList():new ArrayList<>(items);this.action=action;this.destination=destination;allowImportant=important;allowLocalFallback=fallback;this.unavailable=unavailable;
        }
        static Request batch(List<RecordingFiles.Item> items,BatchWork.Action action,Uri destination,boolean important,boolean fallback,int unavailable){return new Request("batch",null,items,action,destination,important,fallback,unavailable);}
        static Request catalog(){return new Request("catalog",null,null,null,null,false,false,0);}
        static Request document(boolean restore,String id,Uri uri){return new Request(restore?"restore":"export",id,null,null,uri,false,false,0);}
    }
    static final class State {
        final String token,title,detail;final int current,total;final boolean running,catalog;
        State(String token,String title,String detail,int current,int total,boolean running,boolean catalog){this.token=token;this.title=title;this.detail=detail;this.current=current;this.total=total;this.running=running;this.catalog=catalog;}
    }
    private static volatile State state;private static volatile OperationsService instance;private static Request pending,afterExport;
    static State state(Context context){
        State value=state;if(value!=null)return value;
        SharedPreferences prefs=context.getSharedPreferences("operations",MODE_PRIVATE);String token=prefs.getString("token","");if(token.isEmpty())return null;
        state=new State(token,I18n.s("operations"),prefs.getBoolean("running",false)?I18n.s("operation_interrupted"):prefs.getString("result",""),0,0,false,prefs.getBoolean("catalog",false));return state;
    }
    static synchronized boolean start(Context context,Request request){
        if(state!=null && state.running || instance!=null && instance.thread!=null){
            OperationsService active=instance;
            if(afterExport==null && active!=null && active.foreground && deletion(request) && request.items.stream().anyMatch(item->exporting(item.id))){
                afterExport=request;active.cancel();return true;
            }
            return false;
        }
        DocumentTransfers.retain(context,request.destination);
        pending=request;state=new State(request.token,title(request),I18n.s("operation_working"),0,request.items.size(),true,"catalog".equals(request.kind));
        context.getSharedPreferences("operations",MODE_PRIVATE).edit().putString("token",request.token).putBoolean("running",true).putBoolean("catalog",state.catalog).remove("result").apply();
        try{context.startForegroundService(new Intent(context,OperationsService.class).putExtra("token",request.token));return true;}
        catch(RuntimeException e){DocumentTransfers.release(context,request.destination);pending=null;state=new State(request.token,title(request),I18n.s("operation_start_failed"),0,0,false,state.catalog);save(context,state);return false;}
    }
    private static boolean deletion(Request r){return r.action==BatchWork.Action.DELETE_LOCAL || r.action==BatchWork.Action.DELETE_CLOUD || r.action==BatchWork.Action.DELETE_BOTH;}
    static synchronized boolean exporting(String id){
        OperationsService active=instance;Request r=active==null?pending:active.request;
        if(r==null || state==null || !state.running)return false;
        return r.kind.equals("export") && (id==null || id.equals(r.id)) || r.action==BatchWork.Action.EXPORT && (id==null || r.items.stream().anyMatch(item->item.id.equals(id)));
    }
    static synchronized void cancelCurrent(){afterExport=null;OperationsService active=instance;if(active!=null)active.cancel();}
    static void acknowledge(Context context){context.getSystemService(NotificationManager.class).cancel(RESULT);}
    private static String title(Request r){return I18n.s(r.kind.equals("catalog")?"cloud_refresh":r.kind.equals("restore")?"restore_folder":r.kind.equals("export") || r.action==BatchWork.Action.EXPORT?"export_recording":r.action==BatchWork.Action.DELETE_LOCAL || r.action==BatchWork.Action.DELETE_CLOUD || r.action==BatchWork.Action.DELETE_BOTH?"delete_recording":"batch_actions");}
    private static void save(Context context,State value){context.getSharedPreferences("operations",MODE_PRIVATE).edit().putString("token",value.token).putBoolean("running",value.running).putBoolean("catalog",value.catalog).putString("result",value.detail).apply();}
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile Thread thread;private volatile BatchWork batch;private volatile DavClient client;private volatile boolean canceled;
    private PowerManager.WakeLock wake;private volatile boolean foreground;private volatile Request request;private long lastNotification;
    @Override public void onCreate(){
        super.onCreate();instance=this;
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("operations",I18n.s("operations"),NotificationManager.IMPORTANCE_LOW));
        try{if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION,notification(false),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);else startForeground(NOTIFICATION,notification(false));foreground=true;}
        catch(RuntimeException e){State value=state;if(value!=null){state=new State(value.token,value.title,I18n.s("operation_start_failed"),0,0,false,value.catalog);save(this,state);}stopSelf();}
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null && CANCEL.equals(intent.getAction())){cancelCurrent();if(thread==null)stopSelf(startId);return START_NOT_STICKY;}
        if(!foreground || intent==null){stopSelf(startId);return START_NOT_STICKY;}
        synchronized(OperationsService.class){if(pending==null || !pending.token.equals(intent.getStringExtra("token")))return START_NOT_STICKY;request=pending;pending=null;}
        if(thread!=null)return START_NOT_STICKY;
        wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"IbenRecorder:operation");wake.acquire(MAX_TIME+10000);
        main.postDelayed(this::timeout,MAX_TIME);thread=new Thread(this::run,"iben-operation");thread.start();return START_NOT_STICKY;
    }
    private void run(){
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);String result;
        try{
            if(canceled)throw new java.io.InterruptedIOException();
            if(request.kind.equals("batch")){
                final String[] answer={I18n.s("operation_interrupted")};
                batch=new BatchWork(this,request.items,request.action,request.destination,request.allowImportant,request.allowLocalFallback,new BatchWork.Listener(){
                    public void progress(int current,int total,String name){publish(current,total,name);}
                    public void finished(BatchWork.Result value){value.skipped+=request.unavailable;answer[0]=value.text();}
                });if(canceled)batch.cancel();batch.run();result=answer[0];
            }else if(request.kind.equals("catalog")){
                CloudSettings cloud=new CloudSettings(this);CloudSettings.Connection connection=cloud.connection();long started=System.currentTimeMillis();
                try(DavClient dav=connection.client((phase,done,total)->{});RecordingFiles files=new RecordingFiles(this,new Config(this))){
                    client=dav;if(canceled)dav.cancel();List<DavListing.Remote> remote=dav.list();
                    if(canceled)throw new java.io.InterruptedIOException();if(cloud.revision()!=connection.revision)throw new java.io.IOException(I18n.s("connection_changed"));
                    files.cloudCatalog(connection.target.key,remote,started);result=I18n.s("cloud_refreshed");
                }
            }else if(request.kind.equals("export")){DocumentTransfers.export(this,request.id,request.destination);result=I18n.s("export_done");}
            else {result=I18n.s("restore_done",DocumentTransfers.restore(this,request.destination));}
        }catch(Exception e){result=canceled || e instanceof java.io.InterruptedIOException?I18n.s("operation_interrupted"):I18n.s("operation_failed");AppLog.write(this,"Operation "+request.kind+": "+e.getClass().getSimpleName());}
        finally{client=null;batch=null;DocumentTransfers.release(this,request.destination);Thread.interrupted();}
        String message=result;main.post(()->finish(message));
    }
    private void publish(int current,int total,String detail){
        State old=state;if(old==null || request==null || !request.token.equals(old.token) || !foreground)return;state=new State(old.token,old.title,detail,current,total,true,old.catalog);
        if(SystemClock.elapsedRealtime()-lastNotification>500){lastNotification=SystemClock.elapsedRealtime();main.post(()->{if(foreground)notifyState(false);});}
    }
    private void cancel(){canceled=true;DavClient dav=client;if(dav!=null)dav.cancel();BatchWork work=batch;if(work!=null)work.cancel();Thread active=thread;if(active!=null)DocumentTransfers.cancel(active);}
    private void timeout(){cancelCurrent();finish(I18n.s("operation_interrupted"));}
    private void finish(String result){
        if(!foreground)return;
        synchronized(OperationsService.class){
            if(afterExport!=null){
                // A confirmed deletion may replace export only after its streams and leases have closed.
                request=afterExport;afterExport=null;canceled=false;batch=null;client=null;
                state=new State(request.token,title(request),I18n.s("operation_working"),0,request.items.size(),true,false);save(this,state);
                thread=new Thread(this::run,"iben-operation");thread.start();notifyState(false);return;
            }
        }
        State old=state;if(old!=null && request!=null && request.token.equals(old.token)){state=new State(old.token,old.title,result,old.current,old.total,false,old.catalog);save(this,state);}
        main.removeCallbacksAndMessages(null);releaseWake();foreground=false;stopForeground(true);notifyState(true);stopSelf();
    }
    private Notification notification(boolean done){
        State value=state;String title=value==null?I18n.s("operations"):value.title,detail=value==null?I18n.s("operation_working"):value.detail;
        PendingIntent open=PendingIntent.getActivity(this,NOTIFICATION,new Intent(this,MainActivity.class).putExtra("tab",0),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=new Notification.Builder(this,"operations").setSmallIcon(R.drawable.ic_mic).setContentTitle(title).setContentText(detail)
                .setStyle(new Notification.BigTextStyle().bigText(detail)).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(!done).setAutoCancel(done).setVisibility(Notification.VISIBILITY_PRIVATE);
        if(!done){
            b.setProgress(value==null?0:value.total,value==null?0:value.current,value==null || value.total==0);
            PendingIntent cancel=PendingIntent.getService(this,NOTIFICATION,new Intent(this,OperationsService.class).setAction(CANCEL),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(null,I18n.s("cancel"),cancel).build());
        }return b.build();
    }
    private void notifyState(boolean done){if(Platform.notifications(this))try{getSystemService(NotificationManager.class).notify(done?RESULT:NOTIFICATION,notification(done));}catch(SecurityException ignored){}}
    private void releaseWake(){if(wake!=null && wake.isHeld())wake.release();}
    @Override public void onTimeout(int startId,int type){timeout();}
    @Override public void onDestroy(){
        if(instance==this)instance=null;cancel();releaseWake();main.removeCallbacksAndMessages(null);
        synchronized(OperationsService.class){afterExport=null;if(pending!=null && thread==null){DocumentTransfers.release(this,pending.destination);pending=null;}}
        State old=state;if(old!=null && old.running && request!=null && request.token.equals(old.token)){state=new State(old.token,old.title,I18n.s("operation_interrupted"),old.current,old.total,false,old.catalog);save(this,state);}super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
