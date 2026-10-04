package android.media;
/** Test-only API boundary. No Android runtime behavior is emulated here. */
public abstract class MediaDataSource implements java.io.Closeable {
    public abstract int readAt(long position,byte[] buffer,int offset,int size)throws java.io.IOException;
    public abstract long getSize()throws java.io.IOException;
}
