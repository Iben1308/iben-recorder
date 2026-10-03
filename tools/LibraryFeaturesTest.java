package ua.iben.recorder;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Pure production policy and concurrent segment-boundary checks; no Android emulator required. */
public final class LibraryFeaturesTest {
    private static int checks;
    private static void check(boolean condition,String message) {
        checks++; if(!condition)throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        for(String ip:new String[]{"10.0.0.1","10.255.255.254","172.16.0.1","172.31.255.254","192.168.0.1","192.168.255.254"}) {
            reject("http://"+ip+"/recordings/",false);
            DavTarget target=new DavTarget("http://"+ip+":80/recordings","user",true);
            check(target.folder.equals("http://"+ip+"/recordings/"),"Explicit LAN HTTP canonicalization");
            check(target.file("Тест 01(01_00).m4a").getHost().equals(ip),"File names cannot change the LAN destination");
            check(!target.key.equals(new DavTarget("https://"+ip+"/recordings/","user").key),"Downgrading protocol requires a new account binding");
        }
        for(String host:new String[]{"127.0.0.1","0.0.0.0","169.254.1.1","172.15.255.255","172.32.0.1",
                "192.167.1.1","192.169.1.1","8.8.8.8","224.0.0.1","255.255.255.255","localhost","cloud.local",
                "192.168.1.1.example.com","[::1]","[fc00::1]","[::ffff:192.168.1.1]","0300.0250.1.1",
                "0xc0a80101","3232235777","192.168.1","192.168.01.1","192.168.1.256"})
            reject("http://"+host+"/recordings/",true);
        for(String url:new String[]{"ftp://192.168.1.1/recordings/","http://u:p@192.168.1.1/recordings/",
                "http://192.168.1.1/recordings/?x=1","http://192.168.1.1/recordings/#x",
                "http://192.168.1.1:0/recordings/","http://192.168.1.1:65536/recordings/",
                "http://192.168.1.1/recordings/%2e%2e/","http://192.168.1.1/recordings/%5c/",
                "http://192.168.1.1/recordings/%0a/","http://192.168.1.1/"})reject(url,true);
        DavTarget previous=new DavTarget("https://CLOUD.example:443/folder"," user ");
        check(previous.key.equals(new DavTarget("https://cloud.example/folder/","user",true).key),
                "Existing HTTPS account/receipt identity survives the new setting");
        check(!previous.key.equals(new DavTarget(previous.folder,"other").key),"Consent is bound to its account");
        check(new DavTarget("http://192.168.1.1:8080/folder","user",true).folder.contains(":8080/"),"Explicit LAN port preserved");

        RecordingPosition position=new RecordingPosition();
        check(position.snapshot()==null,"No bookmark without an active segment");
        position.update("previous",599999);RecordingPosition.Moment previousMoment=position.snapshot();
        position.update("next",0);RecordingPosition.Moment next=position.snapshot();
        check(previousMoment.id.equals("previous") && previousMoment.millis==599999,"A captured moment remains attached to the old segment");
        check(next.id.equals("next") && next.millis==0,"The next segment starts with its own offset");
        position.update("next",-1);check(position.snapshot().millis==0,"Negative offsets clamped");
        AtomicBoolean done=new AtomicBoolean();AtomicReference<Throwable> error=new AtomicReference<>();
        position.update("0",0);
        Thread writer=new Thread(() -> {
            try {for(int i=0;i<200000;i++)position.update(Integer.toString(i),i);}
            catch(Throwable e){error.set(e);}finally{done.set(true);}
        });
        writer.start();
        do {
            RecordingPosition.Moment moment=position.snapshot();
            if(!moment.id.equals(Long.toString(moment.millis)))throw new AssertionError("Mixed file ID and offset at a concurrent split");
        } while(!done.get());
        writer.join();check(error.get()==null,"Concurrent position writer completed");
        position.clear();check(position.snapshot()==null,"Stopped recorder clears bookmark target");
        for(String language:new String[]{"uk","en","pl"}) {
            I18n.use(language);
            for(String key:new String[]{"important_delete_warning","important_protected","bookmark_note","batch_delete_confirm",
                    "batch_result","http_warning","certificate_changed","webdav_nextcloud_recommend"}) {
                // Format-bearing entries are exercised by the translation checker; ensure these keys exist here.
                check(!I18n.s(key).equals(key),"New controls and warnings have translations: "+language+"/"+key);
            }
        }
        I18n.use("uk");
        System.out.println("PASS: "+checks+" local HTTP / receipt compatibility / bookmark / translation assertions; 200000 concurrent position updates");
    }
    private static void reject(String url,boolean allowed) {
        try {new DavTarget(url,"user",allowed);throw new AssertionError("Unsafe HTTP target accepted: "+url);}
        catch(IllegalArgumentException expected){checks++;}
    }
}
