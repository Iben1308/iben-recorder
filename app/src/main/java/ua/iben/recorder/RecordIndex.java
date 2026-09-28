package ua.iben.recorder;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

/** Durable ownership ledger: date-only filenames alone never authorize deletion. */
final class RecordIndex extends SQLiteOpenHelper {
    static final int ACTIVE = 0;
    static final int READY = 1;
    static final int PUBLISHED = 2;
    static final int FAILED = 3;
    static final class Entry {
        String id;
        long start;
        String zone;
        long duration;
        String finalName;
        int state;
        String verifiedTarget;
        long verifiedSize;
        long verifiedModified;
        String verifiedHash;
        String remoteName;
    }
    RecordIndex(Context context) { super(context, "recordings.db", null, 2); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE records (id TEXT PRIMARY KEY, start_ms INTEGER NOT NULL, "
                + "zone TEXT NOT NULL, duration_ms INTEGER NOT NULL DEFAULT 0, "
                + "final_name TEXT UNIQUE, state INTEGER NOT NULL)");
        addCloudColumns(db);
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion == 1 && newVersion == 2) addCloudColumns(db);
        else throw new IllegalStateException("Unsupported recording index upgrade");
    }
    private static void addCloudColumns(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE records ADD COLUMN verified_target TEXT");
        db.execSQL("ALTER TABLE records ADD COLUMN verified_size INTEGER NOT NULL DEFAULT -1");
        db.execSQL("ALTER TABLE records ADD COLUMN verified_modified INTEGER NOT NULL DEFAULT -1");
        db.execSQL("ALTER TABLE records ADD COLUMN verified_hash TEXT");
        db.execSQL("ALTER TABLE records ADD COLUMN remote_name TEXT");
    }
    void remoteName(String id, String name) {
        ContentValues v = new ContentValues(); v.put("remote_name", name);
        getWritableDatabase().update("records", v, "id=?", new String[]{id});
    }
    void verified(String id, String target, DavClient.Receipt receipt, String remoteName) {
        ContentValues v = new ContentValues();
        v.put("verified_target", target); v.put("verified_size", receipt.size);
        v.put("verified_modified", receipt.modified); v.put("verified_hash", receipt.sha256);
        v.put("remote_name", remoteName);
        if (getWritableDatabase().update("records", v, "id=? AND state=?", new String[]{id, String.valueOf(PUBLISHED)}) != 1)
            throw new IllegalStateException("Готовий запис відсутній у реєстрі");
    }
    void create(String id, long start, String zone) {
        ContentValues values = new ContentValues();
        values.put("id", id); values.put("start_ms", start); values.put("zone", zone); values.put("state", ACTIVE);
        getWritableDatabase().insertOrThrow("records", null, values);
    }
    void prepared(String id, long duration, String finalName) {
        ContentValues values = new ContentValues();
        values.put("duration_ms", duration); values.put("final_name", finalName); values.put("state", READY);
        if (getWritableDatabase().update("records", values, "id=?", new String[]{id}) != 1)
            throw new IllegalStateException("Missing recording index entry");
    }
    void state(String id, int state) {
        ContentValues values = new ContentValues(); values.put("state", state);
        getWritableDatabase().update("records", values, "id=?", new String[]{id});
    }
    boolean nameReserved(String name, String exceptId) {
        try (Cursor c = getReadableDatabase().query("records", new String[]{"id"},
                "final_name=? AND id<>?", new String[]{name, exceptId}, null, null, null)) {
            return c.moveToFirst();
        }
    }
    void remove(String id) { getWritableDatabase().delete("records", "id=?", new String[]{id}); }
    List<Entry> all() {
        List<Entry> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("records",
                new String[]{"id", "start_ms", "zone", "duration_ms", "final_name", "state",
                        "verified_target", "verified_size", "verified_modified", "verified_hash", "remote_name"},
                null, null, null, null, "start_ms ASC")) {
            while (cursor.moveToNext()) {
                Entry e = new Entry();
                e.id = cursor.getString(0); e.start = cursor.getLong(1); e.zone = cursor.getString(2);
                e.duration = cursor.getLong(3); e.finalName = cursor.getString(4); e.state = cursor.getInt(5);
                e.verifiedTarget = cursor.getString(6); e.verifiedSize = cursor.getLong(7);
                e.verifiedModified = cursor.getLong(8); e.verifiedHash = cursor.getString(9);
                e.remoteName = cursor.getString(10);
                result.add(e);
            }
        }
        return result;
    }
}
