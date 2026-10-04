package ua.iben.recorder;

import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class SettingsPanel {
    final LinearLayout view;
    private final ScrollView[] sections = new ScrollView[4];
    private final Button[] sectionButtons = new Button[4];
    private final Button scheduleToggle;
    private int currentSection;
    private final MainActivity activity;
    private final Ui ui;
    private final Config config;
    private final EditText minutes, quota;
    private final Spinner bitrate, rate, source, device;
    private final Switch cleanup, boot;
    private final Switch[] days = new Switch[7];
    private final int[] from = new int[7], to = new int[7];
    private final Button[] fromButtons = new Button[7], toButtons = new Button[7];
    private final TextView scheduleStatus, cloudStatus, bluetoothFormat;
    private final List<View> recordingControls = new ArrayList<>();
    private final List<Integer> sourceIds = new ArrayList<>();
    private List<AudioInputs.Choice> inputs;
    SettingsPanel(MainActivity activity, Ui ui, Config config, Bundle draft) {
        this.activity = activity; this.ui = ui; this.config = config;
        view = ui.column();
        LinearLayout navigation = ui.row(); navigation.setPadding(ui.dp(8), 0, ui.dp(8), 0); view.addView(navigation);
        FrameLayout content = new FrameLayout(activity); view.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout[] pages = new LinearLayout[4];
        String[] labels = {I18n.s("general"), I18n.s("sound"), I18n.s("schedule_tab"), I18n.s("webdav_title")};
        for (int i = 0; i < sections.length; i++) {
            final int index = i;
            sectionButtons[i] = ui.button(null, labels[i], () -> showSection(index), false);
            sectionButtons[i].setTextSize(12); sectionButtons[i].setMinWidth(0); sectionButtons[i].setMinimumWidth(0);
            sectionButtons[i].setPadding(ui.dp(4), 0, ui.dp(4), 0);
            navigation.addView(sectionButtons[i], new LinearLayout.LayoutParams(0, ui.dp(52), 1));
            sections[i] = new ScrollView(activity); pages[i] = ui.column();
            pages[i].setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12)); sections[i].addView(pages[i]);
            content.addView(sections[i], new FrameLayout.LayoutParams(-1, -1));
        }
        LinearLayout look = ui.card(pages[0]); ui.title(look, I18n.s("appearance"));
        ui.text(look, I18n.s("language"), 13, ui.muted);
        String[] codes = {"uk", "en", "pl"}; int languageIndex = 0;
        for (int i = 0; i < codes.length; i++) if (codes[i].equals(config.language())) languageIndex = i;
        Spinner language = ui.spinner(look, new String[]{"Українська", "English", "Polski"}, languageIndex);
        language.setOnItemSelectedListener(selection(p -> {
            if (!codes[p].equals(config.language())) {
                config.prefs.edit().putString("language", codes[p]).commit(); I18n.use(codes[p]); activity.recreate();
            }
        }));
        ui.text(look, I18n.s("theme"), 13, ui.muted);
        Spinner theme = ui.spinner(look, new String[]{I18n.s("system"), I18n.s("light"), I18n.s("dark")}, config.theme());
        theme.setOnItemSelectedListener(selection(p -> { if (p != config.theme()) { config.theme(p); activity.recreate(); } }));

        LinearLayout audio = ui.card(pages[1]); ui.helpTitle(audio, I18n.s("record_settings"), I18n.s("settings_stop_hint"));
        minutes = number(audio, I18n.s("segment_minutes"), draft == null ? String.valueOf(config.minutes()) : draft.getString("draft_minutes", String.valueOf(config.minutes())));
        quota = number(audio, I18n.s("quota_mib"), draft == null ? String.valueOf(config.quotaMiB()) : draft.getString("draft_quota", String.valueOf(config.quotaMiB())));
        String[] bitrates = {"64", "96", "128", "192", "256"};
        ui.text(audio, I18n.s("bitrate"), 13, ui.muted);
        bitrate = ui.spinner(audio, bitrates, draft == null ? find(bitrates, String.valueOf(config.bitrate())) : draft.getInt("draft_bitrate", 2));
        ui.text(audio, I18n.s("sample_rate"), 13, ui.muted);
        rate = ui.spinner(audio, new String[]{"44100", "48000"}, draft == null ? (config.sampleRate() == 48000 ? 1 : 0) : draft.getInt("draft_rate", 0));
        ui.helpLabel(audio, I18n.s("input_device"), I18n.s("input_hint")+"\n\n"+I18n.s("bluetooth_hint"));
        device = ui.spinner(audio, new String[]{I18n.s("input_auto")}, 0);
        refillInputs(draft == null ? config.input() : draft.getString("draft_input", config.input()));
        bluetoothFormat=ui.text(audio,"",12,ui.muted);
        Button detect = ui.button(audio, I18n.s("refresh_inputs"), () -> activity.bluetoothPermission(() -> refillInputs(selectedInput())), false);
        ui.text(audio, I18n.s("source_mode"), 13, ui.muted);
        List<String> sourceLabels = new ArrayList<>();
        addSource(sourceLabels, MediaRecorder.AudioSource.MIC, "source_mic");
        addSource(sourceLabels, MediaRecorder.AudioSource.VOICE_RECOGNITION, "source_voice");
        addSource(sourceLabels, MediaRecorder.AudioSource.CAMCORDER, "source_camera");
        if (AudioInputs.rawSupported(activity)) addSource(sourceLabels, MediaRecorder.AudioSource.UNPROCESSED, "source_raw");
        int wantedSource = draft == null ? config.source() : draft.getInt("draft_source", config.source());
        source = ui.spinner(audio, sourceLabels.toArray(new String[0]), Math.max(0, sourceIds.indexOf(wantedSource)));
        cleanup = ui.helpToggle(audio, I18n.s("cleanup"), draft == null ? config.deleteOldest() : draft.getBoolean("draft_cleanup", config.deleteOldest()), I18n.s("cleanup_hint"));
        boot = ui.toggle(audio, I18n.s(Platform.armedService() ? "boot_remind" : "restore_manual"), draft == null ? config.resumeAtBoot() : draft.getBoolean("draft_boot", config.resumeAtBoot()));
        Button save = ui.button(audio, I18n.s("save_record_settings"), this::saveSettings, true);
        for (View control : new View[]{minutes, quota, bitrate, rate, source, device, detect, cleanup, boot, save}) recordingControls.add(control);

        LinearLayout schedule = ui.card(pages[2]); ui.helpTitle(schedule, I18n.s("weekly_schedule"), I18n.s("schedule_toggle_hint") + "\n\n"
                + I18n.s("schedule_hint") + (Platform.armedService() ? "\n\n" + I18n.s("standby_hint") : ""));
        scheduleStatus = ui.text(schedule, "", 13, ui.accent);
        scheduleToggle = ui.button(schedule, "", () -> {
            if (scheduleArmed()) { ScheduleManager.pauseSchedule(activity); refresh(); }
            else activity.recordingPermission(() -> {
                if (saveSchedule(true)) { ScheduleManager.activateSchedule(activity); refresh(); }
            });
        }, true);
        WeeklySchedule.Day[] existing = config.days();
        for (int i = 0; i < 7; i++) {
            final int day = i;
            from[i] = draft == null ? existing[i].start : draft.getInt("draft_from_" + i, existing[i].start);
            to[i] = draft == null ? existing[i].end : draft.getInt("draft_to_" + i, existing[i].end);
            days[i] = ui.toggle(schedule, I18n.s("day_" + i), draft == null ? existing[i].enabled : draft.getBoolean("draft_day_" + i, existing[i].enabled));
            LinearLayout times = ui.row(); schedule.addView(times);
            fromButtons[i] = ui.button(null, "", () -> time(day, false), false);
            toButtons[i] = ui.button(null, "", () -> time(day, true), false);
            ui.equal(times, fromButtons[i]); ui.equal(times, toButtons[i]); updateTime(day);
        }
        if (android.os.Build.VERSION.SDK_INT >= 31) ui.button(schedule, I18n.s("allow_alarms"), () -> {
            try { Platform.requestAlarms(activity); } catch (RuntimeException e) { ui.toast(I18n.s("alarm_required")); }
        }, false);
        ui.button(schedule, I18n.s("allow_notifications"), () -> {
            try { Platform.notificationSettings(activity); } catch (RuntimeException e) { ui.toast(I18n.s("notification_required")); }
        }, false);
        ui.button(schedule, I18n.s("save_schedule"), () -> saveSchedule(false), false);

        LinearLayout playback = ui.card(pages[1]); ui.helpTitle(playback, I18n.s("silence"), I18n.s("silence_hint"));
        TextView silence = ui.text(playback, I18n.s("silence_threshold", config.silenceDb()), 14, ui.ink);
        SeekBar threshold = new SeekBar(activity); threshold.setMax(40); threshold.setProgress(config.silenceDb() + 60); playback.addView(threshold);
        threshold.setContentDescription(I18n.s("silence"));
        threshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int value, boolean user) {
                silence.setText(I18n.s("silence_threshold", value - 60));
                if (user) { config.prefs.edit().putInt("silence_db", value - 60).apply(); activity.silenceChanged(); }
            }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { }
        });

        LinearLayout storage = ui.card(pages[3]); ui.helpTitle(storage, I18n.s("webdav_title"),
                I18n.s("webdav_intro") + "\n\n" + I18n.s("webdav_nextcloud_recommend")
                + (!Platform.publicStorage() ? "\n\n" + I18n.s("private_storage_hint") : ""));
        cloudStatus = ui.text(storage, "", 13, ui.muted);
        ui.button(storage, I18n.s("cloud_connection"), () -> activity.startActivity(new Intent(activity, CloudActivity.class)), true);
        ui.text(storage, RecordingFiles.outputDirectory(activity).getAbsolutePath(), 12, ui.muted).setTextIsSelectable(true);
        ui.button(storage, I18n.s("copy_path"), () -> {
            activity.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Iben Recorder", RecordingFiles.outputDirectory(activity).getAbsolutePath()));
            ui.toast(I18n.s("copied"));
        }, false);
        if (!Platform.publicStorage()) {
            ui.help(storage, ui.button(null, I18n.s("restore_folder"), activity::restoreFolder, false),
                    I18n.s("restore_folder"), I18n.s("restore_hint"));
        }
        LinearLayout notices = ui.card(pages[0]); ui.helpTitle(notices, I18n.s("notifications"), I18n.s("problem_alerts_hint"));
        Switch alerts = ui.toggle(notices, I18n.s("problem_alerts"), ProblemNotifications.enabled(activity));
        alerts.setOnCheckedChangeListener((button, enabled) -> {
            config.prefs.edit().putBoolean("problem_alerts", enabled).apply();
            if (enabled) activity.notificationPermission(() -> { }); else ProblemNotifications.reset(activity);
        });
        ui.button(notices, I18n.s("allow_notifications"), () -> {
            try { Platform.notificationSettings(activity); } catch (RuntimeException e) { ui.toast(I18n.s("notification_required")); }
        }, false);
        LinearLayout system = ui.card(pages[0]); ui.title(system, I18n.s("service"));
        ui.button(system, I18n.s("battery"), () -> {
            try { activity.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            catch (RuntimeException e) { ui.toast(I18n.s("battery_manual")); }
        }, false);
        ui.button(system, I18n.s("log"), () -> {
            TextView text = new TextView(activity); text.setText(I18n.tr(AppLog.read(activity))); text.setTextIsSelectable(true);
            text.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12));
            ScrollView scroll = new ScrollView(activity); scroll.addView(text);
            new AlertDialog.Builder(activity).setTitle(I18n.s("log")).setView(scroll).setPositiveButton(I18n.s("close"), null).show();
        }, false);
        ui.text(system, "Iben Recorder · 0.7.4 · Android 8.1+", 12, ui.muted);
        device.setOnItemSelectedListener(selection(p -> refresh()));
        showSection(draft == null ? config.prefs.getInt("settings_section", 0) : draft.getInt("draft_section", 0)); refresh();
    }
    private interface Selected { void value(int position); }
    private AdapterView.OnItemSelectedListener selection(Selected action) {
        return new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> a, View v, int p, long id) { action.value(p); }
            @Override public void onNothingSelected(AdapterView<?> a) { }
        };
    }
    private int find(String[] values, String wanted) { for (int i = 0; i < values.length; i++) if (values[i].equals(wanted)) return i; return 0; }
    private EditText number(LinearLayout parent, String label, String value) {
        ui.text(parent, label, 13, ui.muted); EditText text = new EditText(activity);
        text.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); text.setSingleLine(true); text.setText(value); parent.addView(text); return text;
    }
    private void addSource(List<String> labels, int value, String key) { sourceIds.add(value); labels.add(I18n.s(key)); }
    private String selectedInput() { int i = device.getSelectedItemPosition(); return i >= 0 && i < inputs.size() ? inputs.get(i).key : config.input(); }
    private void refillInputs(String selected) {
        inputs = AudioInputs.choices(activity, selected);
        ArrayAdapter<AudioInputs.Choice> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, inputs);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); device.setAdapter(adapter);
        for (int i = 0; i < inputs.size(); i++) if (inputs.get(i).key.equals(selected)) device.setSelection(i);
    }
    private void saveSettings() {
        boolean fresh = System.currentTimeMillis() - config.prefs.getLong("heartbeat", 0) <= 90000;
        if (config.wanted() || (fresh && config.prefs.getBoolean("engine_active", false))) { ui.toast(I18n.s("settings_stop_hint")); return; }
        try {
            int mode = sourceIds.get(source.getSelectedItemPosition()); AudioInputs.validateSource(activity, mode);
            config.save(Integer.parseInt(minutes.getText().toString().trim()), Integer.parseInt(bitrate.getSelectedItem().toString()),
                    Integer.parseInt(rate.getSelectedItem().toString()), Integer.parseInt(quota.getText().toString().trim()),
                    cleanup.isChecked(), boot.isChecked(), mode, selectedInput());
            ui.toast(I18n.s("saved"));
        } catch (Exception e) { ui.toast(e instanceof NumberFormatException ? I18n.s("numbers_invalid") : e.getMessage()); }
    }
    private void time(int day, boolean end) {
        int value = end ? to[day] : from[day];
        new TimePickerDialog(activity, (picker, h, m) -> { if (end) to[day] = h * 60 + m; else from[day] = h * 60 + m; updateTime(day); }, value / 60, value % 60, true).show();
    }
    private void updateTime(int i) {
        fromButtons[i].setText(I18n.s("from") + " " + String.format(Locale.ROOT, "%02d:%02d", from[i] / 60, from[i] % 60));
        toButtons[i].setText(I18n.s("to") + " " + String.format(Locale.ROOT, "%02d:%02d", to[i] / 60, to[i] % 60) + (to[i] <= from[i] ? " +1" : ""));
    }
    private boolean scheduleArmed() { return config.scheduleEnabled() && !config.prefs.getBoolean("schedule_paused", false); }
    void showSection(int index) {
        currentSection = Math.max(0, Math.min(sections.length - 1, index));
        for (int i = 0; i < sections.length; i++) {
            sections[i].setVisibility(i == currentSection ? View.VISIBLE : View.GONE);
            sectionButtons[i].setBackgroundTintList(android.content.res.ColorStateList.valueOf(i == currentSection ? ui.accent : ui.pale));
            sectionButtons[i].setTextColor(i == currentSection ? ui.background : ui.accent);
            sectionButtons[i].setSelected(i == currentSection);
        }
        config.prefs.edit().putInt("settings_section", currentSection).apply();
    }
    private boolean saveSchedule(boolean activating) {
        WeeklySchedule.Day[] rules = new WeeklySchedule.Day[7]; boolean any = false;
        for (int i = 0; i < 7; i++) { rules[i] = new WeeklySchedule.Day(days[i].isChecked(), from[i], to[i]); any |= rules[i].enabled; }
        if ((activating || scheduleArmed()) && !any) { ui.toast(I18n.s("choose_day")); return false; }
        try {
            config.schedule(rules);
            if (!activating) { ScheduleManager.reconcile(activity, true); ui.toast(I18n.s("saved")); }
            refresh(); return true;
        } catch (Exception e) { ui.toast(e.getMessage()); return false; }
    }
    void refresh() {
        scheduleToggle.setText(I18n.s(scheduleArmed() ? "schedule_pause_only" : "resume_schedule"));
        boolean stale = System.currentTimeMillis() - config.prefs.getLong("heartbeat", 0) > 90000;
        boolean running = config.wanted() || (config.prefs.getBoolean("engine_active", false) && !stale);
        for (View control : recordingControls) control.setEnabled(!running);
        boolean wireless=AudioInputPolicy.bluetooth(selectedInput());
        source.setEnabled(!running && !wireless);rate.setEnabled(!running && !wireless);bitrate.setEnabled(!running && !wireless);
        bluetoothFormat.setVisibility(wireless ? View.VISIBLE : View.GONE);
        if(wireless)bluetoothFormat.setText(I18n.s("bluetooth_format",AudioInputPolicy.sampleRate(android.os.Build.VERSION.SDK_INT,selectedInput(),config.sampleRate())));
        WeeklySchedule.State state = ScheduleManager.state(config);
        String text = I18n.s("schedule_off");
        if (config.scheduleEnabled()) {
            long skip = config.prefs.getLong("schedule_skip", 0);
            if (config.prefs.getBoolean("schedule_paused", false)) text = I18n.s("schedule_paused");
            else if (!Platform.recordingGranted(activity)) text = I18n.s("record_permission");
            else if (!Platform.exactAlarms(activity)) text = I18n.s("alarm_required");
            else if (!Platform.notifications(activity)) text = I18n.s("notification_required");
            else if (Platform.armedService() && !RecorderService.alive()) text = I18n.s("resume_required");
            else if (state.active && (skip == -1 || skip > System.currentTimeMillis())) text = I18n.s("schedule_skipped");
            else text = I18n.s(state.active ? "schedule_active" : "schedule_waiting");
            if (state.next > 0) text += "\n" + I18n.s(state.active ? "next_end" : "next_start") + ": "
                    + DateTimeFormatter.ofPattern("EEE dd.MM HH:mm", new Locale(config.language())).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(state.next));
        }
        scheduleStatus.setText(text + "\n" + I18n.s("timezone") + ": " + ZoneId.systemDefault().getId());
        cloudStatus.setText(I18n.tr(new CloudSettings(activity).prefs.getString("status", "Підключення ще не налаштоване")));
    }
    void saveDraft(Bundle state) {
        state.putString("draft_minutes", minutes.getText().toString()); state.putString("draft_quota", quota.getText().toString());
        state.putInt("draft_bitrate", bitrate.getSelectedItemPosition()); state.putInt("draft_rate", rate.getSelectedItemPosition());
        state.putInt("draft_source", sourceIds.get(source.getSelectedItemPosition())); state.putString("draft_input", selectedInput());
        state.putBoolean("draft_cleanup", cleanup.isChecked()); state.putBoolean("draft_boot", boot.isChecked()); state.putInt("draft_section", currentSection);
        for (int i = 0; i < 7; i++) { state.putBoolean("draft_day_" + i, days[i].isChecked()); state.putInt("draft_from_" + i, from[i]); state.putInt("draft_to_" + i, to[i]); }
    }
}
