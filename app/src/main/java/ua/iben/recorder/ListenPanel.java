package ua.iben.recorder;

import android.app.AlertDialog;
import android.content.Context;
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
    private final TextView selectedLabel, clock, analysis, count, bookmarkLegend;
    private final Button play, zoom, mark, delete, important, addBookmark, bookmarks, export;
    private final LinearLayout playerCard;
    private final WaveformView wave;
    private final SeekBar timeline;
    private RecordingFiles.Item selected;
    private long listGeneration, generation, bookmarkGeneration, savedPosition, waveDuration;
    private boolean deleting, selecting, gone, seeking, loading, analysisReady, visible, cacheLoading, needsAnalysis;
    private final java.util.Set<String> chosen=new java.util.LinkedHashSet<>();
    private LinearLayout selectionBar, operationBar;
    private TextView selectionCount, operationText;
    private Button selectionToggle, batchActionButton;
    private int filter;
    private float speed;
    private WaveformService.State waveState;
    private String operationSeen="", playbackError="";
    private OperationsService.State lastOperation;

    ListenPanel(MainActivity activity, Ui ui, Config config) {
        this.activity = activity; this.app = activity.getApplicationContext(); this.ui = ui; this.config = config;
        speed = config.prefs.getFloat("playback_speed", 1f);
        filter = Math.max(0, Math.min(3, config.prefs.getInt("listen_filter", 0)));
        view = ui.column(); view.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), 0);
        LinearLayout header = ui.column(), card = ui.card(header);
        playerCard = card;
        selectedLabel = ui.text(card, I18n.s("choose_recording"), 15, ui.ink);
        clock = ui.text(card, "00:00:00 / 00:00:00", 22, ui.ink);
        wave = new WaveformView(ui, this::seek); card.addView(wave, new LinearLayout.LayoutParams(-1, ui.dp(120)));
        bookmarkLegend=ui.text(card,"",12,ui.bookmark);bookmarkLegend.setVisibility(View.GONE);
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
                speed = rates[p]; config.prefs.edit().putFloat("playback_speed", speed).apply();
                PlaybackService.speed(speed);
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
            if(selected!=null && !deleting && !selecting)Bookmarks.add(activity,ui,selected.id,position(),this::loadBookmarks);
        },false);ui.equal(bookmarkRow,addBookmark);
        bookmarks=ui.button(null,I18n.s("bookmarks"),() -> {
            if(selected==null || deleting || selecting)return;
            RecordingFiles.Item item=selected;
            Bookmarks.show(activity,ui,item.id,millis -> {
                if(gone || deleting || selecting)return;
                if(selected==null || !item.id.equals(selected.id))open(item,false);
                seek(millis);
            },this::loadBookmarks);
        },false);ui.equal(bookmarkRow,bookmarks);
        export = ui.button(card, I18n.s("export_recording"), () -> {
            if (selected == null) { ui.toast(I18n.s("choose_recording")); return; }
            if (!deleting && !selecting) activity.exportRecording(selected);
        }, false);
        delete = ui.button(card, I18n.s("delete_recording"), () -> { if (selected != null) chooseDeletion(selected); }, false);
        delete.setTextColor(ui.red); delete.setEnabled(false);
        analysis = ui.text(card, I18n.s("wave_hint"), 12, ui.muted);
        analysis.setOnClickListener(v -> {
            if (selected != null && selected.local && !analysisReady && visible && !deleting) {
                WaveformService.request(app, selected.id); waveState = null; needsAnalysis = false; updateAnalysis();
            }
        });
        operationBar=ui.row();header.addView(operationBar);
        operationText=ui.text(null,"",13,ui.muted);ui.equal(operationBar,operationText);
        ui.button(operationBar,I18n.s("cancel"),OperationsService::cancelCurrent,false);
        operationBar.setVisibility(View.GONE);
        operationSeen=config.prefs.getString("operation_seen","");
        LinearLayout listHeader = ui.row(); header.addView(listHeader);
        count = ui.text(null, I18n.s("recordings"), 16, ui.ink); ui.equal(listHeader, count);
        ui.equal(listHeader, ui.button(null, I18n.s("refresh"), this::refresh, false));
        selectionToggle=ui.button(header,I18n.s("batch_select"),() -> {
            if(deleting)return;chosen.clear();setSelecting(!selecting);adapter.notifyDataSetChanged();
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
        batchActionButton=ui.button(selectionBar,I18n.s("batch_actions"),this::batchActions,true);
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
            if(row.item==null) { setSelecting(true);chooseDay(row.day);return true; }
            if(selecting) {
                if(!chosen.remove(row.item.id))chosen.add(row.item.id);
                updateSelection();adapter.notifyDataSetChanged();return true;
            }
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
                else if(which==3) { chosen.add(item.id);setSelecting(true);adapter.notifyDataSetChanged(); }
                else confirmUploadAgain(item);
            }).show();
            return true;
        });
        view.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        ui.text(view, I18n.s("listen_hint"), 11, ui.muted);

    }

    void load() {
        if (loading || gone || deleting) return;
        if (!Platform.storageGranted(activity)) { activity.storagePermission(this::load); return; }
        changedWhileLoading.clear(); loading = true; count.setText(I18n.s("loading"));
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
                        boolean locationChanged=selected.local!=item.local;selected = item; applySnapshot(item); savedPosition=item.position; selectedLabel.setText(item.name+"\n"+location(item)); if(locationChanged)loadWaveform(item); found = true; break;
                    }
                    if (!found) clearSelection();
                }
                rebuildRows();
                if (selected == null && visible && !selecting) {
                    PlaybackService.State active=PlaybackService.state();
                    String last = active!=null && active.id!=null ? active.id : config.prefs.getString("last_recording", "");
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
        if(deleting || gone || selecting)return;
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
        playerCard.setVisibility(selecting ? View.GONE : View.VISIBLE);
        selectionBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        selectionToggle.setText(I18n.s(selecting ? "batch_finish" : "batch_select"));
        List<RecordingFiles.Item> list=selectedItems();long bytes=0;
        for(RecordingFiles.Item item:list)bytes+=item.bytes;
        selectionCount.setText(I18n.s("batch_count",list.size(),bytes/1048576d));
        batchActionButton.setEnabled(selecting && !deleting && !list.isEmpty());
        updateMark();
    }
    private void setSelecting(boolean value) {
        selecting=value;updateSelection();
        if(!value && visible && !deleting && selected!=null)open(selected,false);
    }
    boolean finishSelection() {
        if(!selecting || deleting)return false;
        chosen.clear();setSelecting(false);adapter.notifyDataSetChanged();return true;
    }
    private void batchActions() {
        if(deleting || gone || !selecting)return;
        List<RecordingFiles.Item> list=selectedItems();
        if(list.isEmpty()) {ui.toast(I18n.s("batch_choose"));return;}
        String[] labels={I18n.s("mark_listened"),I18n.s("mark_unlistened"),I18n.s("important_add"),
                I18n.s("important_remove"),I18n.s("batch_export"),I18n.s("delete_recording")};
        new AlertDialog.Builder(activity).setTitle(I18n.s("batch_actions")).setItems(labels,(d,w) -> {
            if(w==4) {activity.exportRecordings(list);return;}
            if(w==5) {
                new AlertDialog.Builder(activity).setTitle(I18n.s("delete_recording"))
                        .setItems(new String[]{I18n.s("delete_local"),I18n.s("delete_cloud"),I18n.s("delete_both")},(dialog,mode) -> {
                            BatchWork.Action action=mode==0 ? BatchWork.Action.DELETE_LOCAL : mode==1 ? BatchWork.Action.DELETE_CLOUD : BatchWork.Action.DELETE_BOTH;
                            long bytes=0;int protectedCount=0,localOnly=0;boolean busy=false;
                            for(RecordingFiles.Item item:list) {bytes+=item.bytes;if(item.important)protectedCount++;
                                if(!item.important && item.local && !item.hasCloud)localOnly++;
                                if(!item.important && (RecordingFiles.busy(item) || OperationsService.exporting(item.id)))busy=true;}
                            String message=I18n.s("batch_delete_confirm",list.size(),bytes/1048576d,protectedCount)
                                    +"\n\n"+I18n.s(mode==0 ? "delete_local" : mode==1 ? "delete_cloud" : "delete_both");
                            if(localOnly>0)message+="\n\n"+I18n.s(mode==0?"delete_unverified_hint":"batch_local_only_warning",localOnly);
                            if(busy)message+="\n\n"+I18n.s("processing_delete_warning");
                            if(list.stream().anyMatch(item->OperationsService.exporting(item.id)))message+="\n\n"+I18n.s("delete_stops_export");
                            boolean fallback=mode!=0 && localOnly>0;
                            new AlertDialog.Builder(activity).setTitle(I18n.s("delete_recording")).setMessage(message)
                                    .setNegativeButton(I18n.s("cancel"),null)
                                    .setPositiveButton(I18n.s("delete_action"),(confirm,which) -> startBatch(list,action,null,0,false,fallback)).show();
                        }).show();return;
            }
            BatchWork.Action[] actions={BatchWork.Action.LISTENED,BatchWork.Action.UNLISTENED,BatchWork.Action.PIN,BatchWork.Action.UNPIN};
            if(w==3) new AlertDialog.Builder(activity).setTitle(I18n.s("important_remove"))
                    .setMessage(I18n.s("batch_unpin_confirm",list.size())).setNegativeButton(I18n.s("cancel"),null)
                    .setPositiveButton(I18n.s("important_remove"),(dialog,which) -> runBatch(list,actions[w],null)).show();
            else runBatch(list,actions[w],null);
        }).show();
    }
    void runBatch(List<RecordingFiles.Item> list,BatchWork.Action action,android.net.Uri destination) {runBatch(list,action,destination,0);}
    void runBatch(List<RecordingFiles.Item> list,BatchWork.Action action,android.net.Uri destination,int unavailable) {
        startBatch(list,action,destination,unavailable,false,false);
    }
    private void startBatch(List<RecordingFiles.Item> list,BatchWork.Action action,android.net.Uri destination,int unavailable,boolean allowImportant,boolean fallback) {
        if(gone || list.isEmpty())return;
        if(!OperationsService.start(app,OperationsService.Request.batch(list,action,destination,allowImportant,fallback,unavailable))) {
            ui.toast(I18n.s("operation_busy"));return;
        }
        chosen.clear();selecting=false;updateOperations();updateSelection();
    }
    private void updateOperations() {
        OperationsService.State state=OperationsService.state(app);
        deleting=state!=null && state.running && !state.catalog && !OperationsService.exporting(null);
        operationBar.setVisibility(state!=null && state.running?View.VISIBLE:View.GONE);
        if(state!=lastOperation){lastOperation=state;
            if(state!=null && state.running)operationText.setText(state.title+(state.total>0?" · "+state.current+"/"+state.total:"")+"\n"+state.detail);
            if(state!=null && !state.running && !state.token.equals(operationSeen)){
                operationSeen=state.token;config.prefs.edit().putString("operation_seen",operationSeen).apply();
                OperationsService.acknowledge(app);ui.toast(state.detail);load();
            }
            updateSelection();updateMark();
        }
    }
    private void refresh() {
        load();CloudSettings cloud=new CloudSettings(app);
        if(!cloud.targetKey().isEmpty()) {
            if(!OperationsService.start(app,OperationsService.Request.catalog()))ui.toast(I18n.s("operation_busy"));
            updateOperations();
        }
    }
    private void updateMark() {
        boolean enabled=selected!=null && !deleting && !selecting;
        important.setEnabled(enabled);
        important.setText(I18n.s(selected!=null && selected.important ? "important_remove" : "important_add"));
        addBookmark.setEnabled(enabled);bookmarks.setEnabled(enabled);export.setEnabled(enabled);
        mark.setEnabled(enabled);
        delete.setEnabled(enabled);
        mark.setText(I18n.s(selected != null && selected.listened ? "mark_unlistened" : "mark_listened"));
    }
    private void mark(RecordingFiles.Item item, boolean heard) {
        if(deleting || selecting)return;
        applySnapshot(item);item.listened=heard;PlaybackService.marked(item.id,heard);
        if(selected!=null && selected.id.equals(item.id))selected.listened=heard;
        if(loading)changedWhileLoading.put(item.id,item);
        worker.execute(()->{try(RecordingFiles files=new RecordingFiles(app,config)){files.listened(item,heard);}catch(Exception e){AppLog.write(app,"Mark listened: "+e.getClass().getSimpleName());}});
        rebuildRows();
    }
    private void chooseDeletion(RecordingFiles.Item item) {
        if (deleting || gone || selecting) return;
        new AlertDialog.Builder(activity).setTitle(I18n.s("delete_recording"))
                .setItems(new String[]{I18n.s("delete_local"), I18n.s("delete_cloud"), I18n.s("delete_both")},
                        (dialog, which) -> confirmDelete(item, which)).show();
    }
    private void confirmDelete(RecordingFiles.Item item, int mode) {
        if(deleting || gone)return;
        if(mode==0 && !item.local){ui.toast(I18n.s("cloud_only_no_local"));return;}
        boolean fallback=mode!=0 && item.local && !item.hasCloud;
        String message=item.name+"\n\n"+(fallback?I18n.s("local_only_warning"):
                I18n.s(mode==0?"delete_local_confirm":mode==1?"delete_cloud_confirm":"delete_both_confirm"));
        if(mode==0 && !item.hasCloud)message+="\n\n"+I18n.s("delete_unverified_hint");
        if(item.important)message=I18n.s("important_delete_warning")+"\n\n"+message;
        if(RecordingFiles.busy(item) || OperationsService.exporting(item.id))message+="\n\n"+I18n.s("processing_delete_warning");
        if(OperationsService.exporting(item.id))message+="\n\n"+I18n.s("delete_stops_export");
        BatchWork.Action action=mode==0?BatchWork.Action.DELETE_LOCAL:mode==1?BatchWork.Action.DELETE_CLOUD:BatchWork.Action.DELETE_BOTH;
        new AlertDialog.Builder(activity).setTitle(I18n.s("delete_recording"))
                .setMessage(message).setNegativeButton(I18n.s("cancel"),null)
                .setPositiveButton(I18n.s("delete_action"),(dialog,which)->startBatch(java.util.Collections.singletonList(item),action,null,0,item.important,fallback)).show();
    }
    private void confirmUploadAgain(RecordingFiles.Item item) {
        if (deleting || gone) return;
        new AlertDialog.Builder(activity).setTitle(I18n.s("cloud_upload_again"))
                .setMessage(I18n.s("cloud_upload_again_confirm", item.name))
                .setNegativeButton(I18n.s("cancel"), null)
                .setPositiveButton(I18n.s("cloud_upload_again"), (dialog, which) -> {
                    deleting = true; listGeneration++; loading = false; changedWhileLoading.clear();
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
        generation++;
        bookmarkGeneration++; wave.bookmarks(new long[0]);bookmarkLegend.setVisibility(View.GONE);
        selected = null; savedPosition = 0;
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
    private PlaybackService.State playerState() {
        PlaybackService.State value=PlaybackService.state();return selected!=null && value!=null && selected.id.equals(value.id)?value:null;
    }
    private void applySnapshot(RecordingFiles.Item item) {
        PlaybackService.State value=PlaybackService.state();
        if(value!=null && item.id.equals(value.id) && value.error==null){item.position=value.position();item.heardRanges=value.ranges;item.listened=value.listened;}
    }
    private static String location(RecordingFiles.Item item){return I18n.s(item.local?item.hasCloud?"location_both":"location_device":"location_cloud");}
    private void open(RecordingFiles.Item item, boolean autoplay) {
        if(deleting || selecting)return;
        applySnapshot(item);selected=item;savedPosition=PlaybackProgress.resume(item.position,item.duration);
        config.prefs.edit().putString("last_recording",item.id).apply();
        selectedLabel.setText(item.name+"\n"+location(item));waveDuration=duration();wave.data(null,waveDuration,config.silenceDb());zoom.setText("1×");
        wave.bookmarks(new long[0]);bookmarkLegend.setVisibility(View.GONE);loadBookmarks();
        if(autoplay){playbackError="";PlaybackService.play(app,item.id);}
        loadWaveform(item);adapter.notifyDataSetChanged();updateMark();tick();
    }
    private boolean isPlaying(){PlaybackService.State state=playerState();return state!=null && state.playing;}
    private void zoomPressed(){zoom.setText(wave.zoom()+"×");}
    void visible(boolean value){visible=value;if(value){updateOperations();}}
    private void toggle(){if(deleting || selecting || selected==null)return;if(isPlaying())pause();else{playbackError="";PlaybackService.play(app,selected.id);}}
    void pause(){PlaybackService.pauseCurrent();play.setText(I18n.s("play"));}
    private void seek(long millis){
        if(selected==null || deleting)return;
        long position=Math.max(0,Math.min(Math.max(0,duration()-1),millis));
        if(playerState()!=null)PlaybackService.seek(selected.id,position);
        else{savedPosition=position;selected.position=position;persist(selected.id,position,selected.heardRanges,null);}
        wave.position(position);
    }
    private long duration(){PlaybackService.State state=playerState();return state!=null && state.duration>0?state.duration:selected==null?0:selected.duration;}
    private long position(){PlaybackService.State state=playerState();return state!=null?state.position():savedPosition;}
    void tick(){
        if(gone)return;updateOperations();if(selecting)return;updateAnalysis();
        if(selected==null){play.setEnabled(false);return;}
        PlaybackService.State state=playerState();
        if(state!=null){boolean heard=selected.listened;applySnapshot(selected);if(heard!=selected.listened){adapter.notifyDataSetChanged();updateMark();}
            if(state.error!=null && !state.error.equals(playbackError)){playbackError=state.error;ui.toast(state.error);}}
        play.setEnabled(!deleting && (state==null || !state.preparing));
        long position=position(),duration=duration();if(!analysisReady && waveDuration!=duration){waveDuration=duration;wave.data(null,duration,config.silenceDb());}clock.setText(Ui.clock(position)+" / "+Ui.clock(duration));wave.position(position);
        if(!seeking)timeline.setProgress((int)(position*10000L/Math.max(1,duration)));
        play.setText(I18n.s(state!=null && state.preparing?"loading":isPlaying()?"pause":"play"));
    }
    private void loadWaveform(RecordingFiles.Item item) {
        analysisReady = false; waveState = null; needsAnalysis = false; cacheLoading = true;
        if(!item.local){generation++;cacheLoading=false;analysis.setText(I18n.s("cloud_wave_hint"));return;}
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
    private void loadBookmarks() {
        if(gone || selected==null)return;
        String id=selected.id;long request=++bookmarkGeneration;
        worker.execute(() -> {
            long[] positions;
            synchronized(RecordingFiles.LOCK) {
                try(RecordIndex index=new RecordIndex(app)) {
                    List<RecordIndex.Bookmark> marks=index.bookmarks(id);positions=new long[marks.size()];
                    for(int i=0;i<positions.length;i++)positions[i]=marks.get(i).position;
                } catch(RuntimeException e) { positions=new long[0]; }
            }
            long[] result=positions;
            activity.runOnUiThread(() -> {
                if(!gone && request==bookmarkGeneration && selected!=null && id.equals(selected.id)) {
                    wave.bookmarks(result);bookmarkLegend.setVisibility(result.length==0 ? View.GONE : View.VISIBLE);
                    bookmarkLegend.setText(I18n.s("wave_bookmarks",result.length));
                }
            });
        });
    }
    private void updateAnalysis() {
        if (selected == null || !selected.local || analysisReady || cacheLoading || deleting || selecting) return;
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
    // Hiding or destroying an Activity detaches the UI only. The services own their work.
    void suspend(){visible=false;}
    void destroy(){suspend();gone=true;generation++;bookmarkGeneration++;}

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
                    + " · " + location(item) + "\n"
                    + I18n.s(item.listened ? "filter_listened" : "filter_unlistened")
                    + (item.position > 0 ? " · " + I18n.s("resume_at", Ui.clock(item.position)) : ""));
            return row;
        }
    }
}
