package ua.iben.recorder;
import android.app.*;
import android.content.*;
/** Event-driven, one notification per incident; no extra polling or wake locks. */
final class ProblemNotifications {
    private static final String CHANNEL="problems";
    private static final int RECORD=8110,STORAGE=8111,CLOUD=8112,CAPTURE=8113;
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("problems",Context.MODE_PRIVATE);}
    static boolean enabled(Context c){return new Config(c).prefs.getBoolean("problem_alerts",true);}
    static void recordingFailure(Context c,Throwable error){
        RecordingFailure.Reason reason=RecordingFailure.classify(error);
        boolean storage=reason==RecordingFailure.Reason.STORAGE_FULL || reason==RecordingFailure.Reason.STORAGE_IO;
        post(c,storage?STORAGE:RECORD,storage?"problem_storage":"problem_record",
                I18n.s(RecordingFailure.key(reason))+"\n"+I18n.s(reason==RecordingFailure.Reason.STORAGE_FULL?"problem_storage_detail":"problem_record_detail"),1,0);
    }
    static void captureState(Context c,int state) {
        if(state==CaptureHealth.NORMAL)clear(c,CAPTURE);
        else post(c,CAPTURE,state==CaptureHealth.SYSTEM_SILENCED ? "capture_system_silenced" : "capture_zero_signal",
                I18n.s(state==CaptureHealth.SYSTEM_SILENCED ? "capture_system_hint" : "capture_zero_hint"),1,0);
    }
    static void recordingHealthy(Context c){clear(c,RECORD);clear(c,STORAGE);}
    static void cloudFailure(Context c,long revision){
        synchronized(RecordingFiles.LOCK){
            CloudSettings cloud=new CloudSettings(c);
            if(!cloud.enabled() || revision!=cloud.revision())return;
            long now=System.currentTimeMillis(),since=cloud.prefs.getLong("failure_since",0);
            if(since<=0 || since>now){since=now;cloud.prefs.edit().putLong("failure_since",since).apply();}
            if(IncidentPolicy.cloudAlert(cloud.prefs.getInt("failures",0),since,now))
                post(c,CLOUD,"problem_cloud",I18n.s("problem_cloud_detail"),2,3);
        }
    }
    static void cloudHealthy(Context c){new CloudSettings(c).prefs.edit().remove("failure_since").apply();clear(c,CLOUD);}
    static synchronized void reset(Context c){clear(c,RECORD);clear(c,STORAGE);clear(c,CLOUD);clear(c,CAPTURE);}
    private static synchronized void clear(Context c,int id){
        if(!prefs(c).getBoolean("shown_"+id,false))return;
        c.getSystemService(NotificationManager.class).cancel(id);prefs(c).edit().remove("shown_"+id).remove("detail_"+id).apply();
    }
    private static synchronized void post(Context c,int id,String title,String detail,int tab,int section){
        if(!enabled(c) || !Platform.notifications(c) || prefs(c).getBoolean("shown_"+id,false) && detail.equals(prefs(c).getString("detail_"+id,"")))return;
        NotificationManager manager=c.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,I18n.s("problem_alerts"),NotificationManager.IMPORTANCE_DEFAULT));
        if(manager.getNotificationChannel(CHANNEL).getImportance()==NotificationManager.IMPORTANCE_NONE)return;
        Intent intent=new Intent(c,MainActivity.class).putExtra("tab",tab).putExtra("settings_section",section).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open=PendingIntent.getActivity(c,id,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(c,CHANNEL).setSmallIcon(R.drawable.ic_mic).setContentTitle(I18n.s(title))
                .setContentText(detail).setStyle(new Notification.BigTextStyle().bigText(detail)).setContentIntent(open)
                .setAutoCancel(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_ERROR).build();
        try{manager.notify(id,notification);prefs(c).edit().putBoolean("shown_"+id,true).putString("detail_"+id,detail).apply();}catch(SecurityException ignored){}
    }
}
