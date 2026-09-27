package ua.iben.recorder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure planning only. The caller must restrict candidates to its own closed files. */
public final class StoragePolicy {
    public static final long MIB = 1024L * 1024L;
    public static final long RESERVE_BYTES = 256L * MIB;

    public static final class Entry {
        public final String id;
        public final long bytes;
        public final long modified;
        public Entry(String id, long bytes, long modified) {
            if (id == null || bytes < 0) throw new IllegalArgumentException("Invalid file");
            this.id = id;
            this.bytes = bytes;
            this.modified = modified;
        }
    }

    public static final class Plan {
        public final List<String> deleteIds;
        public final boolean enoughSpace;
        Plan(List<String> ids, boolean enoughSpace) {
            this.deleteIds = ids;
            this.enoughSpace = enoughSpace;
        }
    }

    public static Plan plan(List<Entry> closed, long pendingBytes, long requiredBytes,
                            long quotaBytes, long freeBytes, boolean deleteOldest) {
        if (pendingBytes < 0 || requiredBytes < 0 || quotaBytes < 0 || freeBytes < 0)
            throw new IllegalArgumentException("Negative storage value");
        List<Entry> sorted = new ArrayList<>(closed);
        sorted.sort(Comparator.comparingLong((Entry e) -> e.modified).thenComparing(e -> e.id));
        long used = pendingBytes;
        for (Entry file : sorted) used = Math.addExact(used, file.bytes);
        List<String> deletions = new ArrayList<>();
        // An impossible request must never erase an entire archive pointlessly.
        if (requiredBytes > quotaBytes || pendingBytes > quotaBytes - requiredBytes)
            return new Plan(deletions, false);
        if (deleteOldest) {
            for (Entry file : sorted) {
                if (fits(used, requiredBytes, quotaBytes, freeBytes)) break;
                deletions.add(file.id);
                used -= file.bytes;
                freeBytes = Math.addExact(freeBytes, file.bytes);
            }
        }
        return new Plan(deletions, fits(used, requiredBytes, quotaBytes, freeBytes));
    }

    private static boolean fits(long used, long required, long quota, long free) {
        return required <= quota && used <= quota - required
                && free >= RESERVE_BYTES && required <= free - RESERVE_BYTES;
    }

    public static long segmentBudget(int minutes, int bitrateKbps) {
        if (minutes < 1 || minutes > 180 || bitrateKbps < 32 || bitrateKbps > 320)
            throw new IllegalArgumentException("Invalid recording settings");
        long audio = minutes * 60L * bitrateKbps * 1000L / 8L;
        return audio + audio / 5L + 2L * MIB;
    }

    public static boolean ownedName(String name, String owner) {
        return owner != null && owner.matches("[0-9a-f]{12}")
                && name != null && name.matches("j7_" + owner
                + "_[0-9]{8}T[0-9]{6}_[0-9]{3}_[0-9a-f]{8}\\.(m4a|part|ready|failed)");
    }
}
