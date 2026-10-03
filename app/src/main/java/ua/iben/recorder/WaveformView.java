package ua.iben.recorder;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;

final class WaveformView extends View {
    interface Seek { void to(long millis); }
    private final Ui ui;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path flag = new Path();
    private final Seek seek;
    private float[] values = new float[0];
    private boolean[] silent = new boolean[0];
    private long duration = 1, position, from;
    private int zoom = 1;
    private long[] bookmarks = new long[0];
    private long pressedBookmark = -1;
    private float touchX, touchY;
    private final int touchSlop;
    WaveformView(Ui ui, Seek seek) {
        super(ui.activity); this.ui = ui; this.seek = seek;
        touchSlop = ViewConfiguration.get(ui.activity).getScaledTouchSlop();
        setFocusable(true); setClickable(true); setContentDescription(I18n.s("wave_accessibility"));
    }
    void bookmarks(long[] positions) {
        bookmarks = positions.clone(); pressedBookmark = -1;
        setContentDescription(I18n.s("wave_accessibility") + (bookmarks.length == 0 ? "" : " " + I18n.s("wave_bookmarks", bookmarks.length)));
        invalidate();
    }
    void data(WaveformAnalyzer.Data data, long duration, int threshold) {
        this.duration = Math.max(1, data == null ? duration : data.duration);
        values = data == null ? new float[0] : data.db;
        silent = AudioEnvelope.silence(values, threshold); from = 0; zoom = 1; invalidate();
    }
    int quietPercent() { int n = 0; for (boolean b : silent) if (b) n++; return silent.length == 0 ? 0 : n * 100 / silent.length; }
    void threshold(int db) { silent = AudioEnvelope.silence(values, db); invalidate(); }
    int zoom() { zoom = zoom == 1 ? 4 : zoom == 4 ? 16 : 1; center(); invalidate(); return zoom; }
    void position(long millis) {
        long value=Math.min(duration,Math.max(0,millis)); if(position==value)return; position=value;
        if (position < from || position > from + window()) center();
        invalidate();
    }
    private long window() { return Math.max(1, duration / zoom); }
    private void center() { from = Math.max(0, Math.min(duration - window(), position - window() / 2)); }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float left = ui.dp(8), width = getWidth() - 2 * left, top = ui.dp(24), bottom = getHeight() - ui.dp(26);
        if (width <= 0 || bottom <= top) return;
        float middle = (top + bottom) / 2;
        paint.setColor(ui.pale); c.drawRoundRect(left, top, left + width, bottom, ui.dp(6), ui.dp(6), paint);
        int bars = Math.max(1, (int) (width / ui.dp(3)));
        for (int x = 0; x < bars && values.length > 0; x++) {
            long a = from + window() * x / bars, b = from + window() * (x + 1) / bars;
            int first = (int) Math.min(values.length - 1, a * values.length / duration);
            int last = (int) Math.min(values.length, Math.max(first + 1, b * values.length / duration));
            float peak = -96; boolean quiet = true;
            for (int i = first; i < last; i++) { peak = Math.max(peak, values[i]); quiet &= silent[i]; }
            float height = Math.max(ui.dp(1), (peak + 72) / 72 * (bottom - top) * .45f);
            paint.setColor(quiet ? ui.muted : ui.accent); paint.setAlpha(quiet ? 100 : 235);
            float start = left + width * x / bars;
            c.drawRect(start, middle - height, start + Math.max(1, width / bars - ui.dp(1)), middle + height, paint);
            if (quiet) { paint.setAlpha(75); c.drawRect(start, top, start + width / bars, top + ui.dp(5), paint); }
        }
        paint.setAlpha(255); paint.setColor(ui.bookmark); paint.setStrokeWidth(ui.dp(1));
        for (long mark : bookmarks) {
            if (mark < 0 || mark > duration || mark < from || mark > from + window()) continue;
            float x = left + (mark - from) * width / window();
            c.drawLine(x, top, x, bottom, paint);
            float half = ui.dp(5), y = ui.dp(4);
            flag.reset(); flag.moveTo(x - half, y); flag.lineTo(x + half, y);
            flag.lineTo(x + half, y + ui.dp(15)); flag.lineTo(x, y + ui.dp(11));
            flag.lineTo(x - half, y + ui.dp(15)); flag.close(); c.drawPath(flag, paint);
        }
        paint.setColor(ui.red); paint.setStrokeWidth(ui.dp(2));
        float head = left + (position - from) * width / window();
        if (head >= left && head <= left + width) c.drawLine(head, top - ui.dp(4), head, bottom + ui.dp(4), paint);
        paint.setColor(ui.muted); paint.setTextSize(ui.dp(10));
        paint.setTextAlign(Paint.Align.LEFT); c.drawText(Ui.clock(from), left, getHeight() - ui.dp(5), paint);
        paint.setTextAlign(Paint.Align.CENTER); c.drawText(Ui.clock(from + window() / 2), left + width / 2, getHeight() - ui.dp(5), paint);
        paint.setTextAlign(Paint.Align.RIGHT); c.drawText(Ui.clock(from + window()), left + width, getHeight() - ui.dp(5), paint);
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            touchX=e.getX();touchY=e.getY();pressedBookmark=bookmarkAt(touchX,touchY);
            if (getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
            if(pressedBookmark<0)seek.to(timeAt(e.getX()));
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if(Math.abs(e.getX()-touchX)>touchSlop || Math.abs(e.getY()-touchY)>touchSlop)pressedBookmark=-1;
            if(pressedBookmark<0)seek.to(timeAt(e.getX()));
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            if(pressedBookmark>=0) {seek.to(pressedBookmark);announceForAccessibility(Ui.clock(pressedBookmark));}
            else seek.to(timeAt(e.getX()));
            pressedBookmark=-1;performClick();
            if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            pressedBookmark=-1;
            if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);
            return true;
        }
        return super.onTouchEvent(e);
    }
    private long timeAt(float x) {
        return from+(long)(Math.max(0d,Math.min(1d,(x-ui.dp(8))/Math.max(1d,getWidth()-ui.dp(16))))*window());
    }
    private long bookmarkAt(float x,float y) {
        if(y>ui.dp(28))return -1;
        float distance=ui.dp(16),width=getWidth()-ui.dp(16);long nearest=-1;
        if(width<=0)return -1;
        for(long mark:bookmarks) {
            if(mark<0 || mark>duration || mark<from || mark>from+window())continue;
            float delta=Math.abs(x-(ui.dp(8)+(mark-from)*width/window()));
            if(delta<distance) {distance=delta;nearest=mark;}
        }
        return nearest;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    @Override public boolean onKeyDown(int code, KeyEvent event) {
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            seek.to(Math.max(0, Math.min(duration, position + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -10000 : 10000)))); return true;
        }
        return super.onKeyDown(code, event);
    }
}
