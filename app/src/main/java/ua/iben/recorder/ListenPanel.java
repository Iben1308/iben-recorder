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
    private static final ExecutorService worker = new ThreadPoolExecutor(0, 1, 30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), r -> new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run();
            }, "iben-listen-state"));
    private final List<RecordingFiles.Item> items = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    // Preserve edits made while an asynchronous list query is in flight.
    private final Map<String, RecordingFiles.Item> changedWhileLoading = new HashMap<>();
    private final RecordsAdapter adapter = new RecordsAdapter();
    private final TextView selectedLabel, clock, analysis, count;
    private final Button play, zoom, mark;
    private final WaveformView wave;
    private final SeekBar timeline;
    private MediaPlayer player;
    private RecordingFiles.Item selected;
    private RecordingFiles.Lease lease;
    private PlaybackProgress progress;
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
        filter = Math.max(0, Math.min(2, config.prefs.getInt("listen_filter", 0)));
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
        ui.button(card, I18n.s("export_recording"), () -> {
            if (selected == null) { ui.toast(I18n.s("choose_recording")); return; }
            activity.exportRecording(selected);
        }, false);
        analysis = ui.text(card, I18n.s("wave_hint"), 12, ui.muted);
        analysis.setOnClickListener(v -> {
            if (selected != null && !analysisReady && visible) {
                WaveformService.request(app, selected.id); waveState = null; needsAnalysis = false; updateAnalysis();
            }
        });
        LinearLayout listHeader = ui.row(); header.addView(listHeader);
        count = ui.text(null, I18n.s("recordings"), 16, ui.ink); ui.equal(listHeader, count);
        ui.equal(listHeader, ui.button(null, I18n.s("refresh"), this::load, false));
        Spinner filters = ui.spinner(header, new String[]{I18n.s("filter_all"), I18n.s("filter_unlistened"), I18n.s("filter_listened")}, filter);
        filters.setContentDescription(I18n.s("record_filter"));
        filters.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> a, View v, int p, long id) {
                filter = p; config.prefs.edit().putInt("listen_filter", p).apply(); rebuildRows();
            }
            @Override public void onNothingSelected(AdapterView<?> a) { }
        });
        ListView list = new ListView(activity); list.addHeaderView(header, null, false); list.setAdapter(adapter); list.setDividerHeight(ui.dp(6));
        list.setOnItemClickListener((a, v, p, id) -> {
            int index = p - list.getHeaderViewsCount();
            if (index >= 0 && index < rows.size() && rows.get(index).item != null) {
                open(rows.get(index).item, true); list.smoothScrollToPosition(0);
            }
        });
        list.setOnItemLongClickListener((a, v, p, id) -> {
            int index = p - list.getHeaderViewsCount();
            if (index < 0 || index >= rows.size() || rows.get(index).item == null) return false;
            RecordingFiles.Item item = rows.get(index).item;
            new AlertDialog.Builder(activity).setTitle(item.name)
                    .setItems(new String[]{I18n.s(item.listened ? "mark_unlistened" : "mark_listened")}, (dialog, which) -> mark(item, !item.listened)).show();
            return true;
        });
        view.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        ui.text(view, I18n.s("listen_hint"), 11, ui.muted);
        IntentFilter noisyFilter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (android.os.Build.VERSION.SDK_INT >= 33) activity.registerReceiver(noisy, noisyFilter, Context.RECEIVER_NOT_EXPORTED);
        else activity.registerReceiver(noisy, noisyFilter);
    }

    void load() {
        if (loading || gone) return;
        if (!Platform.storageGranted(activity)) { activity.storagePermission(this::load); return; }
        checkpoint(null); changedWhileLoading.clear(); loading = true; count.setText(I18n.s("loading"));
        worker.execute(() -> {
            List<RecordingFiles.Item> list = null; String error = null;
            try (RecordingFiles files = new RecordingFiles(app, config)) { list = files.recordings(); }
            catch (Exception e) { error = e.getMessage(); }
            final List<RecordingFiles.Item> result = list; final String problem = error;
            activity.runOnUiThread(() -> {
                loading = false; if (gone) return;
                if (result != null) for (RecordingFiles.Item item : result) {
                    RecordingFiles.Item edited = changedWhileLoading.get(item.id);
                    if (edited != null) copyState(edited, item);
                }
                changedWhileLoading.clear();
                if (result == null) { count.setText(I18n.tr(problem == null ? "Немає доступу до папки записів" : problem)); return; }
                items.clear(); items.addAll(result);
                if (selected != null) for (RecordingFiles.Item item : items) if (item.id.equals(selected.id)) copyState(selected, item);
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
    private void rebuildRows() {
        rows.clear(); Row group = null; int matches = 0;
        for (RecordingFiles.Item item : items) {
            if (filter == 1 && item.listened || filter == 2 && !item.listened) continue;
            LocalDate day = Instant.ofEpochMilli(item.start).atZone(ZoneId.systemDefault()).toLocalDate();
            if (group == null || !group.day.equals(day)) { group = new Row(day); rows.add(group); }
            group.count++; group.duration += item.duration; rows.add(new Row(item)); matches++;
        }
        count.setText(items.isEmpty() ? I18n.s("no_recordings") : matches == 0 ? I18n.s("no_filter_matches") : I18n.s("record_count", matches));
        adapter.notifyDataSetChanged(); updateMark();
    }
    private void updateMark() {
        mark.setEnabled(selected != null);
        mark.setText(I18n.s(selected != null && selected.listened ? "mark_unlistened" : "mark_listened"));
    }
    private void mark(RecordingFiles.Item item, boolean heard) {
        if (selected != null && selected.id.equals(item.id)) checkpoint(heard);
        else {
            item.listened = heard;
            if (loading) changedWhileLoading.put(item.id, item);
            persist(item.id, item.position, item.heardRanges, heard);
        }
        rebuildRows();
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
    private void toggle() { if (isPlaying()) pause(); else if (ready) resume(); else if (selected != null && player == null) open(selected, true); }
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
        if (!ready || player == null || !visible) return;
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
        if (!ready || player == null) return;
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
        if (selected == null || analysisReady || cacheLoading) return;
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
    void suspend() { visible = false; releasePlayer(); }
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
        @Override public boolean isEnabled(int position) { return rows.get(position).item != null; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout row; TextView name, detail;
            if (recycled instanceof LinearLayout) { row = (LinearLayout) recycled; name = (TextView) row.getChildAt(0); detail = (TextView) row.getChildAt(1); }
            else { row = ui.column(); row.setPadding(ui.dp(14), ui.dp(7), ui.dp(14), ui.dp(7)); name = ui.text(row, "", 14, ui.ink); detail = ui.text(row, "", 12, ui.muted); }
            Row entry = rows.get(position);
            if (entry.item == null) {
                LocalDate today = LocalDate.now();
                String title = entry.day.equals(today) ? I18n.s("today") : entry.day.equals(today.minusDays(1)) ? I18n.s("yesterday")
                        : entry.day.format(DateTimeFormatter.ofPattern("EEE dd.MM.yyyy", new Locale(config.language())));
                name.setText(title); name.setTypeface(null, android.graphics.Typeface.BOLD);
                detail.setText(I18n.s("day_recordings", entry.count, Ui.clock(entry.duration)));
                row.setBackgroundColor(ui.background); return row;
            }
            RecordingFiles.Item item = entry.item;
            row.setBackground(ui.shape(selected != null && item.id.equals(selected.id) ? ui.pale : ui.card, 12));
            name.setTypeface(null, android.graphics.Typeface.NORMAL); name.setText(item.name);
            detail.setText(Ui.clock(item.duration) + " · " + String.format(Locale.ROOT, "%.1f MiB", item.bytes / 1048576d)
                    + " · " + I18n.s(item.uploaded ? "uploaded" : "local") + "\n"
                    + I18n.s(item.listened ? "filter_listened" : "filter_unlistened")
                    + (item.position > 0 ? " · " + I18n.s("resume_at", Ui.clock(item.position)) : ""));
            return row;
        }
    }
}
