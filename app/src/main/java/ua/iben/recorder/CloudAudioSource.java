package ua.iben.recorder;

import android.media.MediaDataSource;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Demand reads; at most 8 MiB in memory. Credentials/TLS remain in DavClient, not MediaPlayer. */
final class CloudAudioSource extends MediaDataSource {
    private static final int BLOCK=256*1024,MAX_BLOCKS=32;
    private final DavClient client;private final String name;private final DavClient.RemoteInfo info;
    private final BooleanSupplier allowed;private volatile boolean closed;
    private final LinkedHashMap<Long,byte[]> cache=new LinkedHashMap<Long,byte[]>(32,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<Long,byte[]> eldest){return size()>MAX_BLOCKS;}
    };
    CloudAudioSource(DavClient client,String name,DavClient.RemoteInfo info,BooleanSupplier allowed){this.client=client;this.name=name;this.info=info;this.allowed=allowed;}
    void prepare() throws IOException {block(0);}
    private void check()throws IOException{if(closed || !allowed.getAsBoolean())throw new InterruptedIOException();}
    private byte[] block(long number)throws IOException{
        check();byte[] data=cache.get(number);if(data==null){data=client.range(name,number*BLOCK,BLOCK,info);check();cache.put(number,data);}return data;
    }
    @Override public synchronized int readAt(long position,byte[] buffer,int offset,int size)throws IOException{
        check();if(position<0 || offset<0 || size<0 || offset>buffer.length-size)throw new IOException("Invalid audio read");
        if(size==0)return 0;if(position>=info.size)return -1;
        int wanted=(int)Math.min(size,info.size-position),done=0;
        while(done<wanted){long at=position+done;byte[] bytes=block(at/BLOCK);int from=(int)(at%BLOCK),count=Math.min(wanted-done,bytes.length-from);
            if(count<=0)throw new EOFException();System.arraycopy(bytes,from,buffer,offset+done,count);done+=count;
        }return done;
    }
    @Override public long getSize(){return info.size;}
    void cancel(){closed=true;client.cancel();}
    @Override public void close(){cancel();synchronized(this){cache.clear();}client.close();}
}
