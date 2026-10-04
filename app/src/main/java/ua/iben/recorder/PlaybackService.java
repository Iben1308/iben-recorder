package ua.iben.recorder;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.media.session.*;
import android.os.*;
import java.io.*;

/** The service owns player, history, focus and MediaSession. Activities only render snapshots.
 * All MediaPlayer calls share one Looper; no network or media release on the UI thread. */
public final class PlaybackService extends Service {
    private static final int NOTIFICATION=8130;
    private static final String OPEN="open",PLAY="play",PAUSE="pause",STOP="stop",BACK="back",FORWARD="forward";
    private static volatile PlaybackService instance;private static volatile State snapshot;
    static final class State {
        final String id,name,ranges,error;final long position,duration,at;final float speed;
        final boolean playing,preparing,listened;
        State(String id,String name,long position,long duration,float speed,boolean playing,boolean preparing,String ranges,boolean listened,String error){
            this.id=id;this.name=name;this.position=position;this.duration=duration;this.speed=speed;this.playing=playing;this.preparing=preparing;this.ranges=ranges;this.listened=listened;this.error=error;at=SystemClock.elapsedRealtime();
        }
        long position(){return playing?Math.min(duration,position+(long)((SystemClock.elapsedRealtime()-at)*speed)):position;}
    }
    static State state(){return snapshot;}
    static void play(Context context,String id){
        try{context.startForegroundService(new Intent(context,PlaybackService.class).setAction(OPEN).putExtra("id",id));}
        catch(RuntimeException e){snapshot=new State(id,"",0,0,1,false,false,"",false,I18n.s("playback_start_failed"));}
    }
    static void pauseCurrent(){PlaybackService s=instance;if(s!=null)s.control.post(()->s.pause(false));}
    static void stop(String id){PlaybackService s=instance;if(s!=null && (id==null || id.equals(s.currentId)))s.cancelFile(s.currentId);}
    static void seek(String id,long position){PlaybackService s=instance;if(s!=null)s.control.post(()->{if(id.equals(s.currentId))s.seek(position);});}
    static void speed(float speed){PlaybackService s=instance;if(s!=null)s.control.post(()->{s.track(false);s.speed=speed;if(s.playing())s.applySpeed();s.publish(null);});}
    static void marked(String id,boolean listened){PlaybackService s=instance;if(s!=null)s.control.post(()->{if(s.item!=null && id.equals(s.item.id)){s.item.listened=listened;s.publish(null);}});}
    private HandlerThread thread;private Handler control;private final Handler main=new Handler(Looper.getMainLooper());
    private MediaPlayer player;private MediaSession session;private AudioManager audio;private AudioFocusRequest focus;
    private RecordingFiles.Item item;private RecordingFiles.Lease lease;private PlaybackProgress progress;
    private volatile String currentId;private volatile DavClient cloudClient;private volatile CloudAudioSource source;
    private final java.util.concurrent.atomic.AtomicLong generation=new java.util.concurrent.atomic.AtomicLong();
    private File temporary;private PowerManager.WakeLock preparingWake;private android.net.wifi.WifiManager.WifiLock wifi;
    private volatile boolean ready;
    private boolean autoplay=true,seekPending,startAfterSeek,ended,resumeOnFocus;
    private volatile boolean foreground,destroyed;
    private final java.util.concurrent.atomic.AtomicLong requests=new java.util.concurrent.atomic.AtomicLong();
    private long savedPosition,pendingSeek,observedPosition=-1,observedAt,lastSaved,lastNotify;
    private float speed=1f;
    private final Runnable idle=()->{if(!playing())stopSession();};
    private final BroadcastReceiver noisy=new BroadcastReceiver(){public void onReceive(Context c,Intent i){control.post(()->pause(false));}};
    @Override public void onCreate(){
        super.onCreate();thread=new HandlerThread("iben-playback");thread.start();control=new Handler(thread.getLooper());instance=this;
        File[] oldCaches=getCacheDir().listFiles((dir,name)->name.startsWith("iben-playback-") && name.endsWith(".m4a"));
        if(oldCaches!=null)for(File file:oldCaches)file.delete();
        audio=getSystemService(AudioManager.class);getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("playback",I18n.s("playback"),NotificationManager.IMPORTANCE_LOW));
        session=new MediaSession(this,"IbenRecorder");session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS|MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback(){
            public void onPlay(){resume();}public void onPause(){pause(false);}public void onStop(){stopSession();}
            public void onSeekTo(long position){seek(position);}public void onRewind(){seek(position()-10000);}public void onFastForward(){seek(position()+10000);}
        },control);
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes()).setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(change->{if(change==AudioManager.AUDIOFOCUS_GAIN){if(resumeOnFocus)resume();}else pause(change!=AudioManager.AUDIOFOCUS_LOSS);},control).build();
        android.net.wifi.WifiManager wifiManager=(android.net.wifi.WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if(wifiManager!=null){wifi=wifiManager.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,"IbenRecorder:cloud-playback");wifi.setReferenceCounted(false);}
        preparingWake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"IbenRecorder:playback-prepare");preparingWake.setReferenceCounted(false);
        IntentFilter filter=new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(noisy,filter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(noisy,filter);
        ensureForeground();
    }
    private static AudioAttributes attributes(){return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();}
    private void ensureForeground(){
        try{if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);else startForeground(NOTIFICATION,notification());foreground=true;}
        catch(RuntimeException e){foreground=false;stopSelf();}
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null){stopSelf(startId);return START_NOT_STICKY;}
        if(!foreground)ensureForeground();
        if(!foreground){stopSelf(startId);return START_NOT_STICKY;}
        String action=intent.getAction(),id=intent.getStringExtra("id");
        if(STOP.equals(action)){long ticket=requests.incrementAndGet();cancelNetwork();control.post(()->{if(requests.get()==ticket)stopSession();});}
        else if(PAUSE.equals(action))control.post(()->pause(false));
        else if(BACK.equals(action))control.post(()->seek(position()-10000));
        else if(FORWARD.equals(action))control.post(()->seek(position()+10000));
        else if(PLAY.equals(action))control.post(this::resume);
        else if(id!=null){long ticket=requests.incrementAndGet();if(!id.equals(currentId))cancelNetwork();control.post(()->{if(requests.get()!=ticket)return;if(id.equals(currentId) && ready)resume();else open(id,ticket);});}
        return START_NOT_STICKY;
    }
    private void open(String id,long request){
        release();if(destroyed || request!=requests.get())return;autoplay=true;currentId=id;long token=generation.incrementAndGet();speed=new Config(this).prefs.getFloat("playback_speed",1f);
        try(RecordingFiles files=new RecordingFiles(this,new Config(this))){
            item=files.find(id);if(item==null)throw new IOException(I18n.s("unavailable"));
            savedPosition=PlaybackProgress.resume(item.position,item.duration);progress=new PlaybackProgress(item.duration,item.heardRanges);
            lease=RecordingFiles.hold(item,()->cancelFile(id));publish(null);preparingWake.acquire(10*60000L);
            MediaPlayer next=new MediaPlayer();player=next;next.setAudioAttributes(attributes());next.setWakeMode(this,PowerManager.PARTIAL_WAKE_LOCK);
            if(item.local)next.setDataSource(item.file.getAbsolutePath());
            else{
                networkWake(true);
                CloudSettings cloud=new CloudSettings(this);CloudSettings.Connection connection=cloud.connection();
                if(!item.cloudTarget.equals(connection.target.key))throw new IOException(I18n.s("connection_changed"));
                DavClient client=connection.client((phase,done,total)->{});cloudClient=client;
                DavClient.RemoteInfo info=client.inspect(item.remoteName);
                CloudAudioSource data=new CloudAudioSource(client,item.remoteName,info,()->generation.get()==token && cloud.revision()==connection.revision);source=data;
                try{data.prepare();next.setDataSource(data);}
                catch(DavClient.RangeUnavailable unsupported){
                    data.close();source=null;
                    if(info.size>512L*1048576 || getCacheDir().getUsableSpace()<info.size+256L*1048576)throw new IOException(I18n.s("cloud_cache_space"));
                    temporary=File.createTempFile("iben-playback-",".m4a",getCacheDir());
                    client=connection.client((phase,done,total)->{});cloudClient=client;
                    try(OutputStream output=new FileOutputStream(temporary)){client.download(item.remoteName,info,output);}
                    if(cloud.revision()!=connection.revision)throw new IOException(I18n.s("connection_changed"));
                    client.close();cloudClient=null;next.setDataSource(temporary.getAbsolutePath());
                }
            }
            if(generation.get()!=token || destroyed)throw new InterruptedIOException();
            main.postDelayed(()->{if(generation.get()==token && !ready){cancelNetwork();control.post(()->{if(player==next)failure(new IOException("Player preparation timed out"));});}},10*60000L);
            next.setOnPreparedListener(mp->{if(player!=mp || generation.get()!=token)return;
                ready=true;releasePreparingWake();if(source==null)networkWake(false);long actual=Math.max(0,mp.getDuration());String ranges=progress.encode();progress=new PlaybackProgress(actual,ranges);
                RecordEdits.worker.execute(()->{synchronized(RecordingFiles.LOCK){try(RecordIndex index=new RecordIndex(this)){index.duration(id,actual);}}});
                if(savedPosition>0){startAfterSeek=autoplay;seek(savedPosition);}else if(autoplay)resume();publish(null);
            });
            next.setOnSeekCompleteListener(mp->{if(player!=mp)return;seekPending=false;observedPosition=-1;checkpoint(null);boolean start=startAfterSeek;startAfterSeek=false;if(start)resume();publish(null);});
            next.setOnCompletionListener(mp->{if(player!=mp)return;track(true);ended=true;savedPosition=0;checkpoint(progress.complete()?Boolean.TRUE:null);networkWake(false);audio.abandonAudioFocusRequest(focus);publish(null);control.postDelayed(idle,5*60000L);});
            next.setOnErrorListener((mp,what,extra)->{if(player==mp)failure(new IOException("MediaPlayer "+what+"/"+extra));return true;});
            next.prepareAsync();control.removeCallbacks(tick);control.post(tick);
        }catch(Exception e){if(destroyed || request!=requests.get()){release();}else failure(e);}
    }
    private void cancelNetwork(){generation.incrementAndGet();CloudAudioSource data=source;if(data!=null)data.cancel();DavClient client=cloudClient;if(client!=null)client.cancel();}
    private void cancelFile(String id){if(id==null || !id.equals(currentId))return;long ticket=requests.incrementAndGet();cancelNetwork();control.post(()->{if(requests.get()==ticket && id.equals(currentId))stopSession();});}
    private void failure(Exception e){
        String id=currentId,name=item==null?"":item.name;long at=position(),duration=duration();AppLog.write(this,"Playback: "+e.getClass().getSimpleName());
        String message=I18n.s("cloud_cache_space").equals(e.getMessage())?e.getMessage():I18n.s("playback_failed");
        release();snapshot=new State(id,name,at,duration,speed,false,false,"",false,message);finishService();
    }
    private boolean playing(){try{return ready && player!=null && player.isPlaying();}catch(RuntimeException e){return false;}}
    private long duration(){try{return ready && player!=null?player.getDuration():item==null?0:item.duration;}catch(RuntimeException e){return item==null?0:item.duration;}}
    private long position(){if(ended)return 0;if(seekPending)return pendingSeek;try{return ready && player!=null?player.getCurrentPosition():savedPosition;}catch(RuntimeException e){return savedPosition;}}
    private void track(boolean completion){
        if(!ready || progress==null || seekPending || (!completion && !playing()))return;
        long now=SystemClock.elapsedRealtime(),at=completion?duration():position();
        if(observedPosition>=0){long delta=at-observedPosition,elapsed=Math.max(0,now-observedAt);if(delta>=0 && delta<=elapsed*speed+1000)progress.add(observedPosition,at);}
        observedPosition=completion?-1:at;observedAt=now;
    }
    private void checkpoint(Boolean heard){
        if(item==null || progress==null)return;track(false);savedPosition=position();item.position=savedPosition;item.heardRanges=progress.encode();if(heard!=null)item.listened=heard;
        String id=item.id,ranges=item.heardRanges;long at=savedPosition;
        RecordEdits.worker.execute(()->{synchronized(RecordingFiles.LOCK){try(RecordIndex index=new RecordIndex(this)){index.playback(id,at,ranges,heard);}catch(RuntimeException e){AppLog.write(this,"Playback history: "+e.getClass().getSimpleName());}}});lastSaved=SystemClock.elapsedRealtime();
    }
    private void resume(){
        autoplay=true;if(!ready || player==null || destroyed)return;control.removeCallbacks(idle);resumeOnFocus=false;
        if(seekPending){startAfterSeek=true;return;}if(ended){ended=false;startAfterSeek=true;seek(0);return;}
        if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){publish(I18n.s("focus_denied"));return;}
        try{if(source!=null)networkWake(true);applySpeed();player.start();observedPosition=position();observedAt=SystemClock.elapsedRealtime();publish(null);}catch(RuntimeException e){failure(e);}
    }
    private void applySpeed(){if(!ready || player==null)return;try{player.setPlaybackParams(new PlaybackParams().setSpeed(speed).setPitch(1));}catch(RuntimeException e){speed=1;try{player.setPlaybackParams(new PlaybackParams().setSpeed(1).setPitch(1));}catch(RuntimeException ignored){}}}
    private void pause(boolean transientLoss){
        boolean wasPlaying=playing();track(false);autoplay=false;startAfterSeek=false;resumeOnFocus=transientLoss && (wasPlaying || resumeOnFocus);
        try{if(wasPlaying)player.pause();}catch(RuntimeException ignored){}checkpoint(null);observedPosition=-1;
        networkWake(false);if(!transientLoss)audio.abandonAudioFocusRequest(focus);publish(null);control.removeCallbacks(idle);control.postDelayed(idle,5*60000L);
    }
    private void seek(long millis){
        if(!ready || player==null){savedPosition=Math.max(0,millis);return;}track(false);observedPosition=-1;ended=false;
        pendingSeek=Math.max(0,Math.min(Math.max(0,duration()-1),millis));seekPending=true;
        try{player.seekTo(pendingSeek,MediaPlayer.SEEK_CLOSEST);}catch(RuntimeException e){seekPending=false;startAfterSeek=false;}publish(null);
    }
    private final Runnable tick=new Runnable(){public void run(){if(destroyed || player==null)return;track(false);if(playing() && SystemClock.elapsedRealtime()-lastSaved>=10000)checkpoint(null);publish(null);control.postDelayed(this,1000);}};
    private void publish(String error){
        if(item==null)return;snapshot=new State(item.id,item.name,position(),duration(),speed,playing(),!ready,item.heardRanges==null?"":item.heardRanges,item.listened,error);
        session.setActive(true);session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,item.name).putString(MediaMetadata.METADATA_KEY_ARTIST,"Iben Recorder").putLong(MediaMetadata.METADATA_KEY_DURATION,duration()).build());
        session.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_STOP|PlaybackState.ACTION_SEEK_TO|PlaybackState.ACTION_FAST_FORWARD|PlaybackState.ACTION_REWIND)
                .setState(!ready?PlaybackState.STATE_BUFFERING:playing()?PlaybackState.STATE_PLAYING:PlaybackState.STATE_PAUSED,position(),playing()?speed:0).build());
        if(foreground && SystemClock.elapsedRealtime()-lastNotify>900){lastNotify=SystemClock.elapsedRealtime();if(Platform.notifications(this))try{getSystemService(NotificationManager.class).notify(NOTIFICATION,notification());}catch(SecurityException ignored){}}
    }
    private PendingIntent action(String action,int code){return PendingIntent.getService(this,code,new Intent(this,PlaybackService.class).setAction(action),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    private Notification notification(){
        State value=snapshot;boolean playing=value!=null && value.playing;
        PendingIntent open=PendingIntent.getActivity(this,NOTIFICATION,new Intent(this,MainActivity.class).putExtra("tab",0),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=new Notification.Builder(this,"playback").setSmallIcon(R.drawable.ic_mic).setContentTitle(value==null?"Iben Recorder":value.name)
                .setContentText(I18n.s(value!=null && value.preparing?"cloud_loading":playing?"playback":"pause"))
                .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this,android.R.drawable.ic_media_rew),"−10 s",action(BACK,8131)).build())
                .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this,playing?android.R.drawable.ic_media_pause:android.R.drawable.ic_media_play),I18n.s(playing?"pause":"play"),action(playing?PAUSE:PLAY,8132)).build())
                .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this,android.R.drawable.ic_media_ff),"+10 s",action(FORWARD,8133)).build())
                .addAction(new Notification.Action.Builder(null,I18n.s("stop"),action(STOP,8134)).build());
        if(session!=null)b.setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0,1,2));return b.build();
    }
    private void networkWake(boolean needed){if(wifi==null)return;try{if(needed && !wifi.isHeld())wifi.acquire();else if(!needed && wifi.isHeld())wifi.release();}catch(SecurityException ignored){}}
    private void releasePreparingWake(){if(preparingWake!=null && preparingWake.isHeld())preparingWake.release();}
    private void release(){
        control.removeCallbacks(tick);control.removeCallbacks(idle);checkpoint(null);cancelNetwork();networkWake(false);
        MediaPlayer p=player;player=null;ready=false;if(p!=null)try{p.release();}catch(RuntimeException ignored){}
        CloudAudioSource data=source;source=null;if(data!=null)data.close();DavClient client=cloudClient;cloudClient=null;if(client!=null)client.close();
        if(lease!=null){lease.close();lease=null;}if(temporary!=null){temporary.delete();temporary=null;}releasePreparingWake();
        audio.abandonAudioFocusRequest(focus);item=null;progress=null;currentId=null;seekPending=false;startAfterSeek=false;ended=false;observedPosition=-1;
    }
    private void finishService(){long ticket=requests.get();main.post(()->{if(ticket==requests.get()){stopForeground(true);foreground=false;stopSelf();}});}
    private void stopSession(){release();snapshot=null;session.setActive(false);finishService();}
    @Override public void onDestroy(){
        destroyed=true;if(instance==this)instance=null;cancelNetwork();try{unregisterReceiver(noisy);}catch(RuntimeException ignored){}
        control.post(()->{release();session.release();thread.quitSafely();});main.removeCallbacksAndMessages(null);super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
