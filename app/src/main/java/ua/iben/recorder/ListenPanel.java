package ua.iben.recorder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Process;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class ListenPanel {
    final LinearLayout view;
    private final MainActivity activity;
    private final Ui ui;
    private final Config config;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(() -> {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run();
    }, "iben-waveforms"));
    private final List<RecordingFiles.Item> items = new ArrayList<>();
    private final RecordsAdapter adapter = new RecordsAdapter();
    private final TextView selectedLabel, clock, analysis, count;
    private final Button play, zoom;
    private final WaveformView wave;
    private final SeekBar timeline;
    private MediaPlayer player;
    private RecordingFiles.Item selected;
    private RecordingFiles.Lease lease;
    private Future<?> analysisTask;
    private long generation;
    private long savedPosition;
    private boolean ready, gone, seeking, loading, analysisReady, visible;
    private float speed;
    private final AudioManager audio;
    private final AudioManager.OnAudioFocusChangeListener focus = change -> { if (change <= 0) pause(); };
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) { pause(); }
    };
    ListenPanel(MainActivity activity, Ui ui, Config config) {
        this.activity = activity; this.ui = ui; this.config = config;
        audio = activity.getSystemService(AudioManager.class);
        speed = config.prefs.getFloat("playback_speed", 1f);
        view = ui.column(); view.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), 0);
        LinearLayout header = ui.column();
        LinearLayout card = ui.card(header);
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
        TextView speedLabel = ui.text(null, I18n.s("speed"), 13, ui.muted); ui.equal(options, speedLabel);
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
                if (isPlaying()) applySpeed();
            }
            @Override public void onNothingSelected(AdapterView<?> a) { }
        });
        zoom = ui.button(null, "1×", this::zoomPressed, false); ui.equal(options, zoom);
        zoom.setContentDescription(I18n.s("zoom"));
        ui.button(card, I18n.s("export_recording"), () -> {
            if (selected == null) { ui.toast(I18n.s("choose_recording")); return; }
            activity.exportRecording(selected);
        }, false);
        analysis = ui.text(card, I18n.s("wave_hint"), 12, ui.muted);
        LinearLayout listHeader = ui.row(); header.addView(listHeader);
        count = ui.text(null, I18n.s("recordings"), 16, ui.ink); ui.equal(listHeader, count);
        Button refresh = ui.button(null, I18n.s("refresh"), this::load, false); ui.equal(listHeader, refresh);
        ListView list = new ListView(activity); list.addHeaderView(header, null, false); list.setAdapter(adapter); list.setDividerHeight(ui.dp(6));
        list.setOnItemClickListener((a, v, p, id) -> {
            int index = p - list.getHeaderViewsCount();
            if (index >= 0 && index < items.size()) { open(items.get(index), true); list.smoothScrollToPosition(0); }
        });
        view.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        ui.text(view, I18n.s("listen_hint"), 11, ui.muted);
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (android.os.Build.VERSION.SDK_INT >= 33) activity.registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED);
        else activity.registerReceiver(noisy, filter);
    }
    void load() {
        if (loading || gone) return;
        if (!Platform.storageGranted(activity)) {
            activity.storagePermission(this::load); return;
        }
        loading = true; count.setText(I18n.s("loading"));
        // A separate short operation is not queued behind a long waveform decode.
        new Thread(() -> {
            List<RecordingFiles.Item> list = null; String error = null;
            try (RecordingFiles files = new RecordingFiles(activity.getApplicationContext(), config)) { list = files.recordings(); }
            catch (Exception e) { error = e.getMessage(); }
            final List<RecordingFiles.Item> result = list; final String problem = error;
            activity.runOnUiThread(() -> {
                loading = false; if (gone) return;
                if (result == null) { count.setText(I18n.tr(problem == null ? "Немає доступу до папки записів" : problem)); return; }
                items.clear(); items.addAll(result); adapter.notifyDataSetChanged();
                count.setText(items.isEmpty() ? I18n.s("no_recordings") : I18n.s("record_count", items.size()));
            });
        }, "iben-record-list").start();
    }
    private void open(RecordingFiles.Item item, boolean autoplay) {
        long resumeAt = selected != null && selected.id.equals(item.id) ? position() : 0;
        cancelAnalysis(); releasePlayer(); selected = item; analysisReady = false;
        savedPosition = resumeAt;
        selectedLabel.setText(item.name); wave.data(null, item.duration, config.silenceDb()); zoom.setText("1×");
        analysis.setText(I18n.s("analyzing", 0)); clock.setText("00:00:00 / " + Ui.clock(item.duration));
        try {
            lease = RecordingFiles.lease(item.file);
            MediaPlayer p = new MediaPlayer(); player = p;
            p.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            p.setDataSource(item.file.getAbsolutePath());
            p.setOnPreparedListener(mp -> {
                if (player != mp) return;
                ready = true;
                if (resumeAt > 0 && resumeAt < mp.getDuration()) mp.seekTo(resumeAt, MediaPlayer.SEEK_CLOSEST);
                if (autoplay && visible) resume(); tick();
            });
            p.setOnCompletionListener(mp -> {
                if (player != mp) return;
                savedPosition = 0; play.setText(I18n.s("play")); audio.abandonAudioFocus(focus);
            });
            p.setOnErrorListener((mp, what, extra) -> {
                if (player == mp) { releasePlayer(); ui.toast(I18n.s("play_failed", what, extra)); } return true;
            });
            p.prepareAsync();
        } catch (Exception e) { releasePlayer(); ui.toast(e.getMessage() == null ? I18n.s("unavailable") : e.getMessage()); }
        final long token = ++generation;
        analysisTask = worker.submit(() -> {
            try (RecordingFiles.Lease ignored = RecordingFiles.lease(item.file)) {
                WaveformAnalyzer.Data data = WaveformAnalyzer.read(activity.getApplicationContext(), item.file, item.duration, percent ->
                    activity.runOnUiThread(() -> { if (!gone && token == generation) analysis.setText(I18n.s("analyzing", percent)); }));
                activity.runOnUiThread(() -> {
                    if (gone || token != generation) return;
                    wave.data(data, item.duration, config.silenceDb()); wave.position(position()); analysisReady = true;
                    zoom.setText("1×");
                    analysis.setText(I18n.s("silence_summary", wave.quietPercent(), config.silenceDb()));
                });
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) return;
                activity.runOnUiThread(() -> { if (!gone && token == generation) analysis.setText(I18n.s("analysis_failed") + " " + I18n.tr(e.getMessage())); });
            }
        });
        adapter.notifyDataSetChanged();
    }
    private boolean isPlaying() { try { return ready && player != null && player.isPlaying(); } catch (RuntimeException e) { return false; } }
    private void zoomPressed() { zoom.setText(wave.zoom() + "×"); }
    void visible(boolean value) { visible = value; if (!value) pause(); }
    private void toggle() { if (isPlaying()) pause(); else if (ready) resume(); else if (selected != null && player == null) open(selected, true); }
    private void resume() {
        if (!ready || player == null || !visible) return;
        if (audio.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            ui.toast(I18n.s("focus_denied")); return;
        }
        try { applySpeed(); player.start(); play.setText(I18n.s("pause")); }
        catch (RuntimeException e) { releasePlayer(); ui.toast(I18n.s("unavailable")); }
    }
    private void applySpeed() {
        try { player.setPlaybackParams(new PlaybackParams().setSpeed(speed).setPitch(1f)); }
        catch (RuntimeException e) { ui.toast(I18n.s("speed_unsupported")); player.setPlaybackParams(new PlaybackParams().setSpeed(1f).setPitch(1f)); }
    }
    void pause() {
        try { if (isPlaying()) player.pause(); } catch (RuntimeException e) { releasePlayer(); }
        play.setText(I18n.s("play")); audio.abandonAudioFocus(focus);
    }
    private void seek(long millis) {
        if (!ready || player == null) return;
        try { player.seekTo(Math.max(0, Math.min(duration(), millis)), MediaPlayer.SEEK_CLOSEST); wave.position(millis); }
        catch (RuntimeException ignored) { }
    }
    private long duration() { try { return ready && player != null ? player.getDuration() : selected == null ? 0 : selected.duration; } catch (RuntimeException e) { return 0; } }
    private long position() { try { return ready && player != null ? player.getCurrentPosition() : savedPosition; } catch (RuntimeException e) { return savedPosition; } }
    void tick() {
        if (selected == null) { play.setEnabled(false); return; }
        play.setEnabled(ready || player == null);
        long position = position(), duration = duration();
        clock.setText(Ui.clock(position) + " / " + Ui.clock(duration)); wave.position(position);
        if (!seeking) timeline.setProgress((int) (position * 10000L / Math.max(1, duration)));
        play.setText(isPlaying() ? I18n.s("pause") : I18n.s("play"));
    }
    void thresholdChanged() {
        wave.threshold(config.silenceDb());
        if (analysisReady) analysis.setText(I18n.s("silence_summary", wave.quietPercent(), config.silenceDb()));
    }
    private void cancelAnalysis() { generation++; if (analysisTask != null) analysisTask.cancel(true); analysisTask = null; }
    private void releasePlayer() {
        if (ready && player != null) savedPosition = position();
        ready = false;
        if (player != null) { player.release(); player = null; }
        if (lease != null) { lease.close(); lease = null; }
        audio.abandonAudioFocus(focus);
    }
    void suspend() { visible = false; cancelAnalysis(); releasePlayer(); }
    void destroy() { gone = true; suspend(); worker.shutdownNow(); activity.unregisterReceiver(noisy); }
    private final class RecordsAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout row; TextView name, detail;
            if (recycled instanceof LinearLayout) { row = (LinearLayout) recycled; name = (TextView) row.getChildAt(0); detail = (TextView) row.getChildAt(1); }
            else { row = ui.column(); row.setPadding(ui.dp(14), ui.dp(7), ui.dp(14), ui.dp(7)); name = ui.text(row, "", 14, ui.ink); detail = ui.text(row, "", 12, ui.muted); }
            RecordingFiles.Item item = items.get(position);
            row.setBackground(ui.shape(selected != null && item.id.equals(selected.id) ? ui.pale : ui.card, 12));
            name.setText(item.name);
            detail.setText(Ui.clock(item.duration) + " · " + String.format(Locale.ROOT, "%.1f MiB", item.bytes / 1048576d)
                    + " · " + I18n.s(item.uploaded ? "uploaded" : "local"));
            return row;
        }
    }
}
