package ua.iben.recorder;

import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.content.Context;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class AudioInputs {
    static final class Choice {
        final String key, label;
        Choice(String key, String label) { this.key = key; this.label = label; }
        @Override public String toString() { return label; }
    }
    static String key(AudioDeviceInfo d) { return d.getType() + ":" + d.getProductName(); }
    static String labelKey(String key) {
        int colon = key.indexOf(':');
        if (colon < 0) return key;
        String kind = "input_other";
        try {
            int type = Integer.parseInt(key.substring(0, colon));
            if (type == AudioDeviceInfo.TYPE_BUILTIN_MIC) kind = "input_builtin";
            else if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET) kind = "input_wired";
            else if (type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET) kind = "input_usb";
        } catch (NumberFormatException ignored) { }
        return I18n.s(kind) + " · " + key.substring(colon + 1);
    }
    static String label(AudioDeviceInfo d) {
        String kind;
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: kind = "input_builtin"; break;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: kind = "input_wired"; break;
            default: kind = "input_usb";
        }
        return I18n.s(kind) + " · " + d.getProductName();
    }
    private static boolean supported(AudioDeviceInfo d) {
        return d.isSource() && (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC
                || d.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET || d.getType() == AudioDeviceInfo.TYPE_USB_DEVICE
                || d.getType() == AudioDeviceInfo.TYPE_USB_HEADSET);
    }
    static List<Choice> choices(Context context, String selected) {
        List<Choice> list = new ArrayList<>(); list.add(new Choice("auto", I18n.s("input_auto")));
        boolean found = "auto".equals(selected);
        for (AudioDeviceInfo d : context.getSystemService(AudioManager.class).getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (!supported(d)) continue;
            list.add(new Choice(key(d), label(d))); found |= key(d).equals(selected);
        }
        if (!found) list.add(new Choice(selected, I18n.s("input_unavailable") + " · " + selected));
        return list;
    }
    static AudioDeviceInfo select(Config config, AudioRecord record) throws IOException {
        if ("auto".equals(config.input())) return null;
        for (AudioDeviceInfo d : config.context.getSystemService(AudioManager.class).getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (supported(d) && key(d).equals(config.input())) {
                if (!record.setPreferredDevice(d)) throw new IOException("Не вдалося вибрати аудіовхід");
                return d;
            }
        }
        throw new IOException("Обраний аудіовхід від’єднаний; очікування підключення");
    }
    static boolean rawSupported(Context c) {
        return "true".equals(c.getSystemService(AudioManager.class).getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
    }
    static void validateSource(Context c, int source) {
        if (source != MediaRecorder.AudioSource.MIC && source != MediaRecorder.AudioSource.VOICE_RECOGNITION
                && source != MediaRecorder.AudioSource.CAMCORDER && !(source == MediaRecorder.AudioSource.UNPROCESSED && rawSupported(c)))
            throw new IllegalArgumentException("Обраний режим мікрофона не підтримується");
    }
}
