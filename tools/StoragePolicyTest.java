package ua.iben.recorder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public final class StoragePolicyTest {
    private static final long M = StoragePolicy.MIB;
    private static int checks;

    public static void main(String[] args) {
        List<StoragePolicy.Entry> files = Arrays.asList(
                new StoragePolicy.Entry("new", 50 * M, 20),
                new StoragePolicy.Entry("old", 50 * M, 10));
        StoragePolicy.Plan p = StoragePolicy.plan(files, 0, 20 * M, 100 * M, 1000 * M, true);
        check(p.enoughSpace && p.deleteIds.equals(Collections.singletonList("old")), "Delete oldest for quota");
        p = StoragePolicy.plan(files, 0, 20 * M, 100 * M, 1000 * M, false);
        check(!p.enoughSpace && p.deleteIds.isEmpty(), "Do not delete when disabled");
        p = StoragePolicy.plan(files, 5 * M, 20 * M, 500 * M, 260 * M, true);
        check(p.enoughSpace && p.deleteIds.equals(Collections.singletonList("old")), "Preserve disk reserve");
        p = StoragePolicy.plan(files, 0, 20 * M, 120 * M, 276 * M, true);
        check(p.enoughSpace && p.deleteIds.isEmpty(), "Exact bounds fit without deletion");
        p = StoragePolicy.plan(files, 0, 200 * M, 100 * M, 1000 * M, true);
        check(!p.enoughSpace && p.deleteIds.isEmpty(), "Oversized segment cannot erase archive");
        p = StoragePolicy.plan(files, 90 * M, 20 * M, 100 * M, 1000 * M, true);
        check(!p.enoughSpace && p.deleteIds.isEmpty(), "Never remove active staging to fit quota");
        p = StoragePolicy.plan(files, 60 * M + 10 * M, 70 * M, 200 * M, 1000 * M, true);
        check(p.enoughSpace && p.deleteIds.equals(Collections.singletonList("old")),
                "Current and finalizing files stay protected together; only closed archive is evicted");
        p = StoragePolicy.plan(files, 150 * M, 70 * M, 200 * M, 1000 * M, true);
        check(!p.enoughSpace && p.deleteIds.isEmpty(), "Finalizer backlog cannot trigger destructive futile eviction");
        p = StoragePolicy.plan(Collections.emptyList(), 0, 20 * M, 100 * M, 275 * M, true);
        check(!p.enoughSpace, "Fail if disk reserve cannot be met");
        check(StoragePolicy.segmentBudget(60, 128) > 57600000L, "AAC estimate includes container headroom");
        String owner = "123456abcdef";
        String stem = "j7_" + owner + "_20260927T010203_123_1234abcd";
        check(StoragePolicy.ownedName(stem + ".m4a", owner), "Accept own finalized filename");
        check(StoragePolicy.ownedName(stem + ".part", owner), "Recognize staging for accounting");
        check(!StoragePolicy.ownedName("../" + stem + ".m4a", owner), "Reject path traversal");
        check(!StoragePolicy.ownedName(stem + ".m4a.bak", owner), "Reject unrelated extensions");
        check(!StoragePolicy.ownedName(stem + ".m4a", "abcdef123456"), "Reject another installation's files");
        check(!StoragePolicy.ownedName("voice-note.m4a", owner), "Reject unrelated recordings");
        try { StoragePolicy.segmentBudget(0, 128); throw new AssertionError("Invalid duration accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        Random random = new Random(27);
        for (int run = 0; run < 4000; run++) {
            int count = random.nextInt(12);
            List<StoragePolicy.Entry> entries = new ArrayList<>();
            long total = 0;
            for (int i = 0; i < count; i++) {
                long size = random.nextInt(100) * M;
                entries.add(new StoragePolicy.Entry("f" + i, size, i));
                total += size;
            }
            long pending = random.nextInt(100) * M;
            long required = random.nextInt(200) * M;
            long quota = random.nextInt(800) * M;
            long free = random.nextInt(1200) * M;
            Collections.shuffle(entries, random);
            p = StoragePolicy.plan(entries, pending, required, quota, free, true);
            long removed = 0;
            for (int i = 0; i < p.deleteIds.size(); i++) {
                String id = p.deleteIds.get(i);
                check(id.equals("f" + i), "Deletion order must be an oldest-first prefix");
                for (StoragePolicy.Entry entry : entries) if (entry.id.equals(id)) removed += entry.bytes;
            }
            boolean fits = pending + total - removed + required <= quota
                    && free + removed >= StoragePolicy.RESERVE_BYTES + required;
            check(p.enoughSpace == fits, "Plan result agrees with resulting byte totals");
            boolean possible = pending + required <= quota
                    && free + total >= StoragePolicy.RESERVE_BYTES + required;
            check(p.enoughSpace == possible, "Planner finds space whenever eligible deletions suffice");
        }
        System.out.println("PASS: " + checks + " storage-policy assertions");
    }
    private static void check(boolean result, String description) {
        checks++;
        if (!result) throw new AssertionError(description);
    }
}
