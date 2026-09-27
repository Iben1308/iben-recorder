package ua.iben.recorder;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.os.Process;
import android.os.SystemClock;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.TimeZone;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** One microphone and one AAC encoder per session, not per file. */
final class ContinuousRecorder {
    interface Listener { void stopped(ContinuousRecorder recorder, Throwable error); }
    private static final int PCM_SAMPLES = 1024;
    private static final Semaphore SESSION = new Semaphore(1);
    private final Config config;
    private final RecordingFiles files;
    private final Listener listener;
    private final int rate;
    private final int bitrate;
    private final int minutes;
    private final AudioGain gain;
    private final ArrayBlockingQueue<short[]> pcm = new ArrayBlockingQueue<>(64);
    private final ArrayBlockingQueue<Packet> packets = new ArrayBlockingQueue<>(512);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicInteger finishing = new AtomicInteger();
    private volatile boolean stopRequested;
    private volatile boolean captureEnded;
    private volatile boolean encoderEnded;
    private volatile boolean writerEnded;
    private volatile AudioRecord audio;
    private volatile long segmentStart;
    private volatile long segmentUs;
    private volatile long lastCapture = SystemClock.elapsedRealtime();
    private volatile long lastWrite = SystemClock.elapsedRealtime();
    private volatile boolean recording;
    private long sessionWall;
    private String sessionZone;

    ContinuousRecorder(Config config, RecordingFiles files, Listener listener) {
        this.config = config; this.files = files; this.listener = listener;
        rate = config.sampleRate(); bitrate = config.bitrate(); minutes = config.minutes();
        gain = new AudioGain(config.gainDb());
    }
    void start() { new Thread(this::run, "iben-aac").start(); }
    void stop() {
        stopRequested = true;
        AudioRecord a = audio;
        if (a != null) try { a.stop(); } catch (RuntimeException ignored) { }
    }
    void abort(String reason) { fail(new IOException(reason)); }
    boolean recording() { return recording; }
    long segmentStart() { return segmentStart; }
    long segmentMillis() { return segmentUs / 1000L; }
    int finishing() { return finishing.get(); }
    float peak() { return gain.peak(); }
    float limitedFraction() { return gain.limitedFraction(); }
    long lastCapture() { return lastCapture; }
    long lastWrite() { return lastWrite; }
    private void fail(Throwable e) { failure.compareAndSet(null, e); stop(); }

    private void run() {
        MediaCodec codec = null;
        Thread capture = null;
        Thread writer = null;
        boolean acquired = false;
        try {
            // A replacement Service instance must wait for the old one to finish its files.
            SESSION.acquire(); acquired = true;
            if (stopRequested) return;
            files.recover();
            long budget = StoragePolicy.segmentBudget(minutes, bitrate);
            if (2 * budget > config.quota())
                throw new IOException("Збільште ліміт пам’яті: він має вміщувати два фрагменти із запасом");
            if (!files.ensureRoom(2 * budget))
                throw new IOException("Недостатньо місця для фрагмента; очікування вільної пам’яті");
            if (stopRequested) return;
            int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) throw new IOException("Мікрофон не підтримує обрану частоту");
            audio = new AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 4, rate * 2));
            if (audio.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("Не вдалося відкрити мікрофон");
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate * 1000);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PCM_SAMPLES * 2);
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            if (stopRequested) return;
            audio.startRecording();
            if (audio.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IOException("Мікрофон не почав запис");
            sessionWall = System.currentTimeMillis();
            sessionZone = TimeZone.getDefault().getID();
            lastCapture = lastWrite = SystemClock.elapsedRealtime();
            recording = true;
            writer = new Thread(this::write, "iben-files");
            capture = new Thread(this::capture, "iben-microphone");
            writer.start(); capture.start();
            encode(codec);
        } catch (Exception e) { fail(e); }
        finally {
            stop();
            join(capture);
            recording = false;
            if (codec != null) {
                try { codec.stop(); } catch (Exception e) { failure.compareAndSet(null, e); }
                try { codec.release(); } catch (Exception e) { failure.compareAndSet(null, e); }
            }
            encoderEnded = true;
            join(writer); // Writer also waits for every finalizer before acknowledging stop.
            AudioRecord a = audio;
            audio = null;
            if (a != null) try { a.release(); } catch (Exception e) { failure.compareAndSet(null, e); }
            if (acquired) SESSION.release();
            listener.stopped(this, failure.get());
        }
    }
    private static void join(Thread thread) {
        if (thread == null) return;
        boolean interrupted = false;
        while (thread.isAlive()) try { thread.join(); } catch (InterruptedException e) { interrupted = true; }
        if (interrupted) Thread.currentThread().interrupt();
    }
    private void capture() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            while (!stopRequested) {
                short[] samples = new short[PCM_SAMPLES];
                int count = audio.read(samples, 0, samples.length, AudioRecord.READ_BLOCKING);
                if (count < 0) {
                    if (stopRequested) break;
                    throw new IOException("Помилка читання мікрофона: " + count);
                }
                if (count == 0) continue;
                gain.process(samples, count, config.gainDb());
                if (count != samples.length) {
                    short[] shortBlock = new short[count];
                    System.arraycopy(samples, 0, shortBlock, 0, count); samples = shortBlock;
                }
                // A bounded queue protects an old phone. Never silently discard audio on overload.
                if (!pcm.offer(samples, 500, TimeUnit.MILLISECONDS)) throw new IOException("Кодек не встигає обробляти звук");
                lastCapture = SystemClock.elapsedRealtime();
            }
        } catch (Exception e) { if (!stopRequested || !(e instanceof IllegalStateException)) fail(e); }
        finally { captureEnded = true; }
    }
    private void encode(MediaCodec codec) throws Exception {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        short[] block = null;
        int offset = 0;
        long submitted = 0;
        long eosAt = 0;
        boolean inputEos = false;
        while (true) {
            if (writerEnded && failure.get() != null) throw new IOException("Запис файла припинено", failure.get());
            if (!inputEos) {
                if (block == null) { block = pcm.poll(); offset = 0; }
                // Recheck the queue AFTER observing captureEnded: the capture thread may have
                // enqueued its final block between poll() and the volatile end flag read.
                if (block != null || (captureEnded && pcm.isEmpty())) {
                    int slot = codec.dequeueInputBuffer(0);
                    if (slot >= 0) {
                        ByteBuffer input = codec.getInputBuffer(slot);
                        if (input == null) throw new IOException("Кодек не надав вхідний буфер");
                        input.clear(); input.order(ByteOrder.nativeOrder());
                        int count = block == null ? 0 : Math.min(block.length - offset, input.remaining() / 2);
                        if (block != null && count == 0) throw new IOException("Вхідний буфер кодека надто малий");
                        long pts = SegmentTimeline.sampleTimeUs(submitted, rate);
                        for (int i = 0; i < count; i++) input.putShort(block[offset + i]);
                        codec.queueInputBuffer(slot, 0, count * 2, pts, block == null ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                        submitted += count;
                        if (block == null) { inputEos = true; eosAt = SystemClock.elapsedRealtime(); }
                        else { offset += count; if (offset == block.length) block = null; }
                    }
                }
            }
            int out = codec.dequeueOutputBuffer(info, 10000);
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) offer(Packet.format(codec.getOutputFormat()));
            else if (out >= 0) {
                try {
                    if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        ByteBuffer data = codec.getOutputBuffer(out);
                        if (data == null) throw new IOException("Кодек не надав аудіо");
                        data.position(info.offset); data.limit(info.offset + info.size);
                        byte[] copy = new byte[info.size]; data.get(copy);
                        offer(Packet.audio(copy, info.presentationTimeUs, info.flags & ~MediaCodec.BUFFER_FLAG_END_OF_STREAM));
                    }
                } finally { codec.releaseOutputBuffer(out, false); }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
            if (inputEos && SystemClock.elapsedRealtime() - eosAt > 15000L)
                throw new IOException("Кодек не завершив останній буфер");
            if (stopRequested && !inputEos && SystemClock.elapsedRealtime() - lastCapture > 30000L)
                throw new IOException("Кодек не відповідає під час зупинки");
        }
    }
    private void offer(Packet packet) throws Exception {
        if (writerEnded || !packets.offer(packet, 1, TimeUnit.SECONDS))
            throw new IOException("Сховище не встигає приймати аудіо");
    }
    private static final class Packet {
        MediaFormat format;
        byte[] data;
        long pts;
        int flags;
        static Packet format(MediaFormat f) { Packet p = new Packet(); p.format = f; return p; }
        static Packet audio(byte[] data, long pts, int flags) {
            Packet p = new Packet(); p.data = data; p.pts = pts; p.flags = flags; return p;
        }
    }
    private static final class Segment {
        final RecordingFiles.Part part;
        final MediaMuxer muxer;
        final int track;
        final long first;
        long last;
        boolean wrote;
        Segment(RecordingFiles.Part part, MediaMuxer muxer, int track, long first) {
            this.part = part; this.muxer = muxer; this.track = track; this.first = first; this.last = first;
        }
    }
    private void write() {
        ThreadPoolExecutor finalizers = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2), runnable -> new Thread(runnable, "iben-finalize"));
        Segment current = null;
        MediaFormat format = null;
        SegmentTimeline timeline = new SegmentTimeline(minutes * 60L * 1000000L);
        long firstOutput = Long.MIN_VALUE;
        long storageAt = SystemClock.elapsedRealtime();
        try {
            while (!encoderEnded || !packets.isEmpty()) {
                Packet packet = packets.poll(100, TimeUnit.MILLISECONDS);
                if (packet == null) continue;
                if (packet.format != null) {
                    if (format != null) throw new IOException("Кодек змінив формат під час запису");
                    if (packet.format.getInteger(MediaFormat.KEY_SAMPLE_RATE) != rate
                            || packet.format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != 1)
                        throw new IOException("Кодек не підтримав обрану частоту або моно");
                    format = packet.format; continue;
                }
                if (format == null) throw new IOException("Немає формату AAC");
                if (firstOutput == Long.MIN_VALUE) firstOutput = packet.pts;
                if (timeline.accept(packet.pts)) {
                    Segment previous = current;
                    current = null;
                    if (previous != null) finishAsync(finalizers, previous, packet.pts);
                    current = open(format, packet.pts, sessionWall + (packet.pts - firstOutput) / 1000L);
                    segmentStart = current.part.start;
                }
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                info.set(0, packet.data.length, packet.pts - current.first, packet.flags);
                current.muxer.writeSampleData(current.track, ByteBuffer.wrap(packet.data), info);
                current.last = packet.pts; current.wrote = true;
                segmentUs = packet.pts - current.first;
                lastWrite = SystemClock.elapsedRealtime();
                if (lastWrite - storageAt >= 30000L) {
                    storageAt = lastWrite;
                    if (!files.ensureRoom(2 * StoragePolicy.MIB)) throw new IOException("Досягнуто ліміт пам’яті або вільного місця");
                }
            }
        } catch (Exception e) { fail(e); }
        finally {
            if (current != null) {
                long end = current.last + SegmentTimeline.sampleTimeUs(1024, rate);
                // Keep AAC frame timing; the metadata duration reflects the actual coded frames.
                finishAsync(finalizers, current, end);
            }
            writerEnded = true;
            finalizers.shutdown();
            boolean interrupted = false;
            while (!finalizers.isTerminated()) try { finalizers.awaitTermination(1, TimeUnit.SECONDS); }
            catch (InterruptedException e) { interrupted = true; }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
    private Segment open(MediaFormat encoded, long first, long wall) throws IOException {
        RecordingFiles.Part part = files.create(wall, sessionZone, StoragePolicy.segmentBudget(minutes, bitrate));
        MediaMuxer muxer = null;
        try {
            muxer = new MediaMuxer(part.file.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaFormat track = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1);
            track.setInteger(MediaFormat.KEY_BIT_RATE, bitrate * 1000);
            track.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            ByteBuffer csd = encoded.getByteBuffer("csd-0");
            if (csd == null) throw new IOException("Кодек не надав заголовок AAC");
            track.setByteBuffer("csd-0", csd.duplicate());
            int index = muxer.addTrack(track);
            muxer.start();
            return new Segment(part, muxer, index, first);
        } catch (Exception e) {
            if (muxer != null) try { muxer.release(); } catch (Exception ignored) { }
            files.failed(part);
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Не вдалося відкрити наступний M4A", e);
        }
    }
    private void finishAsync(ThreadPoolExecutor executor, Segment segment, long end) {
        finishing.incrementAndGet();
        Runnable task = () -> {
            boolean stopped = false;
            try {
                if (segment.wrote) {
                    MediaCodec.BufferInfo eos = new MediaCodec.BufferInfo();
                    eos.set(0, 0, end - segment.first, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    segment.muxer.writeSampleData(segment.track, ByteBuffer.allocateDirect(1), eos);
                    segment.muxer.stop(); stopped = true;
                }
            } catch (Exception e) { fail(e); }
            finally {
                try { segment.muxer.release(); } catch (Exception e) { stopped = false; fail(e); }
                try {
                    if (stopped) files.finish(segment.part); else files.failed(segment.part);
                } catch (Exception e) { fail(e); }
                finally { finishing.decrementAndGet(); }
            }
        };
        try { executor.execute(task); }
        catch (java.util.concurrent.RejectedExecutionException e) {
            // Stop explicitly on a persistently overloaded disk; still preserve the closed segment.
            fail(new IOException("Черга завершення файлів переповнена", e));
            task.run();
        }
    }
}
