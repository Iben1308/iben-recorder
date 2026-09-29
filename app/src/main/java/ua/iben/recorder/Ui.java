package ua.iben.recorder;

import android.app.Activity;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

final class Ui {
    final Activity activity;
    final int ink, muted, accent, card, background, pale, red;
    final boolean dark;
    Ui(Activity activity, Config config) {
        this.activity = activity;
        dark = config.theme() == 2 || (config.theme() == 0 && (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        ink = Color.parseColor(dark ? "#EEF4F2" : "#1B302B");
        muted = Color.parseColor(dark ? "#ACBCB6" : "#60766D");
        accent = Color.parseColor(dark ? "#77DCC2" : "#146A52");
        background = Color.parseColor(dark ? "#111B17" : "#F2F5F1");
        card = Color.parseColor(dark ? "#1B2B24" : "#FFFFFF");
        pale = Color.parseColor(dark ? "#2B4237" : "#E1ECE4");
        red = Color.parseColor(dark ? "#FFB4AA" : "#B23C35");
    }
    int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    LinearLayout column() { LinearLayout v = new LinearLayout(activity); v.setOrientation(LinearLayout.VERTICAL); return v; }
    LinearLayout row() { LinearLayout v = new LinearLayout(activity); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    GradientDrawable shape(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    LinearLayout card(LinearLayout parent) {
        LinearLayout view = column(); view.setPadding(dp(18), dp(14), dp(18), dp(14)); view.setBackground(shape(card, 18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(12);
        parent.addView(view, lp); return view;
    }
    TextView text(LinearLayout parent, String text, int size, int color) {
        TextView view = new TextView(activity); view.setText(text); view.setTextSize(size); view.setTextColor(color);
        view.setPadding(0, dp(4), 0, dp(6)); if (parent != null) parent.addView(view); return view;
    }
    TextView title(LinearLayout parent, String text) {
        TextView v = text(parent, text, 19, ink); v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return v;
    }
    Button button(LinearLayout parent, String label, Runnable action, boolean primary) {
        Button v = new Button(activity); v.setAllCaps(false); v.setText(label); v.setTextSize(15);
        v.setTextColor(primary ? background : accent); v.setBackgroundTintList(ColorStateList.valueOf(primary ? accent : pale));
        v.setOnClickListener(w -> action.run()); if (parent != null) parent.addView(v, new LinearLayout.LayoutParams(-1, dp(52))); return v;
    }
    Spinner spinner(LinearLayout parent, String[] entries, int selected) {
        Spinner v = new Spinner(activity); ArrayAdapter<String> a = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, entries);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); v.setAdapter(a); v.setSelection(selected);
        parent.addView(v, new LinearLayout.LayoutParams(-1, dp(48))); return v;
    }
    Switch toggle(LinearLayout parent, String label, boolean checked) {
        Switch v = new Switch(activity); v.setText(label); v.setTextColor(ink); v.setTextSize(14); v.setChecked(checked);
        v.setPadding(0, dp(10), 0, dp(10)); parent.addView(v); return v;
    }
    void equal(LinearLayout row, View view) { row.addView(view, new LinearLayout.LayoutParams(0, -2, 1)); }
    void toast(String text) { Toast.makeText(activity, I18n.tr(text), Toast.LENGTH_LONG).show(); }
    static String clock(long millis) {
        long s = Math.max(0, millis / 1000);
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
    }
}
