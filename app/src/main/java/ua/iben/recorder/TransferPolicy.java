package ua.iben.recorder;

/** Missing, stale or other-destination receipts never authorize local deletion. */
public final class TransferPolicy {
    public static final int NONE=0, CONTENT=1, METADATA=2;
    public static boolean verified(String target, String receiptTarget, long size, long receiptSize,
                                   long modified, long receiptModified, String sha256) {
        return confirmed(target,receiptTarget,size,receiptSize,modified,receiptModified,sha256,CONTENT,null);
    }
    public static boolean confirmed(String target,String receiptTarget,long size,long receiptSize,
                                    long modified,long receiptModified,String sha256,int kind,String etag) {
        boolean identity=target!=null && !target.isEmpty() && target.equals(receiptTarget)
                && size>0 && size==receiptSize && modified==receiptModified;
        return identity && (kind==CONTENT && sha256!=null && sha256.matches("[0-9a-f]{64}")
                || kind==METADATA && strongEtag(etag));
    }
    public static boolean strongEtag(String etag) {
        if(etag==null || etag.length()<2 || etag.charAt(0)!='"' || etag.charAt(etag.length()-1)!='"')return false;
        for(int i=1;i<etag.length()-1;i++) {
            char c=etag.charAt(i);if(c=='"' || c<0x21 || c==0x7f)return false;
        }
        return true;
    }
    public static long retryDelay(int attempts) {
        if (attempts <= 1) return 30000L;
        if (attempts == 2) return 120000L;
        if (attempts == 3) return 600000L;
        return 1800000L;
    }
}
