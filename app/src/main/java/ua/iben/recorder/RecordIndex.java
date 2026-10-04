package ua.iben.recorder;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

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
        int receiptKind;
        String verifiedEtag;
        String remoteName;
        long position;
        boolean listened;
        boolean important;
        String heardRanges;
        final Map<String, Boolean> cloudDeletes = new HashMap<>();
    }
    RecordIndex(Context context) { super(context, "recordings.db", null, 6); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE records (id TEXT PRIMARY KEY, start_ms INTEGER NOT NULL, "
                + "zone TEXT NOT NULL, duration_ms INTEGER NOT NULL DEFAULT 0, "
                + "final_name TEXT UNIQUE, state INTEGER NOT NULL)");
        addCloudColumns(db);
        addPlaybackColumns(db);
        addCloudDeletions(db);
        addLibraryColumns(db);
        addReceiptKind(db);
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 1 || newVersion > 6) throw new IllegalStateException("Unsupported recording index upgrade");
        if (oldVersion < 2) addCloudColumns(db);
        if (oldVersion < 3) addPlaybackColumns(db);
        if (oldVersion < 4) addCloudDeletions(db);
        if (oldVersion < 5) addLibraryColumns(db);
        if (oldVersion < 6) addReceiptKind(db);
    }
    private static void addReceiptKind(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE records ADD COLUMN receipt_kind INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE records ADD COLUMN verified_etag TEXT");
        db.execSQL("UPDATE records SET receipt_kind=1 WHERE verified_hash IS NOT NULL");
    }
    private static void addLibraryColumns(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE records ADD COLUMN important INTEGER NOT NULL DEFAULT 0");
        db.execSQL("CREATE TABLE bookmarks (id TEXT PRIMARY KEY, record_id TEXT NOT NULL, position_ms INTEGER NOT NULL, label TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX bookmarks_record ON bookmarks(record_id,position_ms)");
    }
    boolean important(String id, boolean value) {
        ContentValues values = new ContentValues(); values.put("important", value ? 1 : 0);
        return getWritableDatabase().update("records", values, "id=? AND state=?", new String[]{id,String.valueOf(PUBLISHED)}) == 1;
    }
    boolean isImportant(String id) {
        try (Cursor c=getReadableDatabase().query("records",new String[]{"important"},"id=?",new String[]{id},null,null,null)) {
            return !c.moveToFirst() || c.getInt(0)!=0; // Missing cannot authorize a destructive operation.
        }
    }
    boolean listened(String id, boolean value) {
        ContentValues values=new ContentValues(); values.put("listened",value ? 1 : 0);
        return getWritableDatabase().update("records",values,"id=? AND state=?",new String[]{id,String.valueOf(PUBLISHED)})==1;
    }
    static final class Bookmark {
        final String id, recordId, label;
        final long position;
        Bookmark(String id,String recordId,long position,String label) {
            this.id=id;this.recordId=recordId;this.position=position;this.label=label;
        }
    }
    Bookmark addBookmark(String recordId,long position,String label) {
        long duration=0; int state;
        try(Cursor c=getReadableDatabase().query("records",new String[]{"duration_ms","state"},"id=?",new String[]{recordId},null,null,null)) {
            if(!c.moveToFirst()) throw new IllegalStateException(I18n.s("bookmark_unavailable"));
            duration=c.getLong(0);state=c.getInt(1);
        }
        if(state==FAILED) throw new IllegalStateException(I18n.s("bookmark_unavailable"));
        position=Math.max(0,position);
        if(duration>0)position=Math.min(position,duration-1);
        String id=java.util.UUID.randomUUID().toString(); label=bookmarkLabel(label);
        ContentValues values=new ContentValues(); values.put("id",id);values.put("record_id",recordId);
        values.put("position_ms",position);values.put("label",label);
        getWritableDatabase().insertOrThrow("bookmarks",null,values);
        return new Bookmark(id,recordId,position,label);
    }
    List<Bookmark> bookmarks(String recordId) {
        List<Bookmark> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("bookmarks",new String[]{"id","position_ms","label"},
                "record_id=?",new String[]{recordId},null,null,"position_ms ASC, id ASC")) {
            while(c.moveToNext())result.add(new Bookmark(c.getString(0),recordId,c.getLong(1),c.getString(2)));
        }
        return result;
    }
    private static String bookmarkLabel(String text) {
        String value=text==null ? "" : text.trim(); return value.length()>160 ? value.substring(0,160) : value;
    }
    void renameBookmark(String id,String label) {
        ContentValues values=new ContentValues();values.put("label",bookmarkLabel(label));
        getWritableDatabase().update("bookmarks",values,"id=?",new String[]{id});
    }
    void removeBookmark(String id) { getWritableDatabase().delete("bookmarks","id=?",new String[]{id}); }
    private static void addCloudDeletions(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE cloud_deletions (record_id TEXT NOT NULL, target TEXT NOT NULL, "
                + "done INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(record_id,target))");
    }
    void beginCloudDelete(String id, String target) {
        ContentValues values = new ContentValues(); values.put("record_id", id); values.put("target", target);
        getWritableDatabase().insertWithOnConflict("cloud_deletions", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        try (Cursor cursor = getReadableDatabase().query("cloud_deletions", new String[]{"record_id"},
                "record_id=? AND target=?", new String[]{id,target}, null,null,null)) {
            if (!cursor.moveToFirst()) throw new IllegalStateException("Cloud deletion intent was not saved");
        }
        ContentValues pending = new ContentValues(); pending.put("done", 0);
        getWritableDatabase().update("cloud_deletions", pending, "record_id=? AND target=?", new String[]{id,target});
    }
    void finishCloudDelete(String id, String target) {
        ContentValues values = new ContentValues(); values.put("done", 1);
        if (getWritableDatabase().update("cloud_deletions", values, "record_id=? AND target=?", new String[]{id,target}) != 1)
            throw new IllegalStateException("Cloud deletion intent is missing");
    }
    void resumeCloudUpload(String id, String target) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("cloud_deletions", "record_id=? AND target=?", new String[]{id,target});
            clearVerification(id);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    private static void addPlaybackColumns(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE records ADD COLUMN playback_ms INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE records ADD COLUMN listened INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE records ADD COLUMN heard_ranges TEXT NOT NULL DEFAULT ''");
    }
    void playback(String id, long position, String ranges, Boolean listened) {
        ContentValues values = new ContentValues();
        values.put("playback_ms", Math.max(0, position)); values.put("heard_ranges", ranges);
        if (listened != null) values.put("listened", listened ? 1 : 0);
        getWritableDatabase().update("records", values, "id=? AND state=?", new String[]{id, String.valueOf(PUBLISHED)});
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
    void clearVerification(String id) {
        ContentValues v = new ContentValues();
        v.putNull("verified_target"); v.putNull("verified_hash");v.putNull("verified_etag");v.put("receipt_kind",TransferPolicy.NONE);
        v.put("verified_size", -1L); v.put("verified_modified", -1L);
        getWritableDatabase().update("records", v, "id=?", new String[]{id});
    }
    void verified(String id, String target, DavClient.Receipt receipt, String remoteName) {
        ContentValues v = new ContentValues();
        v.put("verified_target", target); v.put("verified_size", receipt.size);
        v.put("verified_modified", receipt.modified); v.put("verified_hash", receipt.sha256);
        v.put("receipt_kind",receipt.kind);v.put("verified_etag",receipt.etag);
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
    void remove(String id) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            db.delete("bookmarks","record_id=?",new String[]{id});
            db.delete("cloud_deletions","record_id=?",new String[]{id});
            db.delete("records","id=?",new String[]{id});
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }
    List<Entry> all() {
        List<Entry> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("records",
                new String[]{"id", "start_ms", "zone", "duration_ms", "final_name", "state",
                        "verified_target", "verified_size", "verified_modified", "verified_hash", "remote_name", "playback_ms", "listened", "heard_ranges", "important", "receipt_kind", "verified_etag"},
                null, null, null, null, "start_ms ASC")) {
            while (cursor.moveToNext()) {
                Entry e = new Entry();
                e.id = cursor.getString(0); e.start = cursor.getLong(1); e.zone = cursor.getString(2);
                e.duration = cursor.getLong(3); e.finalName = cursor.getString(4); e.state = cursor.getInt(5);
                e.verifiedTarget = cursor.getString(6); e.verifiedSize = cursor.getLong(7);
                e.verifiedModified = cursor.getLong(8); e.verifiedHash = cursor.getString(9);
                e.remoteName = cursor.getString(10);
                e.position=cursor.getLong(11);e.listened=cursor.getInt(12)!=0;e.heardRanges=cursor.getString(13);e.important=cursor.getInt(14)!=0;
                e.receiptKind=cursor.getInt(15);e.verifiedEtag=cursor.getString(16);
                result.add(e);
            }
        }
        Map<String, Entry> byId = new HashMap<>();
        for (Entry entry : result) byId.put(entry.id, entry);
        try (Cursor cursor = getReadableDatabase().query("cloud_deletions", new String[]{"record_id","target","done"},
                null, null, null, null, null)) {
            while (cursor.moveToNext()) {
                Entry entry = byId.get(cursor.getString(0));
                if (entry != null) entry.cloudDeletes.put(cursor.getString(1), cursor.getInt(2) != 0);
            }
        }
        return result;
    }
}
