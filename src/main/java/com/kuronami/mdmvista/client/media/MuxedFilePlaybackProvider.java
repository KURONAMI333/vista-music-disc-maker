package com.kuronami.mdmvista.client.media;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import com.kuronami.mdmvista.client.ClientFeedSessions;
import com.kuronami.musicdiscmaker.client.audio.PlaybackMediaSession;
import com.kuronami.musicdiscmaker.client.audio.PlaybackSessionProvider;
import com.kuronami.musicdiscmaker.client.audio.PlaybackSessionRequest;
import com.kuronami.musicdiscmaker.client.audio.PlaybackSourceContext;
import com.kuronami.musicdiscmaker.component.CustomTrackData;

/** MDM provider for exact, pre-existing managed muxed files. Acquisition is deliberately a separate stage. */
public final class MuxedFilePlaybackProvider implements PlaybackSessionProvider {
    private static final System.Logger LOGGER = System.getLogger(MuxedFilePlaybackProvider.class.getName());
    private static final long STREAM_READY_TIMEOUT_MS = 15_000L;
    private static final long CLEANUP_WAIT_MS = 20_000L;
    private final Path cacheRoot;
    private final MuxedFileIndex index;
    private final ManagedYoutubeAcquirer acquirer;

    public MuxedFilePlaybackProvider(Path managedCacheRoot, MuxedFileIndex index) throws Exception {
        this.cacheRoot = managedCacheRoot.toRealPath();
        this.index = java.util.Objects.requireNonNull(index, "index");
        this.acquirer = index instanceof ManagedMuxedFileIndex managedIndex
                ? new ManagedYoutubeAcquirer(cacheRoot, managedIndex) : null;
    }

    @Override public boolean selects(PlaybackSourceContext source, CustomTrackData track) {
        UUID sourceId = source.sourceId();
        String videoUrl = sourceId == null || track == null ? null
                : ClientFeedSessions.videoUrlForSource(sourceId, currentGeneration(sourceId), track.sourceKey());
        return sourceId != null && track != null && !track.isEmpty()
                && com.kuronami.mdmvista.network.MuxedAvEligibility.accepts(
                        track.isLocal(), track.radio(), videoUrl);
    }

    @Override public PlaybackMediaSession open(PlaybackSessionRequest request) throws Exception {
        UUID sourceId = request.source().sourceId();
        if (sourceId == null || !request.id().sourceId().equals(sourceId.toString())) return null;
        String videoUrl = ClientFeedSessions.videoUrlForSource(sourceId, request.id().generation(), request.track().sourceKey());
        if (request.track().isEmpty() || !com.kuronami.mdmvista.network.MuxedAvEligibility.accepts(
                request.track().isLocal(), request.track().radio(), videoUrl)) return null;
        java.util.function.BooleanSupplier current = () -> videoUrl.equals(ClientFeedSessions.videoUrlForSource(
                sourceId, request.id().generation(), request.track().sourceKey()));
        if (!current.getAsBoolean()) return null;
        ManagedNativeRuntime.ensure(cacheRoot.resolveSibling("native-cache"), current);
        if (!current.getAsBoolean()) return null;
        JavaCvMuxedSession opened = null;
        Path candidate = index.resolve(sourceId, videoUrl).orElse(null);
        if (candidate != null) {
            opened = new JavaCvMuxedSession(validatedMedia(candidate), request.startOffsetMs());
        } else if (acquirer != null) {
            ManagedYoutubeAcquirer.MediaStreams streams = null;
            try {
                streams = acquirer.resolveStreams(videoUrl, current);
            } catch (Exception | LinkageError failure) {
                LOGGER.log(System.Logger.Level.WARNING, "YouTube stream resolution failed; using local cache");
            }
            if (streams != null && current.getAsBoolean()) {
                JavaCvMuxedSession remote = null;
                try {
                    remote = JavaCvMuxedSession.streaming(streams.videoUrl(), streams.audioUrl(),
                            request.startOffsetMs());
                    boolean ready;
                    try {
                        ready = remote.awaitReady(STREAM_READY_TIMEOUT_MS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        ready = false;
                    }
                    if (ready && current.getAsBoolean()) {
                        opened = remote;
                    } else {
                        closeRemote(remote);
                    }
                } catch (RuntimeException cleanupFailure) {
                    throw cleanupFailure;
                } catch (Exception | LinkageError failure) {
                    LOGGER.log(System.Logger.Level.WARNING, "YouTube stream startup failed; using local cache");
                    if (remote != null) closeRemote(remote);
                }
            }
            if (opened == null) {
                candidate = acquirer.acquire(sourceId, request.id().generation(), request.track().sourceKey(),
                        videoUrl, current);
                if (candidate != null)
                    opened = new JavaCvMuxedSession(validatedMedia(candidate), request.startOffsetMs());
            }
        }
        if (opened == null) return null;
        JavaCvMuxedSession session = opened;
        if (!current.getAsBoolean()) {
            session.close();
            return null;
        }
        if (!ClientFeedSessions.bind(sourceId, request.id())) {
            try { session.close(); }
            catch (com.kuronami.musicdiscmaker.client.audio.PlaybackSessionRegistry.CleanupIncompleteException cleanupFailure) {
                throw cleanupFailure;
            } catch (RuntimeException cleanupFailure) {
                throw new com.kuronami.musicdiscmaker.client.audio.PlaybackSessionRegistry.CleanupIncompleteException(
                        cleanupFailure, session.cleanupAcknowledged());
            }
            return null;
        }
        return new PlaybackMediaSession() {
            private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
            @Override public com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource audioSource() { return session.audioSource(); }
            @Override public com.kuronami.musicdiscmaker.client.audio.PlaybackVideoFrame latestVideoFrame() { return session.latestVideoFrame(); }
            @Override public long presentationTimeUs() { return session.presentationTimeUs(); }
            @Override public void reportAudioOutputPosition(long frames, long rate, long nanos) { session.reportAudioOutputPosition(frames,rate,nanos); }
            @Override public java.util.concurrent.CompletableFuture<Void> cleanupAcknowledged() { return session.cleanupAcknowledged(); }
            @Override public void close() {
                if (!closed.compareAndSet(false,true)) return;
                try { session.close(); } finally { ClientFeedSessions.unbind(sourceId, request.id()); }
            }
        };
    }

    /**
     * Closes a streaming session. If FFmpeg is stuck in a native read, close() reports an incomplete cleanup;
     * wait for the acknowledgement, and only rethrow when cleanup truly cannot finish.
     */
    private static void closeRemote(JavaCvMuxedSession remote) {
        try {
            remote.close();
        } catch (com.kuronami.musicdiscmaker.client.audio.PlaybackSessionRegistry.CleanupIncompleteException incomplete) {
            try {
                remote.cleanupAcknowledged().get(CLEANUP_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw incomplete;
            } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException unresolved) {
                throw incomplete;
            }
        }
    }

    private Path validatedMedia(Path candidate) throws Exception {
        Path media = candidate.toRealPath();
        if (!media.startsWith(cacheRoot) || !Files.isRegularFile(media))
            throw new SecurityException("managed AV path escaped the configured cache");
        return media;
    }

    private static long currentGeneration(UUID sourceId) {
        var snapshot = ClientFeedSessions.source(sourceId);
        return snapshot == null ? -1L : snapshot.generation();
    }
}
