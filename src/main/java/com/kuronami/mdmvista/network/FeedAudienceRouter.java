package com.kuronami.mdmvista.network;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;

/** Routes updates to current TV viewers and retains each generation's recipients until its terminal update. */
public final class FeedAudienceRouter {
    private static final Map<UUID, Set<GlobalPos>> TVS_BY_FEED = new ConcurrentHashMap<>();
    private static final Map<GlobalPos, UUID> FEED_BY_TV = new ConcurrentHashMap<>();
    private static final Map<UUID, FeedSourceSnapshot> CURRENT = new ConcurrentHashMap<>();
    private static final Map<UUID, ServerLevel> CURRENT_LEVEL = new ConcurrentHashMap<>();
    private static final AudienceLeaseBook RECIPIENTS = new AudienceLeaseBook();
    private FeedAudienceRouter() {}

    public static void updateTv(GlobalPos tv, UUID feedId) {
        UUID old = FEED_BY_TV.get(tv);
        if (java.util.Objects.equals(old, feedId)) return;
        FEED_BY_TV.remove(tv);
        if (old != null) removeTvFromFeed(old, tv);
        if (feedId == null) return;
        FEED_BY_TV.put(tv, feedId);
        TVS_BY_FEED.computeIfAbsent(feedId, ignored -> ConcurrentHashMap.newKeySet()).add(tv);
    }

    public static void removeTv(GlobalPos tv) {
        UUID old = FEED_BY_TV.remove(tv);
        if (old != null) removeTvFromFeed(old, tv);
    }

    private static void removeTvFromFeed(UUID feed, GlobalPos tv) {
        Set<GlobalPos> locations = TVS_BY_FEED.get(feed);
        if (locations != null) {
            locations.remove(tv);
            if (locations.isEmpty()) TVS_BY_FEED.remove(feed, locations);
        }
    }

    public static void publish(ServerLevel sourceLevel, FeedSourceSnapshot snapshot) {
        FeedSourceSnapshot latest = CURRENT.get(snapshot.feedId());
        boolean staleSameSource = latest != null && CURRENT_LEVEL.get(snapshot.feedId()) == sourceLevel
                && latest.sourceId().equals(snapshot.sourceId())
                && (snapshot.generation() < latest.generation()
                    || (snapshot.generation() == latest.generation() && snapshot.sequence() <= latest.sequence()));
        if (staleSameSource) return;
        boolean supersededTerminal = snapshot.sourceStopped() && latest != null
                && (!latest.sourceId().equals(snapshot.sourceId()) || latest.generation() > snapshot.generation()
                    || (latest.generation() == snapshot.generation() && latest.sequence() >= snapshot.sequence()));
        if (supersededTerminal) {
            var staleKey = new AudienceLeaseBook.SessionKey(snapshot.feedId(), snapshot.sourceId(), snapshot.generation());
            send(sourceLevel, RECIPIENTS.destinations(staleKey, Set.of(), true), snapshot);
            return;
        }
        Set<UUID> currentViewers = new java.util.HashSet<>();
        for (GlobalPos tv : TVS_BY_FEED.getOrDefault(snapshot.feedId(), Set.of())) {
            if (!tv.dimension().equals(sourceLevel.dimension()) || !sourceLevel.isLoaded(tv.pos())) continue;
            for (ServerPlayer player : sourceLevel.getChunkSource().chunkMap.getPlayers(new ChunkPos(tv.pos()), false))
                currentViewers.add(player.getUUID());
        }
        var key = new AudienceLeaseBook.SessionKey(snapshot.feedId(), snapshot.sourceId(), snapshot.generation());
        transitionPrevious(sourceLevel, snapshot);
        RECIPIENTS.advancePrior(key);
        CURRENT.put(snapshot.feedId(), snapshot);
        CURRENT_LEVEL.put(snapshot.feedId(), sourceLevel);
        boolean feedEnded = snapshot.sourceStopped() || isFeedDeparture(snapshot);
        Set<UUID> destinations = RECIPIENTS.destinations(key, currentViewers, feedEnded);
        send(sourceLevel, destinations, snapshot);
    }

    private static void transitionPrevious(ServerLevel sourceLevel, FeedSourceSnapshot snapshot) {
        FeedSourceSnapshot previous = CURRENT.get(snapshot.feedId());
        ServerLevel priorLevel = CURRENT_LEVEL.get(snapshot.feedId());
        if (previous != null && (priorLevel != sourceLevel
                || !previous.sourceId().equals(snapshot.sourceId())
                || previous.generation() != snapshot.generation()) && !previous.sourceStopped()) {
            var priorKey = new AudienceLeaseBook.SessionKey(previous.feedId(), previous.sourceId(), previous.generation());
            if (priorLevel != sourceLevel || !previous.sourceId().equals(snapshot.sourceId())) {
                var priorRecipients = RECIPIENTS.retire(priorKey);
                var terminal = new FeedSourceSnapshot(previous.feedId(), previous.sourceId(), previous.generation(),
                        MdmVistaServerSync.nextSequenceForSource(previous.sourceId()), previous.sourcePos(),
                        previous.track(), previous.canonicalVideoUrl(),
                        previous.startOffsetMs(), previous.rangeBlocks(), previous.volumePercent(), previous.directional(),
                        previous.serverPlaybackTimeMs(), false, false, true, false);
                if (priorLevel != null) send(priorLevel, priorRecipients, terminal);
            }
        }
    }

    private static void send(ServerLevel sourceLevel, Set<UUID> playerIds, FeedSourceSnapshot snapshot) {
        for (UUID playerId : playerIds) {
            ServerPlayer player = sourceLevel.getServer().getPlayerList().getPlayer(playerId);
            if (player != null && player.serverLevel() == sourceLevel)
                PacketDistributor.sendToPlayer(player, new FeedSourcePayload(snapshot));
        }
    }

    /** Bound retained audience memory when a player leaves or changes worlds. */
    public static void removeRecipient(UUID playerId) {
        RECIPIENTS.removeRecipient(playerId);
    }

    public static FeedSourceSnapshot current(UUID feed) { return CURRENT.get(feed); }

    /** Current and retained recipients for feeds that still point at this exact source. */
    public static Set<UUID> viewersForSource(ServerLevel sourceLevel, UUID sourceId) {
        Set<UUID> result = new java.util.HashSet<>();
        for (Map.Entry<UUID, FeedSourceSnapshot> entry : CURRENT.entrySet()) {
            FeedSourceSnapshot snapshot = entry.getValue();
            if (!snapshot.sourceId().equals(sourceId) || snapshot.sourceStopped() || isFeedDeparture(snapshot)) continue;
            UUID feedId = entry.getKey();
            if (CURRENT_LEVEL.get(feedId) == sourceLevel)
                collectFeedViewers(sourceLevel, feedId, sourceId, snapshot.generation(), result);
        }
        return Set.copyOf(result);
    }

    /** Includes newly linked feeds before their first one-second snapshot is published. */
    public static Set<UUID> viewersForFeeds(ServerLevel sourceLevel, UUID sourceId,
            long sourceGeneration, Set<UUID> feedIds) {
        Set<UUID> result = new java.util.HashSet<>();
        for (UUID feedId : feedIds) {
            FeedSourceSnapshot snapshot = CURRENT.get(feedId);
            if (snapshot != null && (!snapshot.sourceId().equals(sourceId)
                    || CURRENT_LEVEL.get(feedId) != sourceLevel)) snapshot = null;
            collectFeedViewers(sourceLevel, feedId, sourceId, sourceGeneration, result);
        }
        return Set.copyOf(result);
    }

    private static void collectFeedViewers(ServerLevel sourceLevel, UUID feedId, UUID sourceId,
            long generation, Set<UUID> result) {
        for (GlobalPos tv : TVS_BY_FEED.getOrDefault(feedId, Set.of())) {
            if (!tv.dimension().equals(sourceLevel.dimension()) || !sourceLevel.isLoaded(tv.pos())) continue;
            for (ServerPlayer player : sourceLevel.getChunkSource().chunkMap.getPlayers(new ChunkPos(tv.pos()), false))
                result.add(player.getUUID());
        }
        if (generation >= 0L) for (UUID playerId : RECIPIENTS.viewersForSource(
                feedId, sourceId, generation)) {
            ServerPlayer player = sourceLevel.getServer().getPlayerList().getPlayer(playerId);
            if (player != null && player.serverLevel() == sourceLevel) result.add(playerId);
        }
    }

    private static boolean isFeedDeparture(FeedSourceSnapshot snapshot) {
        return !snapshot.active() && !snapshot.paused() && snapshot.stopPlayback() && !snapshot.sourceStopped();
    }

    /** Sends pre-play metadata only to the exact recipient admitted by core playback. */
    public static void publishTo(ServerLevel sourceLevel, UUID feedId, FeedSourceSnapshot snapshot,
            ServerPlayer recipient) {
        if (recipient.serverLevel() != sourceLevel) return;
        FeedSourceSnapshot previous = CURRENT.get(feedId);
        if (previous != null && CURRENT_LEVEL.get(feedId) == sourceLevel
                && previous.sourceId().equals(snapshot.sourceId())
                && previous.generation() > snapshot.generation()) return;
        boolean currentViewer = isViewerForFeed(sourceLevel, feedId, recipient.getUUID());
        boolean retained = RECIPIENTS.viewersForSource(feedId, snapshot.sourceId(), snapshot.generation())
                .contains(recipient.getUUID());
        if (!currentViewer && !retained) return;
        transitionPrevious(sourceLevel, snapshot);
        RECIPIENTS.advancePrior(new AudienceLeaseBook.SessionKey(
                feedId, snapshot.sourceId(), snapshot.generation()));
        CURRENT.put(feedId, snapshot);
        CURRENT_LEVEL.put(feedId, sourceLevel);
        MdmVistaServerSync.recordPrePlaySnapshot(sourceLevel, snapshot);
        RECIPIENTS.destinations(new AudienceLeaseBook.SessionKey(
                feedId, snapshot.sourceId(), snapshot.generation()), Set.of(recipient.getUUID()), false);
        PacketDistributor.sendToPlayer(recipient, new FeedSourcePayload(snapshot));
    }

    private static boolean isViewerForFeed(ServerLevel sourceLevel, UUID feedId, UUID playerId) {
        for (GlobalPos tv : TVS_BY_FEED.getOrDefault(feedId, Set.of())) {
            if (!tv.dimension().equals(sourceLevel.dimension()) || !sourceLevel.isLoaded(tv.pos())) continue;
            if (sourceLevel.getChunkSource().chunkMap.getPlayers(new ChunkPos(tv.pos()), false).stream()
                    .anyMatch(player -> player.getUUID().equals(playerId))) return true;
        }
        return false;
    }
    public static void forgetFeed(UUID feed) {
        CURRENT.remove(feed);
        CURRENT_LEVEL.remove(feed);
        TVS_BY_FEED.remove(feed);
        RECIPIENTS.removeFeed(feed);
    }
    public static void clear() { TVS_BY_FEED.clear(); FEED_BY_TV.clear(); CURRENT.clear(); CURRENT_LEVEL.clear(); RECIPIENTS.clear(); }
}
