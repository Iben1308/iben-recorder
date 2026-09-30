package ua.iben.recorder;
/** PCM envelope fed on the encoder thread after gain; memory is bounded for multi-week sessions. */
public final class CaptureEnvelope {
    private final int window;
    private final float[] values;
    private long completed, first;
    private int used;
    private double power;
    public CaptureEnvelope(int rate,int minutes) {
        if(rate<4 || minutes<1 || minutes>180) throw new IllegalArgumentException("Invalid envelope size");
        window=rate/4; values=new float[minutes*240+512];
    }
    public synchronized void add(short[] pcm,int offset,int count) {
        for(int i=offset;i<offset+count;i++) {
            double value=pcm[i]/32768d; power+=value*value;
            if(++used==window) {
                values[(int)(completed%values.length)]=db(power,used);
                completed++; first=Math.max(first,completed-values.length);used=0;power=0;
            }
        }
    }
    private static float db(double power,int count) { return (float)Math.max(-96,10*Math.log10(Math.max(1e-12,power/Math.max(1,count)))); }
    public synchronized float[] range(long startUs,long endUs) {
        if(endUs<=startUs)return null;
        long length=(endUs-startUs+249999)/250000;
        if(length<=0 || length>values.length)return null;
        float[] result=new float[(int)length];
        for(int i=0;i<result.length;i++) {
            long bin=Math.floorDiv(startUs+i*250000L+125000L,250000L);
            if(bin<0)result[i]=-96;
            else if(bin<first)return null;
            else if(bin<completed)result[i]=values[(int)(bin%values.length)];
            else if(bin==completed && used>0)result[i]=db(power,used);
            else result[i]=-96;
        }
        return result;
    }
    public int capacity(){return values.length;}
}
