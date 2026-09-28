package ua.iben.recorder;

/** Missing, stale or other-destination receipts never authorize local deletion. */
public final class TransferPolicy {
    public static boolean verified(String target, String receiptTarget, long size, long receiptSize,
                                   long modified, long receiptModified, String sha256) {
        return target != null && !target.isEmpty() && target.equals(receiptTarget)
                && size > 0 && size == receiptSize && modified == receiptModified
                && sha256 != null && sha256.matches("[0-9a-f]{64}");
    }
    public static long retryDelay(int attempts) {
        if (attempts <= 1) return 30000L;
        if (attempts == 2) return 120000L;
        if (attempts == 3) return 600000L;
        return 1800000L;
    }
}
