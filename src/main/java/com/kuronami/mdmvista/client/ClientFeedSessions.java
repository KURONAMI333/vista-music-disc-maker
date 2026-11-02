package com.kuronami.mdmvista.client;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import com.kuronami.mdmvista.network.FeedSourceSnapshot;
import com.kuronami.musicdiscmaker.client.audio.PlaybackSessionId;

/** Client mapping between Vista feed IDs and one monotonic MDM source generation. */
public final class ClientFeedSessions {
    private static final Object LOCK = new Object();
    private static final ConcurrentHashMap<UUID, FeedSourceSnapshot> FEEDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, FeedSourceSnapshot> SOURCES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, PlaybackSessionId> SESSIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, String> SESSION_TRACKS = new ConcurrentHashMap<>();
    private static volatile Consumer<UUID> feedRemoval = ignored -> {};
    private ClientFeedSessions() {}

    /**
     * Applies only monotonic server snapshots. Delayed feed updates/stops cannot roll a source backwards;
     * same-source feeds are normalized to one authoritative generation and track identity; delayed
     * lower/equal updates cannot roll metadata backwards. The return value reports whether state advanced.
     */
    public static boolean update(FeedSourceSnapshot snapshot) {
        java.util.Objects.requireNonNull(snapshot, "snapshot");
        synchronized (LOCK) {
            FeedSourceSnapshot current = SOURCES.get(snapshot.sourceId());
            if (current != null) {
                if (snapshot.generation() < current.generation()) return false;
                if (snapshot.generation() == current.generation()) {
                    // Album track changes can happen inside one source generation; sequence is
                    // monotonic per source, so a newer track identity is authoritative.
                    if (!current.trackIdentity().equals(snapshot.trackIdentity())
                            && snapshot.sequence() <= current.sequence()) return false;
                    if (snapshot.sequence() < current.sequence()) {
                        if (current.active()) FEEDS.put(snapshot.feedId(), forFeed(current, snapshot.feedId()));
                        return false;
                    }
                    if (snapshot.sequence() == current.sequence()) {
                        if (current.active()) FEEDS.put(snapshot.feedId(), forFeed(current, snapshot.feedId()));
                        return false;
                    }
                }
            }

            if (isFeedDeparture(snapshot)) {
                FeedSourceSnapshot removed = FEEDS.remove(snapshot.feedId());
                if (removed != null) feedRemoval.accept(snapshot.feedId());
                boolean anotherFeedRemains = FEEDS.values().stream()
                        .anyMatch(feed -> feed.sourceId().equals(snapshot.sourceId()));
                if (anotherFeedRemains) return false;
                SOURCES.put(snapshot.sourceId(), snapshot);
                return true;
            }
            SOURCES.put(snapshot.sourceId(), snapshot);
            if (snapshot.sourceStopped()) {
                FEEDS.entrySet().removeIf(entry -> {
                    if (!entry.getValue().sourceId().equals(snapshot.sourceId())) return false;
                    feedRemoval.accept(entry.getKey());
                    return true;
                });
                return true;
            }
            FeedSourceSnapshot oldFeed = FEEDS.get(snapshot.feedId());
            if (oldFeed != null && !oldFeed.sourceId().equals(snapshot.sourceId())) {
                FEEDS.remove(snapshot.feedId(), oldFeed);
                feedRemoval.accept(snapshot.feedId());
            }
            for (Map.Entry<UUID, FeedSourceSnapshot> entry : FEEDS.entrySet()) {
                if (entry.getValue().sourceId().equals(snapshot.sourceId()))
                    FEEDS.replace(entry.getKey(), entry.getValue(), forFeed(snapshot, entry.getKey()));
            }
            FEEDS.put(snapshot.feedId(), snapshot);
            return true;
        }
    }

    private static boolean isFeedDeparture(FeedSourceSnapshot snapshot) {
        return !snapshot.active() && !snapshot.paused() && snapshot.stopPlayback() && !snapshot.sourceStopped();
    }

    private static FeedSourceSnapshot forFeed(FeedSourceSnapshot s, UUID feedId) {
        return new FeedSourceSnapshot(feedId, s.sourceId(), s.generation(), s.sequence(), s.sourcePos(), s.track(),
                s.canonicalVideoUrl(), s.startOffsetMs(), s.rangeBlocks(), s.volumePercent(), s.directional(),
                s.serverPlaybackTimeMs(), s.active(), s.paused(), s.stopPlayback(), s.sourceStopped());
    }

    public static FeedSourceSnapshot feed(UUID feedId) {
        synchronized (LOCK) { return feedId == null ? null : FEEDS.get(feedId); }
    }
    /** Feed snapshot for a feed ID, or the source snapshot when the ID is a source ID; null when unknown. */
    public static FeedSourceSnapshot snapshotFor(UUID feedId) {
        synchronized (LOCK) {
            if (feedId == null) return null;
            FeedSourceSnapshot feed = FEEDS.get(feedId);
            return feed != null ? feed : SOURCES.get(feedId);
        }
    }
    public static FeedSourceSnapshot source(UUID sourceId) {
        synchronized (LOCK) { return sourceId == null ? null : SOURCES.get(sourceId); }
    }
    public static UUID sourceForFeed(UUID feedId) {
        FeedSourceSnapshot snapshot = feed(feedId);
        return snapshot == null ? null : snapshot.sourceId();
    }
    public static String videoUrlForSource(UUID sourceId, long generation, String trackIdentity) {
        FeedSourceSnapshot snapshot = source(sourceId);
        return snapshot != null && snapshot.active() && !snapshot.paused()
                && snapshot.generation() == generation && snapshot.trackIdentity().equals(trackIdentity)
                && !snapshot.canonicalVideoUrl().isBlank() ? snapshot.canonicalVideoUrl() : null;
    }
    public static boolean bind(UUID sourceId, PlaybackSessionId session) {
        if (sourceId == null || session == null || !sourceId.toString().equals(session.sourceId())) return false;
        synchronized (LOCK) {
            FeedSourceSnapshot current = SOURCES.get(sourceId);
            if (current == null || !current.active() || current.paused()
                    || current.generation() != session.generation()) return false;
            PlaybackSessionId existing = SESSIONS.get(sourceId);
            if (existing != null && (existing.generation() > session.generation()
                    || (existing.generation() == session.generation()
                        && existing.localGeneration() > session.localGeneration()))) return false;
            SESSIONS.put(sourceId, session);
            SESSION_TRACKS.put(sourceId, current.trackIdentity());
            return true;
        }
    }
    public static PlaybackSessionId get(UUID feedId) {
        synchronized (LOCK) {
            if (feedId == null) return null;
            FeedSourceSnapshot feed = FEEDS.get(feedId);
            if (feed == null) feed = SOURCES.get(feedId);
            if (feed == null || !feed.active() || feed.paused()) return null;
            PlaybackSessionId session = SESSIONS.get(feed.sourceId());
            return session != null && session.generation() == feed.generation()
                    && feed.trackIdentity().equals(SESSION_TRACKS.get(feed.sourceId())) ? session : null;
        }
    }
    public static void unbind(UUID sourceId, PlaybackSessionId expected) {
        synchronized (LOCK) {
            if (sourceId != null && expected != null && SESSIONS.remove(sourceId, expected))
                SESSION_TRACKS.remove(sourceId);
        }
    }
    public static void setFeedRemovalHandler(Consumer<UUID> handler) {
        feedRemoval = java.util.Objects.requireNonNull(handler, "handler");
    }
    public static void clear() {
        synchronized (LOCK) {
            FEEDS.keySet().forEach(feedRemoval);
            FEEDS.clear(); SOURCES.clear(); SESSIONS.clear(); SESSION_TRACKS.clear();
        }
    }
}
