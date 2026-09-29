package ua.iben.recorder;

import java.io.*;
import java.util.Arrays;
import java.util.Random;

public final class CompatibilityTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        // Real upgrade boundaries: 8.1/9 shared storage, 10 scoped storage, 11 while-in-use microphone.
        check(PlatformPolicy.publicStorage(27), "J7 keeps the existing public folder");
        check(PlatformPolicy.publicStorage(28), "Android 9 can use legacy storage with its runtime grant");
        check(!PlatformPolicy.publicStorage(29), "Android 10 must not require legacy storage permission");
        check(PlatformPolicy.mayStartMicrophone(29, false, false), "Android 10 supports scheduled cold microphone start");
        for (int sdk : new int[]{30,31,32,33,34,35,36,37}) {
            check(!PlatformPolicy.publicStorage(sdk), "Modern storage does not regress");
            check(!PlatformPolicy.mayStartMicrophone(sdk, false, false), "Boot/alarm cannot cold-start microphone on modern Android");
            check(PlatformPolicy.mayStartMicrophone(sdk, true, false), "Visible user action can activate recording");
            check(PlatformPolicy.mayStartMicrophone(sdk, false, true), "An already active microphone service can enter next interval");
            check(PlatformPolicy.keepStandby(sdk, true, true, false), "Enabled schedule stays armed between intervals");
            check(!PlatformPolicy.keepStandby(sdk, true, true, true), "Explicit stop-all releases standby");
            check(!PlatformPolicy.keepStandby(sdk, true, false, false), "Revoked scheduling permission releases standby");
            check(!PlatformPolicy.keepStandby(sdk, false, true, false), "Disabling schedule releases standby");
        }
        check(!PlatformPolicy.keepStandby(27, true, true, false), "J7 does not run an unnecessary waiting service");
        WeeklySchedule.Decision revoked = WeeklySchedule.decide(true, true, false, true);
        check(!revoked.wanted, "Schedule becomes inactive when exact-alarm or notification access disappears");
        WeeklySchedule.Decision manual = WeeklySchedule.decide(true, false, false, true);
        check(manual.wanted && !manual.scheduled, "Schedule permission revocation does not interrupt a manual recording");
        Random random = new Random(812035);
        for (int size : new int[]{0,1,65535,65536,65537,300001}) {
            byte[] data = new byte[size]; random.nextBytes(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            check(BoundedCopy.copy(new ByteArrayInputStream(data), out, size) == size, "Exact-length copy returns its real count");
            check(Arrays.equals(data, out.toByteArray()), "File contents are preserved across chunk boundaries");
            if (size > 0) {
                out.reset(); boolean refused = false;
                try { BoundedCopy.copy(new ByteArrayInputStream(data), out, size - 1); } catch (IOException e) { refused = true; }
                check(refused && out.size() <= size - 1, "Oversized provider data cannot exceed disk budget");
            }
        }
        boolean interrupted = false;
        Thread.currentThread().interrupt();
        try { BoundedCopy.copy(new ByteArrayInputStream(new byte[1]), new ByteArrayOutputStream(), 1); }
        catch (IOException e) { interrupted = true; }
        finally { Thread.interrupted(); }
        check(interrupted, "Canceled copies stop before writing more data");
        boolean propagated = false;
        try { BoundedCopy.copy(new InputStream() { public int read() throws IOException { throw new IOException("Provider disconnected"); } }, new ByteArrayOutputStream(), 8); }
        catch (IOException e) { propagated = true; }
        check(propagated, "Provider read errors never report success");
        propagated = false;
        try { BoundedCopy.copy(new ByteArrayInputStream(new byte[9]), new OutputStream() { public void write(int b) throws IOException { throw new IOException("Disk full"); } }, 9); }
        catch (IOException e) { propagated = true; }
        check(propagated, "Export write errors never report success");
        for (String locale : new String[]{"uk","en","pl"}) {
            I18n.use(locale);
            check(I18n.tr(I18n.uk("standby_status")).equals(I18n.s("standby_status")), "Persistent standby status follows the selected language");
            check(I18n.tr(I18n.uk("resume_required")).equals(I18n.s("resume_required")), "Boot reminder follows the selected language");
        }
        I18n.use("uk");
        System.out.println("PASS: " + checks + " Android compatibility / bounded file-copy assertions");
    }
}
