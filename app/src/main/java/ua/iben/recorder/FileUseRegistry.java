package ua.iben.recorder;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.*;

/** Readers register cancellation; a deletion reserves the path before asking them to stop.
 * Waiting and callbacks never hold the recorder/SQLite lock. Never unlink a reader's live file. */
final class FileUseRegistry {
    private final Map<String,Set<Use>> uses=new HashMap<>();
    private final Map<String,Reservation> reserved=new HashMap<>();
    final class Use implements AutoCloseable {
        final String path;final Runnable cancel;boolean closed;
        Use(String path,Runnable cancel){this.path=path;this.cancel=cancel;}
        @Override public void close(){synchronized(FileUseRegistry.this){
            if(closed)return;closed=true;Set<Use> readers=uses.get(path);
            if(readers!=null){readers.remove(this);if(readers.isEmpty())uses.remove(path);}FileUseRegistry.this.notifyAll();
        }}
    }
    final class Reservation implements AutoCloseable {
        final String path;final Thread owner=Thread.currentThread();boolean closed;
        Reservation(String path){this.path=path;}
        void stopAndAwait(long timeoutMillis) throws IOException {
            List<Runnable> callbacks=new ArrayList<>();
            synchronized(FileUseRegistry.this){for(Use use:uses.getOrDefault(path,Collections.emptySet()))if(use.cancel!=null)callbacks.add(use.cancel);}
            for(Runnable callback:callbacks)try{callback.run();}catch(RuntimeException ignored){}
            long end=System.nanoTime()+timeoutMillis*1000000L;
            synchronized(FileUseRegistry.this){while(uses.containsKey(path)){
                long remaining=(end-System.nanoTime())/1000000L;
                if(remaining<=0)throw new IOException(I18n.s("processing_stop_failed"));
                try{FileUseRegistry.this.wait(Math.max(1,remaining));}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException();}
            }}
        }
        @Override public void close(){synchronized(FileUseRegistry.this){if(!closed){closed=true;reserved.remove(path);FileUseRegistry.this.notifyAll();}}}
    }
    synchronized Use acquire(String path,Runnable cancel) throws IOException {
        if(reserved.containsKey(path))throw new IOException(I18n.s("record_busy"));
        Use use=new Use(path,cancel);uses.computeIfAbsent(path,k->new HashSet<>()).add(use);return use;
    }
    synchronized Reservation reserve(String path) throws IOException {
        if(reserved.containsKey(path))throw new IOException(I18n.s("record_busy"));
        Reservation value=new Reservation(path);reserved.put(path,value);return value;
    }
    synchronized boolean busy(String path){return uses.containsKey(path);}
    synchronized boolean reserved(String path){return reserved.containsKey(path);}
    synchronized boolean owns(String path){Reservation value=reserved.get(path);return value!=null && value.owner==Thread.currentThread();}
}
