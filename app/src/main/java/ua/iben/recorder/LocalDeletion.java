package ua.iben.recorder;

import java.io.File;

/** Called under the recording-file lock, only with an indexed, validated path. */
public final class LocalDeletion {
    public enum Result { DELETED, MISSING, NOT_READY, BUSY, CHANGED, NEEDS_CONFIRMATION, PROTECTED, FAILED }
    public static Result remove(File file, long expectedSize, long expectedModified,
            boolean published, boolean busy, boolean verified, boolean allowUnverified) {
        return remove(file,expectedSize,expectedModified,published,busy,verified,allowUnverified,false,false);
    }
    public static Result remove(File file, long expectedSize, long expectedModified,
            boolean published, boolean busy, boolean verified, boolean allowUnverified,
            boolean important, boolean allowImportant) {
        if (!published) return Result.NOT_READY;
        if (important && !allowImportant) return Result.PROTECTED;
        if (busy) return Result.BUSY;
        if (!file.isFile()) return Result.MISSING;
        if (file.length() != expectedSize || file.lastModified() != expectedModified) return Result.CHANGED;
        if (!verified && !allowUnverified) return Result.NEEDS_CONFIRMATION;
        return file.delete() ? Result.DELETED : Result.FAILED;
    }
}
