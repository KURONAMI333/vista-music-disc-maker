package com.kuronami.mdmvista.client.media;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameConverter;
import org.bytedeco.javacv.Java2DFrameConverter;

import com.kuronami.musicdiscmaker.client.audio.PlaybackMediaSession;
import com.kuronami.musicdiscmaker.client.audio.PlaybackVideoFrame;
import com.kuronami.musicdiscmaker.client.audio.PlaybackSessionRegistry;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.lavaplayer.api.PlaybackFault;
import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;

/** One muxed local media input, split audio/video demux workers, bounded PCM/video queues. */
public final class JavaCvMuxedSession implements PlaybackMediaSession {
    public static final int SAMPLE_RATE = 48_000;
    public static final int CHANNELS = 2;
    public static final int BYTES_PER_FRAME = 4;
    public static final int PCM_QUEUE_CHUNKS = 256;
    public static final int PCM_CHUNK_BYTES_LIMIT = 16 * 1024;
    public static final int VIDEO_QUEUE_FRAMES = 3;
    public static final int MAX_VIDEO_PIXELS = 1920 * 1080;

    private record PcmChunk(long ptsUs, byte[] bytes) {}

    private static final class SeekRequest {
        private final long ptsUs;
        private final CompletableFuture<Void> done;
        private final AtomicInteger pending = new AtomicInteger(2);

        private SeekRequest(long ptsUs, CompletableFuture<Void> done) {
            this.ptsUs = ptsUs;
            this.done = done;
        }

        private void acknowledge() {
            if (pending.decrementAndGet() == 0) done.complete(null);
        }
    }

    private final FFmpegFrameGrabber audioGrabber;
    private final FFmpegFrameGrabber videoGrabber;
    private final FrameConverter<BufferedImage> converter = new Java2DFrameConverter();
    private final ArrayBlockingQueue<PcmChunk> pcm = new ArrayBlockingQueue<>(PCM_QUEUE_CHUNKS);
    private final Object videoLock = new Object();
    private final ArrayDeque<PlaybackVideoFrame> video = new ArrayDeque<>(VIDEO_QUEUE_FRAMES);
    private PlaybackVideoFrame lastPresentedFrame;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean paused = new AtomicBoolean();
    private final AtomicLong seekGeneration = new AtomicLong();
    private final AtomicLong playedFrames = new AtomicLong();
    private final AtomicLong lastSampleRate = new AtomicLong(SAMPLE_RATE);
    private final AtomicReference<PlaybackFault> terminalFault = new AtomicReference<>();
    private final AtomicReference<Throwable> closeFailure = new AtomicReference<>();
    private final CopyOnWriteArrayList<Consumer<PlaybackFault>> faultListeners = new CopyOnWriteArrayList<>();
    private final CountDownLatch workersDone = new CountDownLatch(2);
    private static final long LATE_FRAME_DROP_US = 200_000L;
    private final CountDownLatch firstAudio = new CountDownLatch(1);
    private final CountDownLatch firstVideo = new CountDownLatch(1);
    private volatile boolean audioQueued;
    private volatile boolean videoQueued;
    private final CompletableFuture<Void> cleanupAcknowledged = new CompletableFuture<>();
    private final AtomicReference<SeekRequest> seekRequest = new AtomicReference<>();
    private final Thread audioWorker;
    private final Thread videoWorker;
    private volatile boolean audioEos;
    private volatile boolean videoEos;
    private volatile long clockOriginUs;
    private volatile long clockOriginFrames;
    private volatile long minimumMediaPtsUs;
    private volatile long pausedAtUs;
    private byte[] currentPcm;
    private int currentOffset;

    public JavaCvMuxedSession(Path localMuxedFile, long startOffsetMs) throws Exception {
        this(localMuxedFile, startOffsetMs, false);
    }

    /** Starts directly from separate video/audio URLs without writing a local muxed copy. */
    public static JavaCvMuxedSession streaming(String videoUrl, String audioUrl, long startOffsetMs) throws Exception {
        if (videoUrl == null || audioUrl == null
                || !videoUrl.startsWith("https://") || !audioUrl.startsWith("https://"))
            throw new IllegalArgumentException("stream URLs must be HTTPS");
        if (startOffsetMs < 0) throw new IllegalArgumentException("negative start offset");
        return new JavaCvMuxedSession(videoUrl, audioUrl, startOffsetMs, true);
    }

    private JavaCvMuxedSession(Path localMuxedFile, long startOffsetMs, boolean unused) throws Exception {
        this(localMuxedFile.toAbsolutePath().normalize().toString(),
                localMuxedFile.toAbsolutePath().normalize().toString(), startOffsetMs, false);
    }

    private JavaCvMuxedSession(String videoSource, String audioSource, long startOffsetMs, boolean remote) throws Exception {
        if (startOffsetMs < 0) throw new IllegalArgumentException("negative start offset");
        if (!remote) {
            Path path = Path.of(videoSource);
            if (!java.nio.file.Files.isRegularFile(path)) throw new IOException("muxed media file is missing");
        }
        audioGrabber = new FFmpegFrameGrabber(audioSource);
        if (remote) configureRemote(audioGrabber);
        audioGrabber.setSampleRate(SAMPLE_RATE);
        audioGrabber.setAudioChannels(CHANNELS);
        audioGrabber.setSampleMode(org.bytedeco.javacv.FrameGrabber.SampleMode.SHORT);
        audioGrabber.setVideoStream(-1);
        videoGrabber = new FFmpegFrameGrabber(videoSource);
        if (remote) configureRemote(videoGrabber);
        videoGrabber.setAudioStream(-1);
        try {
            audioGrabber.start();
            videoGrabber.start();
            if (audioGrabber.getAudioChannels() != CHANNELS || audioGrabber.getSampleRate() != SAMPLE_RATE)
                throw new IOException("FFmpeg did not provide the requested stereo 48 kHz output");
            if (startOffsetMs > 0) {
                long timestampUs = Math.multiplyExact(startOffsetMs, 1_000L);
                audioGrabber.setTimestamp(timestampUs, true);
                videoGrabber.setTimestamp(timestampUs, true);
            }
        } catch (Exception | LinkageError failure) {
            try { audioGrabber.close(); } catch (Exception | LinkageError closeFailure) { failure.addSuppressed(closeFailure); }
            try { videoGrabber.close(); } catch (Exception | LinkageError closeFailure) { failure.addSuppressed(closeFailure); }
            cleanupAcknowledged.complete(null);
            throw failure;
        }
        clockOriginUs = Math.multiplyExact(startOffsetMs, 1_000L);
        minimumMediaPtsUs = clockOriginUs;
        audioWorker = new Thread(this::audioLoop, "mdm-vista-javacv-audio");
        audioWorker.setDaemon(true);
        videoWorker = new Thread(this::videoLoop, "mdm-vista-javacv-video");
        videoWorker.setDaemon(true);
        audioWorker.start();
        videoWorker.start();
    }

    private static void configureRemote(FFmpegFrameGrabber grabber) {
        org.bytedeco.javacv.FFmpegLogCallback.set();
        org.bytedeco.javacv.FFmpegLogCallback.setLevel(org.bytedeco.ffmpeg.global.avutil.AV_LOG_QUIET);
        grabber.setOption("user_agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
        grabber.setOption("referer", "https://www.youtube.com/");
        grabber.setOption("rw_timeout", "5000000");
        grabber.setOption("reconnect", "1");
        grabber.setOption("reconnect_streamed", "1");
        grabber.setOption("reconnect_delay_max", "5");
    }

    /** Waits until both decode workers have produced their first queued frame. */
    public boolean awaitReady(long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
        if (!firstAudio.await(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) return false;
        return firstVideo.await(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
                && audioQueued && videoQueued && !closed.get() && terminalFault.get() == null;
    }

    @Override public IAudioSource audioSource() { return audioSource; }

    private final IAudioSource audioSource = new IAudioSource() {
        @Override public int sampleRate() { return SAMPLE_RATE; }
        @Override public int channels() { return CHANNELS; }
        @Override public int bitsPerSample() { return 16; }
        @Override public boolean bigEndian() { return false; }
        @Override public PlaybackFault playbackFault() { return terminalFault.get(); }
        @Override public void onPlaybackFault(Consumer<PlaybackFault> sink) {
            if (sink == null) return;
            faultListeners.add(sink);
            PlaybackFault current = terminalFault.get();
            if (current != null) sink.accept(current);
        }
        @Override public int read(byte[] dst, int off, int len) {
            if (closed.get()) return -1;
            final int limit = len - (len % BYTES_PER_FRAME);
            if (limit <= 0) return 0;
            int written = 0;
            synchronized (pcm) {
                while (written < limit) {
                    if (closed.get()) return written > 0 ? written : -1;
                    if (paused.get()) return written;
                    if (currentPcm == null || currentOffset >= currentPcm.length) {
                        // Sound engine thread: never wait. Return what is queued now.
                        // Read EOS before polling: a chunk offered after the poll is picked up on the next call.
                        boolean eos = audioEos;
                        PcmChunk next = pcm.poll();
                        if (next == null) {
                            if (written > 0) return written;
                            return eos ? -1 : 0;
                        }
                        currentPcm = next.bytes();
                        currentOffset = 0;
                    }
                    int count = Math.min(limit - written, currentPcm.length - currentOffset);
                    count -= count % BYTES_PER_FRAME;
                    if (count <= 0) {
                        currentPcm = null;
                        currentOffset = 0;
                        continue;
                    }
                    System.arraycopy(currentPcm, currentOffset, dst, off + written, count);
                    currentOffset += count;
                    written += count;
                }
            }
            return written;
        }
        @Override public void close() { JavaCvMuxedSession.this.close(); }
    };

    private void audioLoop() {
        SeekRequest appliedSeek = null;
        try {
            while (!closed.get()) {
                appliedSeek = applySeek(audioGrabber, appliedSeek);
                if (paused.get()) { sleepBriefly(); continue; }
                long generation = seekGeneration.get();
                Frame frame = audioGrabber.grab();
                if (frame == null) { audioEos = true; return; }
                EnumSet<Frame.Type> types = frame.getTypes();
                if (types.contains(Frame.Type.AUDIO) && frame.samples != null) copyAudio(frame, generation);
            }
        } catch (Exception | LinkageError failure) {
            fail("Muxed audio decode failed", failure);
        } finally {
            audioEos = true;
            firstAudio.countDown();
            closeGrabber(audioGrabber);
            finishWorker();
        }
    }

    private void videoLoop() {
        SeekRequest appliedSeek = null;
        try {
            while (!closed.get()) {
                appliedSeek = applySeek(videoGrabber, appliedSeek);
                if (paused.get()) { sleepBriefly(); continue; }
                long generation = seekGeneration.get();
                Frame frame = videoGrabber.grabImage();
                if (frame == null) { videoEos = true; return; }
                if (frame.image != null) copyVideo(frame, generation);
            }
        } catch (Exception | LinkageError failure) {
            fail("Muxed video decode failed", failure);
        } finally {
            videoEos = true;
            firstVideo.countDown();
            closeGrabber(videoGrabber);
            finishWorker();
        }
    }

    private SeekRequest applySeek(FFmpegFrameGrabber grabber, SeekRequest applied) throws Exception {
        SeekRequest request = seekRequest.get();
        if (request == null || request == applied) return applied;
        grabber.setTimestamp(request.ptsUs, true);
        request.acknowledge();
        return request;
    }

    private void copyVideo(Frame frame, long generation) throws Exception {
        if (generation != seekGeneration.get() || closed.get() || frame.timestamp < minimumMediaPtsUs) return;
        // Skip the costly conversion for frames already well behind the playback clock.
        if (frame.timestamp < presentationTimeUs() - LATE_FRAME_DROP_US) return;
        BufferedImage image = converter.convert(frame);
        if (image == null) return;
        int width = image.getWidth(), height = image.getHeight();
        if (width <= 0 || height <= 0 || (long) width * height > MAX_VIDEO_PIXELS)
            throw new IOException("video frame dimensions exceed the fixed decode limit");
        ByteBuffer rgba = ByteBuffer.allocate(Math.multiplyExact(Math.multiplyExact(width, height), 4));
        int[] pixels = new int[width * height];
        image.getRGB(0, 0, width, height, pixels, 0, width);
        for (int argb : pixels) {
            rgba.put((byte) (argb >>> 16)).put((byte) (argb >>> 8)).put((byte) argb).put((byte) (argb >>> 24));
        }
        rgba.flip();
        PlaybackVideoFrame copied = new PlaybackVideoFrame(width, height, Math.max(0L, frame.timestamp), rgba);
        if (generation != seekGeneration.get() || closed.get()) return;
        synchronized (videoLock) {
            while (video.size() >= VIDEO_QUEUE_FRAMES) {
                if (generation != seekGeneration.get() || closed.get()) return;
                videoLock.wait(20);
            }
            if (generation != seekGeneration.get() || closed.get()) return;
            video.addLast(copied);
            videoQueued = true;
            firstVideo.countDown();
        }
    }

    private void copyAudio(Frame frame, long generation) throws Exception {
        if (generation != seekGeneration.get() || closed.get() || frame.timestamp < minimumMediaPtsUs) return;
        if (frame.audioChannels != CHANNELS || frame.samples.length != 1 || !(frame.samples[0] instanceof ShortBuffer samples))
            throw new IOException("unexpected PCM layout; expected one interleaved stereo ShortBuffer");
        ShortBuffer input = samples.duplicate();
        if ((input.remaining() % CHANNELS) != 0) throw new IOException("stereo PCM frame is not channel aligned");
        int byteCount = Math.multiplyExact(input.remaining(), 2);
        if (byteCount == 0 || byteCount > PCM_CHUNK_BYTES_LIMIT) throw new IOException("PCM chunk exceeds fixed queue bound");
        ByteBuffer output = ByteBuffer.allocate(byteCount).order(ByteOrder.LITTLE_ENDIAN);
        while (input.hasRemaining()) output.putShort(input.get());
        byte[] bytes = output.array();
        PcmChunk chunk = new PcmChunk(Math.max(0L, frame.timestamp), bytes);
        while (!closed.get() && generation == seekGeneration.get()) {
            if (paused.get()) { sleepBriefly(); continue; }
            if (pcm.offer(chunk, 100, TimeUnit.MILLISECONDS)) {
                audioQueued = true;
                firstAudio.countDown();
                return;
            }
        }
    }

    /** Nonblocking control; the returned future acknowledges both decoders' seek and queue purge. */
    public CompletableFuture<Void> seekToUs(long targetUs) {
        if (targetUs < 0 || closed.get() || audioEos) return CompletableFuture.failedFuture(new IllegalArgumentException("invalid seek"));
        CompletableFuture<Void> done = new CompletableFuture<>();
        seekGeneration.incrementAndGet();
        pcm.clear(); synchronized (pcm) { currentPcm = null; currentOffset = 0; }
        synchronized (videoLock) { video.clear(); lastPresentedFrame = null; videoLock.notifyAll(); }
        clockOriginUs = targetUs;
        clockOriginFrames = playedFrames.get();
        minimumMediaPtsUs = targetUs;
        if (paused.get()) pausedAtUs = targetUs;
        SeekRequest next = new SeekRequest(targetUs, done);
        SeekRequest old = seekRequest.getAndSet(next);
        if (old != null && old != next) old.done.completeExceptionally(new IllegalStateException("superseded seek"));
        if (audioEos || workersDone.getCount() == 0) {
            if (seekRequest.compareAndSet(next, null)) done.completeExceptionally(new IllegalStateException("decoder has ended"));
        }
        return done;
    }

    public void setPaused(boolean value) {
        if (value) pausedAtUs = presentationTimeUs();
        if (paused.getAndSet(value) && !value) {
            clockOriginUs = pausedAtUs;
            clockOriginFrames = playedFrames.get();
            synchronized (videoLock) { videoLock.notifyAll(); }
        }
    }

    @Override public PlaybackVideoFrame latestVideoFrame() {
        if (closed.get()) return null;
        long clock = presentationTimeUs();
        synchronized (videoLock) {
            boolean consumed = false;
            while (!video.isEmpty() && video.peekFirst().presentationTimeUs() <= clock) {
                lastPresentedFrame = video.removeFirst();
                consumed = true;
            }
            if (consumed) videoLock.notifyAll();
            return lastPresentedFrame;
        }
    }

    @Override public long presentationTimeUs() {
        if (paused.get()) return pausedAtUs;
        long frames = playedFrames.get();
        long elapsed = Math.max(0L, frames - clockOriginFrames);
        return clockOriginUs + (elapsed * 1_000_000L) / Math.max(1L, lastSampleRate.get());
    }

    @Override public void reportAudioOutputPosition(long frames, long sampleRate, long sampledAtNanos) {
        if (closed.get() || sampleRate <= 0) return;
        lastSampleRate.set(sampleRate);
        playedFrames.set(Math.max(0L, frames));
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            audioEos = true;
            videoEos = true;
            pcm.clear(); synchronized (pcm) { currentPcm = null; currentOffset = 0; }
            synchronized (videoLock) { video.clear(); lastPresentedFrame = null; videoLock.notifyAll(); }
            audioWorker.interrupt();
            videoWorker.interrupt();
        }
        awaitCleanupAcknowledgement();
    }

    @Override public CompletableFuture<Void> cleanupAcknowledged() { return cleanupAcknowledged; }

    private void fail(String message, Throwable failure) {
        if (closed.get()) return;
        PlaybackFault fault = new PlaybackFault(FailureReason.UNKNOWN, message);
        if (terminalFault.compareAndSet(null, fault)) faultListeners.forEach(listener -> {
            try { listener.accept(fault); } catch (RuntimeException | LinkageError ignored) {}
        });
        audioEos = true;
        videoEos = true;
        // Release awaitReady immediately; it reports false because terminalFault is set.
        firstAudio.countDown();
        firstVideo.countDown();
        synchronized (videoLock) { videoLock.notifyAll(); }
    }

    private void closeGrabber(FFmpegFrameGrabber grabber) {
        try { grabber.close(); }
        catch (Exception | LinkageError failure) { closeFailure.compareAndSet(null, failure); }
    }

    private void finishWorker() {
        workersDone.countDown();
        if (workersDone.getCount() != 0) return;
        Throwable failure = closeFailure.get();
        if (failure == null) cleanupAcknowledged.complete(null);
        else cleanupAcknowledged.completeExceptionally(failure);
    }

    private void awaitCleanupAcknowledgement() {
        try { cleanupAcknowledged.get(5, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new PlaybackSessionRegistry.CleanupIncompleteException(interrupted, cleanupAcknowledged);
        } catch (java.util.concurrent.TimeoutException | ExecutionException incomplete) {
            Throwable cause = incomplete instanceof ExecutionException && incomplete.getCause() != null
                    ? incomplete.getCause() : incomplete;
            throw new PlaybackSessionRegistry.CleanupIncompleteException(cause, cleanupAcknowledged);
        }
    }

    private void sleepBriefly() {
        try { Thread.sleep(10); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
