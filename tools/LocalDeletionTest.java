package ua.iben.recorder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Real filesystem checks: refusal never loses data; explicit deletion removes only its target. */
public final class LocalDeletionTest {
    private static int checks;
    private static void check(boolean ok, String reason) { checks++; if (!ok) throw new AssertionError(reason); }
    public static void main(String[] args) throws Exception {
        Path dir=Files.createTempDirectory("iben-delete-test");
        Path recording=dir.resolve("recording.m4a"), other=dir.resolve("other.m4a");
        byte[] original={1,2,3,4,5};
        try {
            Files.write(recording,original); Files.write(other,original);
            File file=recording.toFile(); long size=file.length(), modified=file.lastModified();
            check(LocalDeletion.remove(file,size,modified,false,false,true,true)==LocalDeletion.Result.NOT_READY,"Active/unfinished records cannot be removed even with confirmation");
            check(LocalDeletion.remove(file,size,modified,true,true,true,true)==LocalDeletion.Result.BUSY,"An upload/export/analysis lease protects the recording");
            check(LocalDeletion.remove(file,size,modified,true,false,false,false)==LocalDeletion.Result.NEEDS_CONFIRMATION,"Unverified files require explicit risk confirmation");
            check(LocalDeletion.remove(file,size,modified,true,false,true,true,true,false)==LocalDeletion.Result.PROTECTED,"Verified important recordings survive batch deletion without individual consent");
            check(LocalDeletion.remove(file,size,modified,true,false,false,true,true,false)==LocalDeletion.Result.PROTECTED,"Consent to remove an unuploaded copy does not waive important protection");
            check(Arrays.equals(Files.readAllBytes(recording),original),"Refused operations preserve the actual bytes");
            check(LocalDeletion.remove(file,size+1,modified,true,false,true,true)==LocalDeletion.Result.CHANGED,"Changed size invalidates the confirmation");
            check(LocalDeletion.remove(file,size,modified+1000,true,false,true,true)==LocalDeletion.Result.CHANGED,"Changed modification time invalidates the confirmation");
            check(LocalDeletion.remove(file,size,modified,true,false,true,false)==LocalDeletion.Result.DELETED,"A verified, idle, matching file can be removed");
            check(!Files.exists(recording) && Arrays.equals(Files.readAllBytes(other),original),"Deletion only removes the chosen local file");
            check(LocalDeletion.remove(file,size,modified,true,false,true,true)==LocalDeletion.Result.MISSING,"Repeated deletion reports absence");
            Files.write(recording,original);
            check(LocalDeletion.remove(file,file.length(),file.lastModified(),true,false,false,true)==LocalDeletion.Result.DELETED,"Explicit warning confirmation permits removal of an unuploaded copy");
            Files.write(recording,original);
            check(LocalDeletion.remove(file,file.length(),file.lastModified(),true,false,true,false,true,true)==LocalDeletion.Result.DELETED,"Individual important-warning confirmation permits removal of exactly that recording");
            Files.write(recording,original);
            File refuses = new File(recording.toString()) { @Override public boolean delete() { return false; } };
            check(LocalDeletion.remove(refuses,refuses.length(),refuses.lastModified(),true,false,true,true)==LocalDeletion.Result.FAILED,"Filesystem refusal is never reported as success");
            check(Arrays.equals(Files.readAllBytes(recording),original),"A failed delete preserves the file");
            for(String language:new String[]{"uk","en","pl"}) {
                I18n.use(language);
                String text=I18n.s("delete_confirm","recording.m4a");
                check(text.contains("recording.m4a") && text.contains("\n"),"Translated confirmation includes filename and readable line breaks");
                check(!I18n.s("delete_unverified_hint").equals("delete_unverified_hint"),"Unverified warning is translated");
            }
            I18n.use("uk");
        } finally { Files.deleteIfExists(recording); Files.deleteIfExists(other); Files.deleteIfExists(dir); }
        System.out.println("PASS: "+checks+" manual-deletion / real-filesystem / confirmation assertions");
    }
}
