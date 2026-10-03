package ua.iben.recorder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.text.InputFilter;
import android.widget.*;
import java.util.List;
import java.util.function.LongConsumer;

final class Bookmarks {
    private static boolean closed(Activity activity) { return activity.isDestroyed() || activity.isFinishing(); }
    static void add(Activity activity,Ui ui,String recordId,long position) {
        add(activity,ui,recordId,position,() -> { });
    }
    static void add(Activity activity,Ui ui,String recordId,long position,Runnable changed) {
        Context app=activity.getApplicationContext();
        RecordEdits.worker.execute(() -> {
            RecordIndex.Bookmark added=null;String error=null;
            synchronized(RecordingFiles.LOCK) {
                try(RecordIndex index=new RecordIndex(app)) { added=index.addBookmark(recordId,position,""); }
                catch(Exception e) { error=I18n.s("bookmark_unavailable"); }
            }
            RecordIndex.Bookmark bookmark=added;String problem=error;
            activity.runOnUiThread(() -> {
                if(closed(activity))return;
                if(problem!=null)ui.toast(problem);
                else { changed.run();ui.toast(I18n.s("bookmark_saved",Ui.clock(bookmark.position)));edit(activity,ui,bookmark,true,changed); }
            });
        });
    }
    private static void edit(Activity activity,Ui ui,RecordIndex.Bookmark bookmark,boolean added,Runnable changed) {
        EditText label=new EditText(activity);label.setSingleLine(true);
        label.setFilters(new InputFilter[]{new InputFilter.LengthFilter(160)});
        label.setHint(I18n.s("bookmark_note"));label.setText(bookmark.label);
        new AlertDialog.Builder(activity).setTitle(Ui.clock(bookmark.position))
                .setMessage(I18n.s(added ? "bookmark_saved_hint" : "bookmark_note"))
                .setView(label).setNegativeButton(I18n.s("close"),null)
                .setPositiveButton(I18n.s("bookmark_save_note"),(d,w) -> {
                    String text=label.getText().toString();Context app=activity.getApplicationContext();
                    RecordEdits.worker.execute(() -> {
                        boolean success=true;
                        synchronized(RecordingFiles.LOCK) {
                            try(RecordIndex index=new RecordIndex(app)) { index.renameBookmark(bookmark.id,text); }
                            catch(Exception e) { success=false; }
                        }
                        boolean ok=success;
                        activity.runOnUiThread(() -> { if(!closed(activity)) {if(ok)changed.run();ui.toast(I18n.s(ok ? "bookmark_note_saved" : "bookmark_unavailable"));} });
                    });
                }).show();
    }
    static void show(Activity activity,Ui ui,String recordId,LongConsumer seek,Runnable changed) {
        Context app=activity.getApplicationContext();
        RecordEdits.worker.execute(() -> {
            List<RecordIndex.Bookmark> found=null;
            synchronized(RecordingFiles.LOCK) {
                try(RecordIndex index=new RecordIndex(app)) { found=index.bookmarks(recordId); }
                catch(Exception ignored) { }
            }
            List<RecordIndex.Bookmark> marks=found;
            activity.runOnUiThread(() -> {
                if(closed(activity))return;
                if(marks==null || marks.isEmpty()) { ui.toast(I18n.s(marks==null ? "bookmark_unavailable" : "bookmarks_empty"));return; }
                String[] labels=new String[marks.size()];
                for(int i=0;i<labels.length;i++)labels[i]=Ui.clock(marks.get(i).position)+
                        (marks.get(i).label.isEmpty() ? "" : " · "+marks.get(i).label);
                ListView list=new ListView(activity);
                list.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_list_item_1,labels));
                AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(I18n.s("bookmarks"))
                        .setMessage(I18n.s("bookmarks_hint")).setView(list)
                        .setNegativeButton(I18n.s("close"),null).create();
                list.setOnItemClickListener((a,v,p,id) -> { dialog.dismiss();seek.accept(marks.get(p).position); });
                list.setOnItemLongClickListener((a,v,p,id) -> {
                    RecordIndex.Bookmark bookmark=marks.get(p);dialog.dismiss();
                    new AlertDialog.Builder(activity).setTitle(labels[p])
                            .setItems(new String[]{I18n.s("bookmark_edit"),I18n.s("bookmark_delete")},(d,w) -> {
                                if(w==0)edit(activity,ui,bookmark,false,changed);
                                else new AlertDialog.Builder(activity).setTitle(I18n.s("bookmark_delete"))
                                        .setMessage(labels[p]).setNegativeButton(I18n.s("cancel"),null)
                                        .setPositiveButton(I18n.s("delete_action"),(confirm,which) -> RecordEdits.worker.execute(() -> {
                                            boolean ok=true;
                                            synchronized(RecordingFiles.LOCK) {
                                                try(RecordIndex index=new RecordIndex(app)) { index.removeBookmark(bookmark.id); }
                                                catch(Exception e) { ok=false; }
                                            }
                                            boolean success=ok;
                                            activity.runOnUiThread(() -> { if(!closed(activity)) {if(success)changed.run();ui.toast(I18n.s(success ? "bookmark_deleted" : "bookmark_unavailable"));} });
                                        })).show();
                            }).show();
                    return true;
                });
                dialog.show();
            });
        });
    }
}
