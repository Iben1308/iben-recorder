package ua.iben.recorder;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Process;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class ListenPanel {
    final LinearLayout view;
    private final MainActivity activity;
    private final Context app;
    private final Ui ui;
    private final Config config;
    // Ordered across Activity recreation: a new list query follows the previous final checkpoint.
    // No permanent background thread and no waveform decoding on this executor.
    private static final ExecutorService worker = RecordEdits.worker;
    private final List<RecordingFiles.Item> items = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    // Preserve edits made while an asynchronous list query is in flight.
    private final Map<String, RecordingFiles.Item> changedWhileLoading = new HashMap<>();
    private final RecordsAdapter adapter = new RecordsAdapter();
    private final TextView selectedLabel, clock, analysis, count;
    private final Button play, zoom, mark, delete, important, addBookmark, bookmarks;
    private final WaveformView wave;
    private final SeekBar timeline;
    private MediaPlayer player;
    private RecordingFiles.Item selected;
    private RecordingFiles.Lease lease;
    private PlaybackProgress progress;
    private long listGeneration;
    private boolean deleting, selecting;
    private final java.util.Set<String> chosen=new java.util.LinkedHashSet<>();
    private LinearLayout selectionBar;
    private TextView selectionCount;
    private Button selectionToggle;
    private BatchWork batchWork;
    private CloudDeletionTask cloudDeletion;
    private AlertDialog deletionDialog;
    private long generation, savedPosition, lastSavedAt, observedPosition = -1, observedAt, pendingSeek;
    private boolean ready, gone, seeking, loading, analysisReady, visible, seekPending, startAfterSeek, ended, cacheLoading, needsAnalysis;
    private int filter;
    private float speed;
    private WaveformService.State waveState;
    private final AudioManager audio;
    private final AudioManager.OnAudioFocusChangeListener focus = change -> { if (change <= 0) pause(); };
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) { pause(); }
    };

    ListenPanel(MainActivity activity, Ui ui, Config config) {
        this.activity = activity; this.app = activity.getApplicationContext(); this.ui = ui; this.config = config;
        audio = activity.getSystemService(AudioManager.class);
        speed = config.prefs.getFloat("playback_speed", 1f);
        filter = Math.max(0, Math.min(3, config.prefs.getInt("listen_filter", 0)));
        view = ui.column(); view.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), 0);
        LinearLayout header = ui.column(), card = ui.card(header);
        selectedLabel = ui.text(card, I18n.s("choose_recording"), 15, ui.ink);
        clock = ui.text(card, "00:00:00 / 00:00:00", 22, ui.ink);
        wave = new WaveformView(ui, this::seek); card.addView(wave, new LinearLayout.LayoutParams(-1, ui.dp(120)));
        timeline = new SeekBar(activity); timeline.setMax(10000); card.addView(timeline);
        timeline.setContentDescription(I18n.s("position"));
        timeline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int value, boolean user) { if (user && selected != null) seek(duration() * value / 10000L); }
            @Override public void onStartTrackingTouch(SeekBar b) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar b) { seeking = false; }
        });
        LinearLayout controls = ui.row(); card.addView(controls);
        ui.equal(controls, ui.button(null, "−10 s", () -> seek(position() - 10000), false));
        play = ui.button(null, I18n.s("play"), this::toggle, true); ui.equal(controls, play);
        ui.equal(controls, ui.button(null, "+10 s", () -> seek(position() + 10000), false));
        LinearLayout options = ui.row(); card.addView(options);
        ui.equal(options, ui.text(null, I18n.s("speed"), 13, ui.muted));
        final float[] rates = {.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f};
        Spinner speeds = new Spinner(activity);
        ArrayAdapter<String> choices = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item,
                new String[]{"0.5×", "0.75×", "1×", "1.25×", "1.5×", "1.75×", "2×"});
        choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); speeds.setAdapter(choices);
        int choice = 2; for (int i = 0; i < rates.length; i++) if (rates[i] == speed) choice = i;
        speeds.setSelection(choice); ui.equal(options, speeds);
        speeds.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> a, View v, int p, long id) {
                track(false); speed = rates[p]; config.prefs.edit().putFloat("playback_speed", speed).apply();
                if (isPlaying()) applySpeed();
            }
            @Override public void onNothingSelected(AdapterView<?> a) { }
        });
        zoom = ui.button(null, "1×", this::zoomPressed, false); ui.equal(options, zoom);
        zoom.setContentDescription(I18n.s("zoom"));
        mark = ui.button(card, I18n.s("mark_listened"), () -> { if (selected != null) mark(selected, !selected.listened); }, false);
        mark.setEnabled(false);
        important=ui.button(card,I18n.s("important_add"),() -> {if(selected!=null)important(selected,!selected.important);},false);
        LinearLayout bookmarkRow=ui.row();card.addView(bookmarkRow);
        addBookmark=ui.button(null,I18n.s("bookmark_add"),() -> {
            if(selected!=null && !deleting)Bookmarks.add(activity,ui,selected.id,position());
        },false);ui.equal(bookmarkRow,addBookmark);
        bookmarks=ui.button(null,I18n.s("bookmarks"),() -> {
            if(selected==null || deleting)return;
            RecordingFiles.Item item=selected;
            Bookmarks.show(activity,ui,item.id,millis -> {
                if(gone || deleting)return;
                if(selected==null || !item.id.equals(selected.id) || player==null) {
                    open(item,false);savedPosition=Math.max(0,Math.min(item.duration-1,millis));
                    if(selected!=null)selected.position=savedPosition;
                } else if(!ready) {
                    savedPosition=Math.max(0,Math.min(item.duration-1,millis));selected.position=savedPosition;
                } else seek(millis);
            });
        },false);ui.equal(bookmarkRow,bookmarks);
        ui.button(card, I18n.s("export_recording"), () -> {
            if (selected == null) { ui.toast(I18n.s("choose_recording")); return; }
            if (!deleting) activity.exportRecording(selected);
        }, false);
        delete = ui.button(card, I18n.s("delete_recording"), () -> { if (selected != null) chooseDeletion(selected); }, false);
        delete.setTextColor(ui.red); delete.setEnabled(false);
        analysis = ui.text(card, I18n.s("wave_hint"), 12, ui.muted);
        analysis.setOnClickListener(v -> {
            if (selected != null && !analysisReady && visible && !deleting) {
                WaveformService.request(app, selected.id); waveState = null; needsAnalysis = false; updateAnalysis();
            }
        });
        LinearLayout listHeader = ui.row(); header.addView(listHeader);
        count = ui.text(null, I18n.s("recordings"), 16, ui.ink); ui.equal(listHeader, count);
        ui.equal(listHeader, ui.button(null, I18n.s("refresh"), this::load, false));
        selectionToggle=ui.button(header,I18n.s("batch_select"),() -> {
            if(deleting)return;selecting=!selecting;chosen.clear();updateSelection();adapter.notifyDataSetChanged();
        },false);
        selectionBar=ui.column();header.addView(selectionBar);
        selectionCount=ui.text(selectionBar,"",13,ui.muted);
        LinearLayout selectionButtons=ui.row();selectionBar.addView(selectionButtons);
        ui.equal(selectionButtons,ui.button(null,I18n.s("batch_all"),() -> {
            if(deleting)return;for(RecordingFiles.Item item:items)if(matchesFilter(item))chosen.add(item.id);
            updateSelection();adapter.notifyDataSetChanged();
        },false));
        ui.equal(selectionButtons,ui.button(null,I18n.s("batch_clear"),() -> {
            if(deleting)return;chosen.clear();updateSelection();adapter.notifyDataSetChanged();
        },false));
        ui.equal(selectionButtons,ui.button(null,I18n.s("batch_actions"),this::batchActions,true));
        updateSelection();
        Spinner filters = ui.spinner(header, new String[]{I18n.s("filter_all"), I18n.s("filter_unlistened"), I18n.s("filter_listened"), I18n.s("filter_important")}, filter);
        filters.setContentDescription(I18n.s("record_filter"));
        filters.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> a, View v, int p, long id) {
                if(filter!=p)chosen.clear();
                filter = p; config.prefs.edit().putInt("listen_filter", p).apply(); rebuildRows();
            }
            @Override public void onNothingSelected(AdapterView<?> a) { }
        });
        ListView list = new ListView(activity); list.addHeaderView(header, null, false); list.setAdapter(adapter); list.setDividerHeight(ui.dp(6));
        list.setOnItemClickListener((a,v,p,id) -> {
            int index=p-list.getHeaderViewsCount();
            if(index<0 || index>=rows.size() || deleting)return;
            Row row=rows.get(index);
            if(row.item==null) { if(selecting)chooseDay(row.day);return; }
            if(selecting) {
                if(!chosen.remove(row.item.id))chosen.add(row.item.id);
                updateSelection();adapter.notifyDataSetChanged();
            } else { open(row.item,true);list.smoothScrollToPosition(0); }
        });
        list.setOnItemLongClickListener((a,v,p,id) -> {
            int index=p-list.getHeaderViewsCount();
            if(index<0 || index>=rows.size() || deleting)return false;
            Row row=rows.get(index);
            if(row.item==null) { selecting=true;chooseDay(row.day);return true; }
            RecordingFiles.Item item=row.item;
            List<String> actions=new ArrayList<>();
            actions.add(I18n.s(item.listened ? "mark_unlistened" : "mark_listened"));
            actions.add(I18n.s(item.important ? "important_remove" : "important_add"));
            actions.add(I18n.s("delete_recording"));
            actions.add(I18n.s("batch_select"));
            if(item.cloudDeleteState!=0)actions.add(I18n.s("cloud_upload_again"));
            new AlertDialog.Builder(activity).setTitle(item.name).setItems(actions.toArray(new String[0]),(dialog,which) -> {
                if(which==0)mark(item,!item.listened);
                else if(which==1)important(item,!item.important);
                else if(which==2)chooseDeletion(item);
                else if(which==3) { selecting=true;chosen.add(item.id);updateSelection();adapter.notifyDataSetChanged(); }
                else confirmUploadAgain(item);
            }).show();
            return true;
        });
        view.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        ui.text(view, I18n.s("listen_hint"), 11, ui.muted);
        IntentFilter noisyFilter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (android.os.Build.VERSION.SDK_INT >= 33) activity.registerReceiver(noisy, noisyFilter, Context.RECEIVER_NOT_EXPORTED);
        else activity.registerReceiver(noisy, noisyFilter);
    }

    void load() {
        if (loading || gone || deleting) return;
        if (!Platform.storageGranted(activity)) { activity.storagePermission(this::load); return; }
        checkpoint(null); changedWhileLoading.clear(); loading = true; count.setText(I18n.s("loading"));
        final long request = ++listGeneration;
        worker.execute(() -> {
            List<RecordingFiles.Item> list = null; String error = null;
            try (RecordingFiles files = new RecordingFiles(app, config)) { list = files.recordings(); }
            catch (Exception e) { error = e.getMessage(); }
            final List<RecordingFiles.Item> result = list; final String problem = error;
            activity.runOnUiThread(() -> {
                if (gone || request != listGeneration) return;
                loading = false;
                if (result != null) for (RecordingFiles.Item item : result) {
                    RecordingFiles.Item edited = changedWhileLoading.get(item.id);
                    if (edited != null) copyState(edited, item);
                }
                changedWhileLoading.clear();
                if (result == null) { count.setText(I18n.tr(problem == null ? "Немає доступу до папки записів" : problem)); return; }
                items.clear(); items.addAll(result);
                if (selected != null) {
                    boolean found = false;
                    for (RecordingFiles.Item item : items) if (item.id.equals(selected.id)) {
                        copyState(selected, item); selected = item; found = true; break;
                    }
                    if (!found) clearSelection();
                }
                rebuildRows();
                if (selected == null && visible) {
                    String last = config.prefs.getString("last_recording", "");
                    for (RecordingFiles.Item item : items) if (item.id.equals(last)) { open(item, false); break; }
                }
            });
        });
    }
    private static void copyState(RecordingFiles.Item from, RecordingFiles.Item to) {
        to.position = from.position; to.listened = from.listened; to.heardRanges = from.heardRanges;
    }
    private boolean matchesFilter(RecordingFiles.Item item) {
        return !(filter==1 && item.listened || filter==2 && !item.listened || filter==3 && !item.important);
    }
    private void important(RecordingFiles.Item item,boolean value) {
        if(deleting || gone)return;
        worker.execute(() -> {
            boolean changed=false;
            try(RecordingFiles files=new RecordingFiles(app,config)) {changed=files.important(item,value);}
            catch(Exception ignored) { }
            boolean ok=changed;
            activity.runOnUiThread(() -> {
                if(gone)return;
                if(ok) {
                    item.important=value;
                    for(RecordingFiles.Item entry:items)if(entry.id.equals(item.id))entry.important=value;
                    if(selected!=null && selected.id.equals(item.id))selected.important=value;
                    ui.toast(I18n.s(value ? "important_enabled" : "important_disabled"));
                    rebuildRows();load();
                } else ui.toast(I18n.s("unavailable"));
            });
        });
    }
    private void rebuildRows() {
        rows.clear(); Row group = null; int matches = 0;
        for (RecordingFiles.Item item : items) {
            if (!matchesFilter(item)) continue;
            LocalDate day = Instant.ofEpochMilli(item.start).atZone(ZoneId.systemDefault()).toLocalDate();
            if (group == null || !group.day.equals(day)) { group = new Row(day); rows.add(group); }
            group.count++; group.duration += item.duration; rows.add(new Row(item)); matches++;
        }
        count.setText(items.isEmpty() ? I18n.s("no_recordings") : matches == 0 ? I18n.s("no_filter_matches") : I18n.s("record_count", matches));
        java.util.Set<String> present=new java.util.HashSet<>();
        for(RecordingFiles.Item item:items)present.add(item.id);
        chosen.retainAll(present);updateSelection();
        adapter.notifyDataSetChanged(); updateMark();
    }
    private List<RecordingFiles.Item> selectedItems() {
        List<RecordingFiles.Item> result=new ArrayList<>();
        for(RecordingFiles.Item item:items)if(chosen.contains(item.id) && matchesFilter(item))result.add(item);
        return result;
    }
    private void chooseDay(LocalDate day) {
        List<RecordingFiles.Item> group=new ArrayList<>();boolean all=true;
        for(RecordingFiles.Item item:items)if(matchesFilter(item) && Instant.ofEpochMilli(item.start).atZone(ZoneId.systemDefault()).toLocalDate().equals(day)) {
            group.add(item);if(!chosen.contains(item.id))all=false;
        }
        for(RecordingFiles.Item item:group) {if(all)chosen.remove(item.id);else chosen.add(item.id);}
        updateSelection();adapter.notifyDataSetChanged();
    }
    private void updateSelection() {
        if(selectionBar==null)return;
        selectionBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        selectionToggle.setText(I18n.s(selecting ? "batch_finish" : "batch_select"));
        List<RecordingFiles.Item> list=selectedItems();long bytes=0;
        for(RecordingFiles.Item item:list)bytes+=item.bytes;
        selectionCount.setText(I18n.s("batch_count",list.size(),bytes/1048576d));
    }
    private void batchActions() {
        if(deleting || gone)return;
        List<RecordingFiles.Item> list=selectedItems();
        if(list.isEmpty()) {ui.toast(I18n.s("batch_choose"));return;}
        String[] labels={I18n.s("mark_listened"),I18n.s("mark_unlistened"),I18n.s("important_add"),
                I18n.s("important_remove"),I18n.s("batch_export"),I18n.s("delete_recording")};
        new AlertDialog.Builder(activity).setTitle(I18n.s("batch_actions")).setItems(labels,(d,w) -> {
            if(w==4) {releasePlayer();activity.exportRecordings(list);return;}
            if(w==5) {
                new AlertDialog.Builder(activity).setTitle(I18n.s("delete_recording"))
                        .setItems(new String[]{I18n.s("delete_local"),I18n.s("delete_cloud"),I18n.s("delete_both")},(dialog,mode) -> {
                            BatchWork.Action action=mode==0 ? BatchWork.Action.DELETE_LOCAL : mode==1 ? BatchWork.Action.DELETE_CLOUD : BatchWork.Action.DELETE_BOTH;
                            long bytes=0;int protectedCount=0,unverified=0;
                            for(RecordingFiles.Item item:list) {bytes+=item.bytes;if(item.important)protectedCount++;if(!item.uploaded)unverified++;}
                            String message=I18n.s("batch_delete_confirm",list.size(),bytes/1048576d,protectedCount)
                                    +(mode==0 && unverified>0 ? "\n\n"+I18n.s("delete_unverified_hint") : "")
                                    +(mode!=0 ? "\n\n"+I18n.s("batch_cloud_target",new CloudSettings(app).folder()) : "")
                                    +"\n\n"+I18n.s(mode==0 ? "delete_local" : mode==1 ? "delete_cloud" : "delete_both");
                            new AlertDialog.Builder(activity).setTitle(I18n.s("delete_recording")).setMessage(message)
                                    .setNegativeButton(I18n.s("cancel"),null)
                                    .setPositiveButton(I18n.s("delete_action"),(confirm,which) -> runBatch(list,action,null)).show();
                        }).show();return;
            }
            BatchWork.Action[] actions={BatchWork.Action.LISTENED,BatchWork.Action.UNLISTENED,BatchWork.Action.PIN,BatchWork.Action.UNPIN};
            if(w==3) new AlertDialog.Builder(activity).setTitle(I18n.s("important_remove"))
                    .setMessage(I18n.s("batch_unpin_confirm",list.size())).setNegativeButton(I18n.s("cancel"),null)
                    .setPositiveButton(I18n.s("important_remove"),(dialog,which) -> runBatch(list,actions[w],null)).show();
            else runBatch(list,actions[w],null);
        }).show();
    }
    void runBatch(List<RecordingFiles.Item> list,BatchWork.Action action,android.net.Uri destination) {
        runBatch(list,action,destination,0);
    }
    void runBatch(List<RecordingFiles.Item> list,BatchWork.Action action,android.net.Uri destination,int unavailable) {
        if(deleting || gone || list.isEmpty())return;
        deleting=true;listGeneration++;loading=false;changedWhileLoading.clear();releasePlayer();updateMark();play.setEnabled(false);
        deletionDialog=new AlertDialog.Builder(activity).setTitle(I18n.s("batch_actions"))
                .setMessage(I18n.s("batch_progress",0,list.size()))
                .setNegativeButton(I18n.s("cancel"),(d,w) -> {if(batchWork!=null)batchWork.cancel();})
                .setOnCancelListener(d -> {if(batchWork!=null)batchWork.cancel();}).create();
        deletionDialog.setCanceledOnTouchOutside(false);deletionDialog.show();
        batchWork=new BatchWork(app,list,action,destination,new BatchWork.Listener() {
            @Override public void progress(int current,int total,String name) {
                activity.runOnUiThread(() -> {if(!gone && deletionDialog!=null)deletionDialog.setMessage(I18n.s("batch_progress",current,total)+"\n"+name);});
            }
            @Override public void finished(BatchWork.Result result) {
                result.skipped+=unavailable;
                activity.runOnUiThread(() -> {
                    batchWork=null;
                    if(deletionDialog!=null) {deletionDialog.dismiss();deletionDialog=null;}
                    if(gone)return;
                    deleting=false;chosen.clear();selecting=false;
                    // Reload persisted metadata, without an old selected object overwriting batch edits.
                    clearSelection();items.clear();rebuildRows();
                    if(visible || action==BatchWork.Action.EXPORT)new AlertDialog.Builder(activity).setTitle(I18n.s("batch_actions"))
                            .setMessage(result.text()).setPositiveButton(I18n.s("close"),null).show();
                    else ui.toast(result.text());
                    load();
                });
            }
        });
        BatchWork task=batchWork;worker.execute(task::start);
    }
    private void updateMark() {
        important.setEnabled(selected!=null && !deleting);
        important.setText(I18n.s(selected!=null && selected.important ? "important_remove" : "important_add"));
        addBookmark.setEnabled(selected!=null && !deleting);bookmarks.setEnabled(selected!=null && !deleting);
        mark.setEnabled(selected != null && !deleting);
        delete.setEnabled(selected != null && !deleting);
        mark.setText(I18n.s(selected != null && selected.listened ? "mark_unlistened" : "mark_listened"));
    }
    private void mark(RecordingFiles.Item item, boolean heard) {
        if (deleting) return;
        if (selected != null && selected.id.equals(item.id)) checkpoint(heard);
        else {
            item.listened = heard;
            if (loading) changedWhileLoading.put(item.id, item);
            persist(item.id, item.position, item.heardRanges, heard);
        }
        rebuildRows();
    }
    private void chooseDeletion(RecordingFiles.Item item) {
        if (deleting || gone) return;
        new AlertDialog.Builder(activity).setTitle(I18n.s("delete_recording"))
                .setItems(new String[]{I18n.s("delete_local"), I18n.s("delete_cloud"), I18n.s("delete_both")},
                        (dialog, which) -> confirmDelete(item, which)).show();
    }
    private void confirmDelete(RecordingFiles.Item item, int mode) {
        if (deleting || gone) return;
        CloudSettings cloud = new CloudSettings(app);
        if (mode != 0 && (!item.cloudTarget.equals(cloud.targetKey())
                || !item.uploaded && item.cloudDeleteState == 0)) {
            new AlertDialog.Builder(activity).setTitle(I18n.s("delete_cloud"))
                    .setMessage(I18n.s("cloud_delete_no_receipt")).setPositiveButton(I18n.s("close"), null).show();
            return;
        }
        String message = mode == 0 ? I18n.s("delete_confirm", item.name) + "\n\n"
                + I18n.s(item.uploaded ? "delete_verified_hint" : "delete_unverified_hint")
                : I18n.s(mode == 1 ? "delete_cloud_confirm" : "delete_both_confirm", item.name, cloud.folder());
        final boolean allowImportant=item.important;
        if(allowImportant)message=I18n.s("important_delete_warning")+"\n\n"+message;
        new AlertDialog.Builder(activity).setTitle(I18n.s(mode == 0 ? "delete_local" : mode == 1 ? "delete_cloud" : "delete_both"))
                .setMessage(message).setNegativeButton(I18n.s("cancel"), null)
                .setPositiveButton(I18n.s("delete_action"), (dialog, which) -> {
                    if (mode == 0) deleteRecording(item, !item.uploaded,allowImportant); else deleteCloud(item, mode == 2,allowImportant);
                }).show();
    }
    private void deleteRecording(RecordingFiles.Item item, boolean allowUnverified,boolean allowImportant) {
        if (deleting || gone) return;
        deleting = true; listGeneration++; loading = false; changedWhileLoading.clear();
        if (selected != null && selected.id.equals(item.id)) releasePlayer();
        updateMark(); play.setEnabled(false);
        worker.execute(() -> {
            LocalDeletion.Result result = LocalDeletion.Result.FAILED;
            try (RecordingFiles files = new RecordingFiles(app, config)) { result = files.deleteLocal(item, allowUnverified,allowImportant); }
            catch (Exception e) { AppLog.write(app, "Local deletion: " + e.getClass().getSimpleName()); }
            final LocalDeletion.Result outcome = result;
            activity.runOnUiThread(() -> {
                if (gone) return;
                deleting = false;
                if (outcome == LocalDeletion.Result.DELETED || outcome == LocalDeletion.Result.MISSING) {
                    if (selected != null && selected.id.equals(item.id)) clearSelection();
                    items.removeIf(record -> record.id.equals(item.id)); rebuildRows();
                    ui.toast(I18n.s(outcome == LocalDeletion.Result.DELETED ? "delete_done" : "delete_missing"));
                    SyncScheduler.kick(app);
                } else {
                    new AlertDialog.Builder(activity).setTitle(I18n.s("delete_failed"))
                            .setMessage(deletionError(outcome)).setPositiveButton(I18n.s("close"), null).show();
                }
                updateMark(); load();
            });
        });
    }
    private static String deletionError(LocalDeletion.Result result) {
        return I18n.s(result == LocalDeletion.Result.PROTECTED ? "important_protected" : result == LocalDeletion.Result.BUSY ? "delete_busy"
                : result == LocalDeletion.Result.CHANGED || result == LocalDeletion.Result.NEEDS_CONFIRMATION ? "delete_changed"
                : result == LocalDeletion.Result.NOT_READY ? "delete_not_ready" : "delete_failed");
    }
    private void deleteCloud(RecordingFiles.Item item, boolean both,boolean allowImportant) {
        if (deleting || gone) return;
        deleting = true; listGeneration++; loading = false; changedWhileLoading.clear();
        if (selected != null && selected.id.equals(item.id)) releasePlayer();
        updateMark(); play.setEnabled(false);
        deletionDialog = new AlertDialog.Builder(activity).setTitle(I18n.s(both ? "delete_both" : "delete_cloud"))
                .setMessage(I18n.s("cloud_delete_progress", 0))
                .setNegativeButton(I18n.s("cancel"), (dialog, which) -> { if (cloudDeletion != null) cloudDeletion.cancel(); })
                .setOnCancelListener(dialog -> { if (cloudDeletion != null) cloudDeletion.cancel(); }).create();
        deletionDialog.setCanceledOnTouchOutside(false); deletionDialog.show();
        cloudDeletion = new CloudDeletionTask(app, item, both,allowImportant, new CloudDeletionTask.Listener() {
            private int lastPercent = -1;
            @Override public void progress(int percent) {
                if (percent == lastPercent) return;
                lastPercent = percent;
                activity.runOnUiThread(() -> {
                    if (!gone && deletionDialog != null) deletionDialog.setMessage(I18n.s("cloud_delete_progress", percent));
                });
            }
            @Override public void finished(boolean remoteDone, LocalDeletion.Result local, String error) {
                activity.runOnUiThread(() -> {
                    cloudDeletion = null;
                    if (deletionDialog != null) { deletionDialog.dismiss(); deletionDialog = null; }
                    if (gone) return;
                    deleting = false;
                    boolean localGone = local == LocalDeletion.Result.DELETED || local == LocalDeletion.Result.MISSING;
                    if (localGone) {
                        if (selected != null && selected.id.equals(item.id)) clearSelection();
                        items.removeIf(record -> record.id.equals(item.id)); rebuildRows();
                    }
                    String message = error;
                    if (remoteDone && both && !localGone) {
                        message = I18n.s("cloud_delete_partial") + "\n\n" + (error == null ? deletionError(local) : error);
                    } else if (error != null) message = error + "\n\n" + I18n.s("cloud_delete_kept");
                    if (message != null && visible) {
                        new AlertDialog.Builder(activity).setTitle(I18n.s("delete_recording"))
                                .setMessage(message).setPositiveButton(I18n.s("close"), null).show();
                    } else if (message != null) ui.toast(message);
                    else ui.toast(I18n.s(both ? "delete_both_done" : "cloud_delete_done"));
                    updateMark(); load(); SyncScheduler.kick(app);
                });
            }
        });
        cloudDeletion.start();
    }
    private void confirmUploadAgain(RecordingFiles.Item item) {
        if (deleting || gone) return;
        new AlertDialog.Builder(activity).setTitle(I18n.s("cloud_upload_again"))
                .setMessage(I18n.s("cloud_upload_again_confirm", item.name))
                .setNegativeButton(I18n.s("cancel"), null)
                .setPositiveButton(I18n.s("cloud_upload_again"), (dialog, which) -> {
                    deleting = true; listGeneration++; loading = false; changedWhileLoading.clear();
                    if (selected != null && selected.id.equals(item.id)) releasePlayer();
                    updateMark(); play.setEnabled(false);
                    worker.execute(() -> {
                        String error = null;
                        try (RecordingFiles files = new RecordingFiles(app, config)) { files.resumeCloudUpload(item); }
                        catch (Exception e) { error = e.getMessage() == null ? I18n.s("delete_failed") : I18n.tr(e.getMessage()); }
                        String problem = error;
                        SyncScheduler.kick(app);
                        activity.runOnUiThread(() -> {
                            if (gone) return;
                            deleting = false; ui.toast(problem == null ? I18n.s("cloud_upload_resumed") : problem);
                            updateMark(); load();
                        });
                    });
                }).show();
    }
    private void clearSelection() {
        releasePlayer(); generation++;
        selected = null; progress = null; savedPosition = 0; ended = false;
        analysisReady = false; cacheLoading = false; needsAnalysis = false; waveState = null;
        selectedLabel.setText(I18n.s("choose_recording")); analysis.setText(I18n.s("wave_hint"));
        clock.setText("00:00:00 / 00:00:00"); wave.data(null, 0, config.silenceDb());
        timeline.setProgress(0); zoom.setText("1×"); play.setEnabled(false); updateMark();
    }
    private void persist(String id, long position, String ranges, Boolean heard) {
        worker.execute(() -> {
            synchronized (RecordingFiles.LOCK) {
                try (RecordIndex index = new RecordIndex(app)) { index.playback(id, position, ranges, heard); }
                catch (RuntimeException e) { AppLog.write(app, "Playback history: " + e.getClass().getSimpleName()); }
            }
        });
    }
    private void checkpoint(Boolean heard) {
        if (gone || selected == null || progress == null) return;
        track(false); savedPosition = position(); selected.position = savedPosition; selected.heardRanges = progress.encode();
        if (heard != null) selected.listened = heard;
        if (loading) changedWhileLoading.put(selected.id, selected);
        for (RecordingFiles.Item item : items) if (item.id.equals(selected.id)) copyState(selected, item);
        persist(selected.id, savedPosition, selected.heardRanges, heard); lastSavedAt = SystemClock.elapsedRealtime();
    }
    private void open(RecordingFiles.Item item, boolean autoplay) {
        if (deleting) return;
        releasePlayer();
        if (selected != null && selected.id.equals(item.id)) copyState(selected, item);
        selected = item; savedPosition = PlaybackProgress.resume(item.position, item.duration);
        ended = false; seekPending = false; startAfterSeek = false;
        progress = new PlaybackProgress(item.duration, item.heardRanges);
        config.prefs.edit().putString("last_recording", item.id).apply();
        selectedLabel.setText(item.name); wave.data(null, item.duration, config.silenceDb()); zoom.setText("1×");
        clock.setText(Ui.clock(savedPosition) + " / " + Ui.clock(item.duration));
        try {
            lease = RecordingFiles.lease(item.file);
            MediaPlayer p = new MediaPlayer(); player = p;
            p.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            p.setDataSource(item.file.getAbsolutePath());
            p.setOnPreparedListener(mp -> {
                if (player != mp) return;
                ready = true;
                if (savedPosition > 0) { startAfterSeek = autoplay && visible; seek(savedPosition); }
                else if (autoplay && visible) resume();
                tick();
            });
            p.setOnSeekCompleteListener(mp -> {
                if (player != mp) return;
                seekPending = false; observedPosition = -1; checkpoint(null);
                boolean start = startAfterSeek; startAfterSeek = false;
                if (start && visible) resume();
            });
            p.setOnCompletionListener(mp -> {
                if (player != mp) return;
                track(true); ended = true; savedPosition = 0;
                Boolean heard = progress.complete() ? Boolean.TRUE : null;
                checkpoint(heard); rebuildRows(); play.setText(I18n.s("play")); audio.abandonAudioFocus(focus);
            });
            p.setOnErrorListener((mp, what, extra) -> {
                if (player == mp) { releasePlayer(); ui.toast(I18n.s("play_failed", what, extra)); } return true;
            });
            p.prepareAsync();
        } catch (Exception e) { releasePlayer(); ui.toast(e.getMessage() == null ? I18n.s("unavailable") : e.getMessage()); }
        loadWaveform(item); adapter.notifyDataSetChanged(); updateMark();
    }
    private boolean isPlaying() { try { return ready && player != null && player.isPlaying(); } catch (RuntimeException e) { return false; } }
    private void zoomPressed() { zoom.setText(wave.zoom() + "×"); }
    void visible(boolean value) {
        visible = value;
        if (!value) pause();
        else if (selected != null && player == null) open(selected, false);
    }
    private void toggle() { if (deleting) return; if (isPlaying()) pause(); else if (ready) resume(); else if (selected != null && player == null) open(selected, true); }
    private void track(boolean completion) {
        if (!ready || progress == null || seekPending || (!completion && !isPlaying())) return;
        long now = SystemClock.elapsedRealtime(), at = completion ? duration() : position();
        if (observedPosition >= 0) {
            long delta = at - observedPosition, elapsed = Math.max(0, now - observedAt);
            if (delta >= 0 && delta <= elapsed * speed + 1000) progress.add(observedPosition, at);
        }
        observedPosition = completion ? -1 : at; observedAt = now;
    }
    private void resume() {
        if (!ready || player == null || !visible || deleting) return;
        if (seekPending) { startAfterSeek = true; return; }
        if (ended) { ended = false; startAfterSeek = true; seek(0); return; }
        if (audio.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            ui.toast(I18n.s("focus_denied")); return;
        }
        try {
            applySpeed(); player.start(); observedPosition = position(); observedAt = SystemClock.elapsedRealtime();
            play.setText(I18n.s("pause"));
        } catch (RuntimeException e) { releasePlayer(); ui.toast(I18n.s("unavailable")); }
    }
    private void applySpeed() {
        try { player.setPlaybackParams(new PlaybackParams().setSpeed(speed).setPitch(1f)); }
        catch (RuntimeException e) {
            ui.toast(I18n.s("speed_unsupported")); speed = 1f;
            player.setPlaybackParams(new PlaybackParams().setSpeed(1f).setPitch(1f));
        }
    }
    void pause() {
        track(false); startAfterSeek = false;
        try { if (isPlaying()) player.pause(); } catch (RuntimeException ignored) { }
        checkpoint(null); observedPosition = -1;
        play.setText(I18n.s("play")); audio.abandonAudioFocus(focus);
    }
    private void seek(long millis) {
        if (!ready || player == null || deleting) return;
        track(false); observedPosition = -1; ended = false;
        pendingSeek = Math.max(0, Math.min(duration(), millis)); seekPending = true;
        try { player.seekTo(pendingSeek, MediaPlayer.SEEK_CLOSEST); wave.position(pendingSeek); }
        catch (RuntimeException ignored) { seekPending = false; startAfterSeek = false; }
    }
    private long duration() {
        try { return ready && player != null ? player.getDuration() : selected == null ? 0 : selected.duration; }
        catch (RuntimeException e) { return selected == null ? 0 : selected.duration; }
    }
    private long position() {
        if (ended) return 0;
        if (seekPending) return pendingSeek;
        try { return ready && player != null ? player.getCurrentPosition() : savedPosition; }
        catch (RuntimeException e) { return savedPosition; }
    }
    void tick() {
        if (deleting) return;
        updateAnalysis();
        if (selected == null) { play.setEnabled(false); return; }
        track(false);
        if (isPlaying() && SystemClock.elapsedRealtime() - lastSavedAt >= 10000) checkpoint(null);
        play.setEnabled(ready || player == null);
        long position = position(), duration = duration();
        clock.setText(Ui.clock(position) + " / " + Ui.clock(duration)); wave.position(position);
        if (!seeking) timeline.setProgress((int) (position * 10000L / Math.max(1, duration)));
        play.setText(isPlaying() ? I18n.s("pause") : I18n.s("play"));
    }
    private void loadWaveform(RecordingFiles.Item item) {
        analysisReady = false; waveState = null; needsAnalysis = false; cacheLoading = true;
        analysis.setText(I18n.s("wave_loading")); final long token = ++generation;
        worker.execute(() -> {
            WaveformAnalyzer.Data cached = null;
            try (RecordingFiles.Lease ignored = RecordingFiles.lease(item.file)) { cached = WaveformAnalyzer.cached(app, item.file); }
            catch (Exception ignored) { }
            WaveformAnalyzer.Data data = cached;
            activity.runOnUiThread(() -> {
                if (gone || token != generation) return;
                cacheLoading = false;
                if (data != null) displayWaveform(data);
                else {
                    needsAnalysis = WaveformService.state(item.id) == null;
                    updateAnalysis();
                }
            });
        });
    }
    private void updateAnalysis() {
        if (selected == null || analysisReady || cacheLoading || deleting) return;
        if (needsAnalysis && visible) { needsAnalysis = false; WaveformService.request(app, selected.id); }
        WaveformService.State state = WaveformService.state(selected.id);
        if (state == null) { analysis.setText(I18n.s("wave_start_hint")); return; }
        if (waveState == state) return;
        waveState = state;
        if (state.data != null) displayWaveform(state.data);
        else if (state.error != null) analysis.setText(I18n.s("analysis_failed") + " " + state.error + "\n" + I18n.s("wave_retry"));
        else analysis.setText(I18n.s("analyzing", state.percent));
    }
    private void displayWaveform(WaveformAnalyzer.Data data) {
        if (selected == null) return;
        wave.data(data, selected.duration, config.silenceDb()); wave.position(position());
        analysisReady = true; zoom.setText("1×"); thresholdChanged();
    }
    void thresholdChanged() {
        wave.threshold(config.silenceDb());
        if (analysisReady) analysis.setText(I18n.s("silence_summary", wave.quietPercent(), config.silenceDb()));
    }
    private void releasePlayer() {
        pause(); ready = false;
        if (player != null) { player.release(); player = null; }
        if (lease != null) { lease.close(); lease = null; }
        seekPending = false; observedPosition = -1;
    }
    void suspend() {
        visible = false; releasePlayer();
        if (cloudDeletion != null) cloudDeletion.cancel();
        if (batchWork != null) batchWork.cancel();
        if (deletionDialog != null) { deletionDialog.dismiss(); deletionDialog = null; }
    }
    void destroy() { suspend(); gone = true; generation++; activity.unregisterReceiver(noisy); }

    private static final class Row {
        final RecordingFiles.Item item;
        final LocalDate day;
        int count;
        long duration;
        Row(RecordingFiles.Item item) { this.item = item; this.day = null; }
        Row(LocalDate day) { this.item = null; this.day = day; }
    }
    private final class RecordsAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int position) { return rows.get(position).item == null ? 0 : 1; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int position) { return true; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout row; TextView name, detail;
            if (recycled instanceof LinearLayout) { row = (LinearLayout) recycled; name = (TextView) row.getChildAt(0); detail = (TextView) row.getChildAt(1); }
            else { row = ui.column(); row.setPadding(ui.dp(14), ui.dp(7), ui.dp(14), ui.dp(7)); name = new CheckedTextView(activity);name.setTextSize(14);name.setTextColor(ui.ink);row.addView(name,new LinearLayout.LayoutParams(-1,-2)); detail = ui.text(row, "", 12, ui.muted); }
            Row entry = rows.get(position);
            CheckedTextView check=(CheckedTextView)name;
            boolean checked=entry.item!=null && chosen.contains(entry.item.id);
            check.setCheckMarkDrawable(selecting && entry.item!=null
                    ? checked ? android.R.drawable.checkbox_on_background : android.R.drawable.checkbox_off_background : 0);
            check.setChecked(checked);
            if (entry.item == null) {
                LocalDate today = LocalDate.now();
                String title = entry.day.equals(today) ? I18n.s("today") : entry.day.equals(today.minusDays(1)) ? I18n.s("yesterday")
                        : entry.day.format(DateTimeFormatter.ofPattern("EEE dd.MM.yyyy", new Locale(config.language())));
                name.setText(title); name.setTypeface(null, android.graphics.Typeface.BOLD);
                detail.setText(I18n.s("day_recordings", entry.count, Ui.clock(entry.duration)));
                row.setBackgroundColor(ui.background); return row;
            }
            RecordingFiles.Item item = entry.item;
            row.setBackground(ui.shape((selecting ? chosen.contains(item.id) : selected != null && item.id.equals(selected.id)) ? ui.pale : ui.card, 12));
            name.setTypeface(null, android.graphics.Typeface.NORMAL); name.setText((item.important ? "★ " : "")+item.name);
            detail.setText(Ui.clock(item.duration) + " · " + String.format(Locale.ROOT, "%.1f MiB", item.bytes / 1048576d)
                    + " · " + I18n.s(item.cloudDeleteState == 1 ? "cloud_delete_pending"
                            : item.cloudDeleteState == 2 ? "cloud_deleted" : item.uploaded ? "uploaded" : "local") + "\n"
                    + I18n.s(item.listened ? "filter_listened" : "filter_unlistened")
                    + (item.position > 0 ? " · " + I18n.s("resume_at", Ui.clock(item.position)) : ""));
            return row;
        }
    }
}
