package ua.iben.recorder;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.SystemClock;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;

final class WaveformAnalyzer {
    interface Progress { void update(int percent); }
    static final class Data {
        final long duration;
        final float[] db;
        Data(long duration, float[] db) { this.duration = duration; this.db = db; }
    }
    private static final int MAGIC = 0x49425731;
    private static void check() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Analysis canceled");
    }
    static Data read(Context context, File source, long duration, Progress progress) throws Exception {
        long length = source.length(), modified = source.lastModified();
        File folder = new File(context.getCacheDir(), "waveforms");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Не вдалося створити кеш шкали");
        String hash = DavTarget.hex(MessageDigest.getInstance("SHA-256").digest(
                (source.getAbsolutePath() + "\n" + length + "\n" + modified).getBytes(StandardCharsets.UTF_8)));
        File cached = new File(folder, hash + ".wave");
        if (cached.isFile()) try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(cached)))) {
            if (in.readInt() != MAGIC) throw new IOException();
            long storedDuration = in.readLong(); int n = in.readInt();
            if (n <= 0 || n > 100000 || cached.length() != 16L + n * 4L || storedDuration <= 0) throw new IOException();
            float[] db = new float[n];
            for (int i = 0; i < n; i++) { db[i] = in.readFloat(); if (!Float.isFinite(db[i])) throw new IOException(); }
            check(); return new Data(storedDuration, db);
        } catch (InterruptedIOException e) { throw e; }
          catch (IOException ignored) { cached.delete(); }
        Data data = decode(source, duration, progress);
        check();
        if (source.length() != length || source.lastModified() != modified) throw new IOException("Локальний файл змінився");
        File temporary = File.createTempFile("wave-", ".tmp", folder);
        try {
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(temporary)))) {
                out.writeInt(MAGIC); out.writeLong(data.duration); out.writeInt(data.db.length);
                for (float db : data.db) out.writeFloat(db);
            }
            check();
            if (!temporary.renameTo(cached)) throw new IOException("Не вдалося зберегти шкалу");
        } finally { temporary.delete(); }
        File[] files = folder.listFiles();
        if (files != null) {
            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
            long bytes = 0; for (File file : files) bytes += file.length();
            for (File file : files) if (bytes > 32 * 1024 * 1024L && !file.equals(cached)) {
                long size = file.length(); if (file.delete()) bytes -= size;
            }
        }
        return data;
    }
    private static Data decode(File file, long duration, Progress progress) throws Exception {
        MediaExtractor extractor = new MediaExtractor(); MediaCodec codec = null;
        boolean started = false;
        try {
            extractor.setDataSource(file.getAbsolutePath());
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { format = f; extractor.selectTrack(i); break; }
            }
            if (format == null) throw new IOException("У файлі немає аудіодоріжки");
            if (duration <= 0 && format.containsKey(MediaFormat.KEY_DURATION)) duration = format.getLong(MediaFormat.KEY_DURATION) / 1000;
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0); codec.start(); started = true;
            AudioEnvelope envelope = null;
            int rate = 0, channels = 0, pcm = AudioFormat.ENCODING_PCM_16BIT;
            boolean inputEnd = false, outputEnd = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long alive = SystemClock.elapsedRealtime(), updated = 0;
            while (!outputEnd) {
                check();
                if (!inputEnd) {
                    int input = codec.dequeueInputBuffer(10000);
                    if (input >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(input); buffer.clear();
                        int n = extractor.readSampleData(buffer, 0);
                        if (n < 0) { codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnd = true; }
                        else { codec.queueInputBuffer(input, 0, n, extractor.getSampleTime(), 0); extractor.advance(); }
                        alive = SystemClock.elapsedRealtime();
                    }
                }
                int output = codec.dequeueOutputBuffer(info, 10000);
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat out = codec.getOutputFormat();
                    int newRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE), newChannels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (envelope != null && (newRate != rate || newChannels != channels)) throw new IOException("Декодер змінив формат PCM");
                    rate = newRate; channels = newChannels;
                    pcm = out.containsKey(MediaFormat.KEY_PCM_ENCODING) ? out.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
                    if (pcm != AudioFormat.ENCODING_PCM_16BIT && pcm != AudioFormat.ENCODING_PCM_FLOAT) throw new IOException("Непідтримуваний формат PCM");
                    if (envelope == null) envelope = new AudioEnvelope(rate, channels);
                } else if (output >= 0) {
                    try {
                        if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            if (envelope == null) throw new IOException("Декодер не надав формат PCM");
                            ByteBuffer buffer = codec.getOutputBuffer(output).duplicate().order(ByteOrder.LITTLE_ENDIAN);
                            buffer.position(info.offset); buffer.limit(info.offset + info.size);
                            while (buffer.remaining() >= (pcm == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2))
                                envelope.sample(pcm == AudioFormat.ENCODING_PCM_FLOAT ? buffer.getFloat() : buffer.getShort() / 32768f);
                        }
                        outputEnd = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    } finally { codec.releaseOutputBuffer(output, false); }
                    alive = SystemClock.elapsedRealtime();
                    if (alive - updated > 750) {
                        updated = alive;
                        progress.update((int) Math.min(99, info.presentationTimeUs / 10L / Math.max(1, duration)));
                    }
                }
                if (SystemClock.elapsedRealtime() - alive > 20000) throw new IOException("Декодер не відповідає");
            }
            if (envelope == null) throw new IOException("Немає декодованого звуку");
            float[] db = envelope.finish();
            if (db.length == 0) throw new IOException("Немає декодованого звуку");
            return new Data(duration > 0 ? duration : db.length * 250L, db);
        } finally {
            if (codec != null) { if (started) try { codec.stop(); } catch (RuntimeException ignored) { } codec.release(); }
            extractor.release();
        }
    }
}
