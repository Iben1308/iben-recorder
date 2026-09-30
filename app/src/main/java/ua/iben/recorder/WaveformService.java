package ua.iben.recorder;
import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.io.IOException;
import java.util.concurrent.*;
/** An explicitly selected legacy recording; independent of the Activity, stops when done. */
public final class WaveformService extends Service {
    private static final String CANCEL="ua.iben.recorder.oreo.CANCEL_WAVE";
    private static final int NOTIFICATION=8113;
    private static final long MAX_TIME=30*60000L;
    static final class State {
        final String id; final int percent; final WaveformAnalyzer.Data data; final String error;
        State(String id,int percent,WaveformAnalyzer.Data data,String error){this.id=id;this.percent=percent;this.data=data;this.error=error;}
        boolean running(){return data==null && error==null;}
    }
    private static volatile State state;
    static State state(String id){State s=state;return s!=null && s.id.equals(id)?s:null;}
    static void request(Context context,String id){
        State current=state(id);if(current!=null && current.running())return;
        state=new State(id,0,null,null);
        try{context.startForegroundService(new Intent(context,WaveformService.class).putExtra("id",id));}
        catch(RuntimeException e){state=new State(id,0,null,I18n.s("wave_start_failed"));}
    }
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->new Thread(()->{
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);r.run();
    },"iben-waveform"));
    private Future<?> task;
    private PowerManager.WakeLock wake;
    private long generation;
    private String id;
    private boolean foreground;
    private final Runnable timeout=()->cancel(I18n.s("wave_time_limit"));
    @Override public void onCreate(){
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("waveform",I18n.s("wave_building"),NotificationManager.IMPORTANCE_LOW));
        try{
            if(Build.VERSION.SDK_INT>=35)startForeground(NOTIFICATION,notification(0),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);
            else if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION,notification(0),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NOTIFICATION,notification(0));
            foreground=true;
            wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"IbenRecorder:waveform");wake.setReferenceCounted(false);
        }catch(RuntimeException e){
            State s=state;if(s!=null)state=new State(s.id,0,null,I18n.s("wave_start_failed"));stopSelf();
        }
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(!foreground || intent==null){stopSelf(startId);return START_NOT_STICKY;}
        if(CANCEL.equals(intent.getAction())){cancel(I18n.s("wave_canceled"));return START_NOT_STICKY;}
        id=intent.getStringExtra("id");if(id==null){stopSelf(startId);return START_NOT_STICKY;}
        final String selected=id;final long token=++generation;
        if(task!=null)task.cancel(true);
        state=new State(selected,0,null,null);main.removeCallbacks(timeout);main.postDelayed(timeout,MAX_TIME);
        if(wake.isHeld())wake.release();wake.acquire(MAX_TIME+10000L);
        task=worker.submit(()->{
            WaveformAnalyzer.Data result=null;String error=null;
            try(RecordingFiles files=new RecordingFiles(getApplicationContext(),new Config(getApplicationContext()))){
                RecordingFiles.Item item=null;
                for(RecordingFiles.Item candidate:files.recordings())if(candidate.id.equals(selected)){item=candidate;break;}
                if(item==null)throw new IOException(I18n.s("unavailable"));
                try(RecordingFiles.Lease ignored=RecordingFiles.lease(item.file)){
                    result=WaveformAnalyzer.read(getApplicationContext(),item.file,item.duration,percent->main.post(()->{
                        if(token!=generation)return;
                        State old=state;state=new State(selected,percent,null,null);
                        if(old==null || percent/5!=old.percent/5)updateNotification(percent);
                    }));
                }
            }catch(Exception e){
                if(Thread.currentThread().isInterrupted())return;
                error=I18n.tr(e.getMessage()==null?I18n.s("analysis_failed"):e.getMessage());
            }
            WaveformAnalyzer.Data data=result;String problem=error;
            main.post(()->{
                if(token!=generation)return;
                state=new State(selected,data==null?0:100,data,problem);
                main.removeCallbacks(timeout);releaseWake();
                if(stopSelfResult(startId))stopForeground(true);
            });
        });
        return START_NOT_STICKY;
    }
    private void updateNotification(int percent){
        if(!Platform.notifications(this))return;
        try{getSystemService(NotificationManager.class).notify(NOTIFICATION,notification(percent));}catch(SecurityException ignored){}
    }
    private Notification notification(int percent){
        PendingIntent open=PendingIntent.getActivity(this,NOTIFICATION,new Intent(this,MainActivity.class).putExtra("tab",0),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,NOTIFICATION,new Intent(this,WaveformService.class).setAction(CANCEL),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"waveform").setSmallIcon(R.drawable.ic_mic).setContentTitle(I18n.s("wave_building"))
                .setContentText(I18n.s("wave_background")).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).setProgress(100,percent,false)
                .addAction(new Notification.Action.Builder(null,I18n.s("cancel"),stop).build()).build();
    }
    private void cancel(String message){
        generation++;if(task!=null)task.cancel(true);
        if(id!=null)state=new State(id,0,null,message);
        main.removeCallbacks(timeout);releaseWake();stopForeground(true);stopSelf();
    }
    @Override public void onTimeout(int startId,int fgsType){cancel(I18n.s("wave_time_limit"));}
    private void releaseWake(){if(wake!=null && wake.isHeld())wake.release();}
    @Override public void onDestroy(){
        generation++;if(task!=null)task.cancel(true);worker.shutdownNow();main.removeCallbacksAndMessages(null);releaseWake();
        State s=state;if(id!=null && s!=null && s.id.equals(id) && s.running())state=new State(id,0,null,I18n.s("wave_canceled"));super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
