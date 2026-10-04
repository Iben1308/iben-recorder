package ua.iben.recorder;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioDeviceInfo;
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
    private final CaptureEnvelope envelope;
    private final ArrayBlockingQueue<short[]> pcm = new ArrayBlockingQueue<>(64);
    private final ArrayBlockingQueue<Packet> packets = new ArrayBlockingQueue<>(512);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicInteger finishing = new AtomicInteger();
    private volatile boolean stopRequested;
    private volatile boolean captureEnded;
    private volatile boolean encoderEnded;
    private volatile boolean writerEnded;
    private volatile AudioRecord audio;
    private AudioDeviceInfo preferred;
    private BluetoothRoute bluetooth;
    private volatile CaptureMonitor monitor;
    private volatile boolean capturing;
    private volatile long failureAt;
    private volatile long segmentStart;
    private volatile long segmentUs;
    private volatile long lastCapture = SystemClock.elapsedRealtime();
    private volatile long lastWrite = SystemClock.elapsedRealtime();
    private volatile boolean recording;
    private final RecordingPosition bookmarkPosition = new RecordingPosition();
    private long sessionWall;
    private String sessionZone;

    ContinuousRecorder(Config config, RecordingFiles files, Listener listener) {
        this.config = config; this.files = files; this.listener = listener;
        rate = config.captureRate(); bitrate = config.captureBitrate(); minutes = config.minutes();
        gain = new AudioGain(config.gainDb());
        envelope = new CaptureEnvelope(rate, minutes);
    }
    void start() { new Thread(this::run, "iben-aac").start(); }
    void stop() {
        stopRequested = true;
        AudioRecord a = audio;
        if (a != null) try { a.stop(); } catch (RuntimeException ignored) { }
    }
    void abort(String reason) { fail(new RecordingFailure(RecordingFailure.Reason.STALLED,new IOException(reason))); }
    void abort(RecordingFailure.Reason reason) {fail(new RecordingFailure(reason));}
    boolean capturing() {return capturing;}
    int captureHealth() {CaptureMonitor value=monitor;return value==null ? CaptureHealth.NORMAL : value.state();}
    boolean recording() { return recording; }
    RecordingPosition.Moment bookmarkPosition() { return recording && !stopRequested ? bookmarkPosition.snapshot() : null; }
    long segmentStart() { return segmentStart; }
    long segmentMillis() { return segmentUs / 1000L; }
    int finishing() { return finishing.get(); }
    float peak() { return gain.peak(); }
    float limitedFraction() { return gain.limitedFraction(); }
    long lastCapture() { return lastCapture; }
    long lastWrite() { return lastWrite; }
    long failureAt() {return failureAt;}
    private void fail(Throwable e) { if(failure.compareAndSet(null,e))failureAt=System.currentTimeMillis();stop(); }

    private void run() {
        MediaCodec codec = null;
        Thread capture = null;
        Thread writer = null;
        boolean acquired = false;
        RecordingFailure.Reason stage=RecordingFailure.Reason.STORAGE_IO;
        try {
            // A replacement Service instance must wait for the old one to finish its files.
            SESSION.acquire(); acquired = true;
            if (stopRequested) return;
            files.recover();
            long budget = StoragePolicy.segmentBudget(minutes, bitrate);
            if (2 * budget > config.quota())
                throw new StorageFullException("Збільште ліміт пам’яті: він має вміщувати два фрагменти із запасом");
            if (!files.ensureRoom(2 * budget))
                throw new StorageFullException("Недостатньо місця для фрагмента; очікування вільної пам’яті");
            if (stopRequested) return;
            stage=RecordingFailure.Reason.CONFIGURATION;
            boolean wireless=AudioInputPolicy.bluetooth(config.input());
            if(wireless) {
                bluetooth=new BluetoothRoute(config.context,config.input(),() -> stopRequested,() -> {
                    if(!stopRequested)fail(new RecordingFailure(RecordingFailure.Reason.INPUT_UNAVAILABLE));
                });
                bluetooth.start();
            }
            int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) throw new IOException("Мікрофон не підтримує обрану частоту");
            if(!wireless)AudioInputs.validateSource(config.context, config.source());
            if (config.context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("Потрібні дозволи на мікрофон і файли");
            stage=RecordingFailure.Reason.AUDIO_READ;
            audio = new AudioRecord(wireless ? MediaRecorder.AudioSource.VOICE_COMMUNICATION : config.source(), rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 4, rate * 2));
            if (audio.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("Не вдалося відкрити мікрофон");
            preferred = wireless ? null : AudioInputs.select(config, audio);
            monitor=new CaptureMonitor(audio,config.context.getSystemService(android.media.AudioManager.class),rate);
            stage=RecordingFailure.Reason.CODEC;
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate * 1000);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PCM_SAMPLES * 2);
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            if (stopRequested) return;
            stage=RecordingFailure.Reason.INPUT_BUSY;
            audio.startRecording();
            monitor.started();
            if (audio.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IOException("Мікрофон не почав запис");
            sessionWall = System.currentTimeMillis();
            sessionZone = TimeZone.getDefault().getID();
            lastCapture = lastWrite = SystemClock.elapsedRealtime();
            recording = true;
            writer = new Thread(this::write, "iben-files");
            capture = new Thread(this::capture, "iben-microphone");
            writer.start(); capture.start();
            stage=RecordingFailure.Reason.CODEC;
            encode(codec);
        } catch (SecurityException e) { fail(new IOException("Потрібні дозволи на мікрофон і файли", e)); }
        catch (Exception e) { if(!(stopRequested && e instanceof java.io.InterruptedIOException))fail(e instanceof RecordingFailure ? e : new RecordingFailure(stage,e)); }
        finally {
            stop();
            join(capture);
            recording = false;
            if (codec != null) {
                try { codec.stop(); } catch (Exception e) { fail(new RecordingFailure(RecordingFailure.Reason.CODEC,e)); }
                try { codec.release(); } catch (Exception e) { fail(new RecordingFailure(RecordingFailure.Reason.CODEC,e)); }
            }
            encoderEnded = true;
            join(writer); // Writer also waits for every finalizer before acknowledging stop.
            CaptureMonitor observed=monitor;monitor=null;if(observed!=null)observed.close();
            AudioRecord a = audio;
            audio = null;
            if (a != null) try { a.release(); } catch (Exception e) { fail(new RecordingFailure(RecordingFailure.Reason.AUDIO_READ,e)); }
            if(bluetooth!=null)bluetooth.close();
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
            long routeChecked = 0, routeDeadline=SystemClock.elapsedRealtime()+3000;
            boolean acceptedRoute=false;
            while (!stopRequested) {
                short[] samples = new short[PCM_SAMPLES];
                int count = audio.read(samples, 0, samples.length, AudioRecord.READ_BLOCKING);
                if (count < 0) {
                    if (stopRequested) break;
                    throw new RecordingFailure(count==AudioRecord.ERROR_INVALID_OPERATION ? RecordingFailure.Reason.INPUT_BUSY : RecordingFailure.Reason.AUDIO_READ,
                            new IOException("AudioRecord read="+count));
                }
                if (count == 0) continue;
                long routeNow = SystemClock.elapsedRealtime();
                if(bluetooth!=null && !bluetooth.accepts(audio.getRoutedDevice())) {
                    if(acceptedRoute || routeNow>=routeDeadline)throw new RecordingFailure(RecordingFailure.Reason.INPUT_UNAVAILABLE);
                    continue; // Discard startup buffers from any unrequested route.
                }
                if(!acceptedRoute) {acceptedRoute=true;sessionWall=System.currentTimeMillis()-count*1000L/rate;}
                monitor.samples(samples,count);
                if (routeNow - routeChecked >= 1000L) {
                    routeChecked = routeNow;
                    AudioDeviceInfo routed = audio.getRoutedDevice();
                    if (preferred != null && (routed == null || routed.getId() != preferred.getId()))
                        throw new RecordingFailure(RecordingFailure.Reason.INPUT_UNAVAILABLE);
                    config.prefs.edit().putString("input_route", routed == null ? "" : AudioInputs.key(routed)).apply();
                }
                gain.process(samples, count, config.gainDb());
                if (count != samples.length) {
                    short[] shortBlock = new short[count];
                    System.arraycopy(samples, 0, shortBlock, 0, count); samples = shortBlock;
                }
                // A bounded queue protects an old phone. Never silently discard audio on overload.
                if (!pcm.offer(samples, 500, TimeUnit.MILLISECONDS)) throw new RecordingFailure(RecordingFailure.Reason.CODEC);
                lastCapture = SystemClock.elapsedRealtime();capturing=true;
            }
        } catch (Exception e) { if (!stopRequested || !(e instanceof IllegalStateException)) fail(e instanceof RecordingFailure ? e : new RecordingFailure(RecordingFailure.Reason.AUDIO_READ,e)); }
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
                        if (block != null) envelope.add(block, offset, count);
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
            throw new RecordingFailure(RecordingFailure.Reason.STORAGE_IO,new IOException("Сховище не встигає приймати аудіо"));
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
                    if (format != null) throw new RecordingFailure(RecordingFailure.Reason.CODEC,new IOException("Кодек змінив формат під час запису"));
                    if (packet.format.getInteger(MediaFormat.KEY_SAMPLE_RATE) != rate
                            || packet.format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != 1)
                        throw new RecordingFailure(RecordingFailure.Reason.CODEC,new IOException("Кодек не підтримав обрану частоту або моно"));
                    format = packet.format; continue;
                }
                if (format == null) throw new RecordingFailure(RecordingFailure.Reason.CODEC,new IOException("Немає формату AAC"));
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
                bookmarkPosition.update(current.part.id, segmentUs / 1000L);
                lastWrite = SystemClock.elapsedRealtime();
                if (lastWrite - storageAt >= 30000L) {
                    storageAt = lastWrite;
                    if (!files.ensureRoom(2 * StoragePolicy.MIB)) throw new StorageFullException("Досягнуто ліміт пам’яті або вільного місця");
                }
            }
        } catch (Exception e) { fail(e instanceof RecordingFailure ? e : new RecordingFailure(RecordingFailure.Reason.STORAGE_IO,e)); }
        finally {
            if (current != null) {
                long end = current.last + SegmentTimeline.sampleTimeUs(1024, rate);
                // Keep AAC frame timing; the metadata duration reflects the actual coded frames.
                finishAsync(finalizers, current, end);
            }
            bookmarkPosition.clear();
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
        final float[] waveform = envelope.range(segment.first, end);
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
            } catch (Exception e) { fail(new RecordingFailure(RecordingFailure.Reason.STORAGE_IO,e)); }
            finally {
                try { segment.muxer.release(); } catch (Exception e) { stopped = false; fail(new RecordingFailure(RecordingFailure.Reason.STORAGE_IO,e)); }
                try {
                    if (stopped) files.finish(segment.part, waveform); else files.failed(segment.part);
                } catch (Exception e) { fail(new RecordingFailure(RecordingFailure.Reason.STORAGE_IO,e)); }
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
