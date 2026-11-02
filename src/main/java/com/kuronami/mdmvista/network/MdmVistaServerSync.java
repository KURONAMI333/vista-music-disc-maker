package com.kuronami.mdmvista.network;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.lang.reflect.Proxy;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.mehvahdjukaar.vista.common.broadcast.BroadcastManager;
import net.mehvahdjukaar.vista.common.broadcast.IBroadcastLocation;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

/** Publishes source playback to TV-tracking clients, including clients outside the source chunk. */
public final class MdmVistaServerSync {
    private static final System.Logger LOGGER = System.getLogger(MdmVistaServerSync.class.getName());
    private static final Map<UUID, FeedSourceSnapshot> LAST_BY_FEED = new HashMap<>();
    private static final Map<UUID, ResourceKey<Level>> LEVEL_BY_FEED = new HashMap<>();
    private static final Map<UUID, Long> SEQUENCE_BY_SOURCE = new HashMap<>();
    private static final Set<UUID> KNOWN_LINKS = ConcurrentHashMap.newKeySet();
    private static AutoCloseable audienceRegistration;
    private MdmVistaServerSync() {}

    public static void register() {
        NeoForge.EVENT_BUS.register(MdmVistaServerSync.class);
        registerOptionalAudienceProvider();
    }

    private static void registerOptionalAudienceProvider() {
        try {
            ClassLoader loader = MdmVistaServerSync.class.getClassLoader();
            Class<?> providerType = Class.forName(
                    "com.kuronami.musicdiscmaker.event.SourcePlaybackAudienceProvider", false, loader);
            Class<?> registryType = Class.forName(
                    "com.kuronami.musicdiscmaker.event.SourcePlaybackAudienceRegistry", true, loader);
            providerType.getMethod("viewers", GoldenJukeboxBlockEntity.class);
            providerType.getMethod("beforeCorePlay", GoldenJukeboxBlockEntity.class, ServerPlayer.class);
            registryType.getMethod("beforeCorePlay", GoldenJukeboxBlockEntity.class, ServerPlayer.class);
            var register = registryType.getMethod("register", providerType);
            if (register.getReturnType() != AutoCloseable.class) return;
            Object provider = Proxy.newProxyInstance(loader, new Class<?>[] { providerType }, (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "viewers" -> args != null && args.length == 1
                            && args[0] instanceof GoldenJukeboxBlockEntity source ? viewers(source) : Set.of();
                    case "beforeCorePlay" -> {
                        if (args != null && args.length == 2
                                && args[0] instanceof GoldenJukeboxBlockEntity source
                                && args[1] instanceof ServerPlayer player) publishImmediatelyTo(source, player);
                        yield null;
                    }
                    case "toString" -> "MdmVistaSourceAudienceProvider";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> args != null && args.length == 1 && proxy == args[0];
                    default -> null;
                };
            });
            audienceRegistration = (AutoCloseable) register.invoke(null, provider);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) {
            LOGGER.log(System.Logger.Level.DEBUG,
                    "MDM optional audience API unavailable; Vista playback routing remains dormant", unavailable);
        }
    }

    private static Set<UUID> viewers(GoldenJukeboxBlockEntity source) {
        if (!(source.getLevel() instanceof ServerLevel sourceLevel)) return Set.of();
        CustomTrackData track = source.currentTrack();
        String url = videoUrl(source, track);
        if (source.isStopped() || source.isPaused() || track == null || track.isEmpty()
                || !MuxedAvEligibility.accepts(track.isLocal(), track.radio(), url)) return Set.of();
        UUID sourceId = source.peekSourceIdentity();
        if (sourceId == null) sourceId = source.sourceIdentity();
        Set<UUID> feeds = linkedFeeds(sourceLevel, source);
        return feeds.isEmpty() ? Set.of() : FeedAudienceRouter.viewersForFeeds(
                sourceLevel, sourceId, source.playbackIdentity().generation(), feeds);
    }

    private static Set<UUID> linkedFeeds(ServerLevel sourceLevel, GoldenJukeboxBlockEntity source) {
        Set<UUID> result = new HashSet<>();
        BroadcastManager manager = manager(sourceLevel);
        if (manager == null) return Set.of();
        for (Map.Entry<UUID, IBroadcastLocation> link : manager.getAll()) {
            IBroadcastLocation location = link.getValue();
            if (location == null) continue;
            var resolved = location.get(false);
            if (resolved.isValid() && resolved.getValue() == source) result.add(link.getKey());
        }
        return Set.copyOf(result);
    }

    private static void publishImmediatelyTo(GoldenJukeboxBlockEntity source, ServerPlayer player) {
        if (!(source.getLevel() instanceof ServerLevel sourceLevel) || player.serverLevel() != sourceLevel) return;
        CustomTrackData track = source.currentTrack();
        String videoUrl = videoUrl(source, track);
        if (source.isStopped() || source.isPaused() || track == null || track.isEmpty()
                || !MuxedAvEligibility.accepts(track.isLocal(), track.radio(), videoUrl)) return;
        MinecraftServer server = sourceLevel.getServer();
        for (UUID feedId : linkedFeeds(sourceLevel, source)) {
            FeedSourceSnapshot next = snapshot(server, feedId, source,
                    Math.max(0L, source.currentElapsedMs()));
            FeedAudienceRouter.publishTo(sourceLevel, feedId, next, player);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 20 != 0) return;
        Set<UUID> seen = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) publishLevel(server, level, seen);
        for (UUID feedId : Set.copyOf(LAST_BY_FEED.keySet()))
            if (!seen.contains(feedId)) publishStop(server, feedId);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        // The provider is mod-lifetime and stateless; only per-server routing state is reset here.
        LAST_BY_FEED.clear(); LEVEL_BY_FEED.clear(); SEQUENCE_BY_SOURCE.clear(); KNOWN_LINKS.clear(); FeedAudienceRouter.clear();
    }

    /** Server-side truth used to keep Vista from pinning an MDM source chunk. */
    public static boolean isLinked(UUID feedId) {
        return feedId != null && KNOWN_LINKS.contains(feedId);
    }

    /** Tells every client whether a feed is MDM-owned before its first TV tick. */
    public static void broadcastLink(ServerLevel level, UUID feedId, boolean linked) {
        if (level == null || feedId == null) return;
        MinecraftServer server = level.getServer();
        if (server == null) return;
        boolean changed = linked ? KNOWN_LINKS.add(feedId) : KNOWN_LINKS.remove(feedId);
        if (!changed) return;
        FeedLinkedPayload payload = new FeedLinkedPayload(feedId, linked);
        for (ServerPlayer player : server.getPlayerList().getPlayers())
            PacketDistributor.sendToPlayer(player, payload);
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        Set<UUID> replay = new HashSet<>(KNOWN_LINKS);
        for (ServerLevel level : server.getAllLevels()) {
            BroadcastManager manager = manager(level);
            if (manager == null) continue;
            for (Map.Entry<UUID, IBroadcastLocation> link : manager.getAll()) {
                if (manager.getBroadcast(link.getKey(), false) instanceof GoldenJukeboxBlockEntity)
                    replay.add(link.getKey());
            }
        }
        for (UUID feedId : replay) {
            KNOWN_LINKS.add(feedId);
            PacketDistributor.sendToPlayer(player, new FeedLinkedPayload(feedId, true));
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        FeedAudienceRouter.removeRecipient(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        FeedAudienceRouter.removeRecipient(event.getEntity().getUUID());
    }

    /** Called immediately before MDM emits its normal PlayDisc for this same generation. */
    public static void publishImmediately(GoldenJukeboxBlockEntity source, long startOffsetMs) {
        if (!(source.getLevel() instanceof ServerLevel serverLevel)) return;
        MinecraftServer server = serverLevel.getServer();
        UUID sourceId = source.peekSourceIdentity();
        if (sourceId == null) sourceId = source.sourceIdentity();
        for (UUID feedId : linkedFeeds(serverLevel, source)) {
            publish(serverLevel, feedId, snapshot(server, feedId, source, startOffsetMs), true);
        }
    }

    /** Push a terminal/source-state update after pause, seek, stop, or block removal. */
    public static void publishImmediately(GoldenJukeboxBlockEntity source) {
        publishImmediately(source, Math.max(0L, source.currentElapsedMs()));
    }

    private static void publishLevel(MinecraftServer server, ServerLevel level, Set<UUID> seen) {
        BroadcastManager manager = manager(level);
        if (manager == null) return;
        for (Map.Entry<UUID, IBroadcastLocation> linked : manager.getAll()) {
            UUID feedId = linked.getKey();
            IBroadcastLocation location = linked.getValue();
            if (location == null) { publishStop(server, feedId); continue; }
            var sendAt = location.getChunkSendPosition();
            if (sendAt == null || !sendAt.dimension().equals(level.dimension())) continue;
            seen.add(feedId);
            var resolved = location.get(false); // Do not force-load an MDM source chunk.
            if (!resolved.isValid() || !(resolved.getValue() instanceof GoldenJukeboxBlockEntity source)) {
                publishStop(server, feedId);
                continue;
            }
            if (source.getLevel() != level) { publishStop(server, feedId); continue; }
            UUID sourceId = source.peekSourceIdentity();
            if (sourceId == null) { publishStop(server, feedId); continue; }
            FeedSourceSnapshot next = snapshot(server, feedId, source, Math.max(0L, source.currentElapsedMs()));
            // A one-second active update reattaches late TV viewers to the current source clock.
            publish(level, feedId, next, next.active());
        }
    }

    private static BroadcastManager manager(ServerLevel level) {
        try { return BroadcastManager.getInstance(level); }
        catch (RuntimeException | LinkageError unavailable) {
            LOGGER.log(System.Logger.Level.DEBUG, "Vista feed manager not ready for " + level.dimension(), unavailable);
            return null;
        }
    }

    private static FeedSourceSnapshot snapshot(MinecraftServer server, UUID feedId,
            GoldenJukeboxBlockEntity source, long offsetMs) {
        UUID sourceId = source.peekSourceIdentity();
        if (sourceId == null) sourceId = source.sourceIdentity();
        var identity = source.playbackIdentity();
        boolean sourcePlaying = !source.isStopped();
        CustomTrackData track = source.currentTrack();
        boolean paused = sourcePlaying && source.isPaused();
        String videoUrl = videoUrl(source, track);
        boolean active = sourcePlaying && !paused && track != null && !track.isEmpty()
                && MuxedAvEligibility.accepts(track.isLocal(), track.radio(), videoUrl);
        return new FeedSourceSnapshot(feedId, sourceId, identity.generation(), nextSequence(sourceId),
                source.getBlockPos(), track, videoUrl, Math.max(0L, offsetMs), source.getRangeBlocks(),
                source.getVolumePercent(), source.isDirectional(), server.getTickCount() * 50L, active, paused,
                !sourcePlaying || paused, !sourcePlaying);
    }

    private static long nextSequence(UUID sourceId) {
        return SEQUENCE_BY_SOURCE.merge(sourceId, 1L, Long::sum);
    }

    static long nextSequenceForSource(UUID sourceId) { return nextSequence(sourceId); }

    static void recordPrePlaySnapshot(ServerLevel sourceLevel, FeedSourceSnapshot snapshot) {
        LAST_BY_FEED.put(snapshot.feedId(), snapshot);
        LEVEL_BY_FEED.put(snapshot.feedId(), sourceLevel.dimension());
    }

    private static void publish(ServerLevel sourceLevel, UUID feedId, FeedSourceSnapshot next, boolean heartbeat) {
        FeedSourceSnapshot previous = LAST_BY_FEED.get(feedId);
        if (!heartbeat && equivalentState(previous, next)) return;
        LAST_BY_FEED.put(feedId, next);
        LEVEL_BY_FEED.put(feedId, sourceLevel.dimension());
        FeedAudienceRouter.publish(sourceLevel, next);
    }

    private static boolean equivalentState(FeedSourceSnapshot previous, FeedSourceSnapshot next) {
        return previous != null && previous.sourceId().equals(next.sourceId())
                && previous.generation() == next.generation()
                && previous.trackIdentity().equals(next.trackIdentity())
                && previous.active() == next.active() && previous.paused() == next.paused()
                && previous.stopPlayback() == next.stopPlayback()
                && previous.sourceStopped() == next.sourceStopped()
                && previous.canonicalVideoUrl().equals(next.canonicalVideoUrl());
    }

    private static void publishStop(MinecraftServer server, UUID feedId) {
        FeedSourceSnapshot previous = LAST_BY_FEED.get(feedId);
        if (previous == null || previous.sourceStopped()) return;
        ResourceKey<Level> sourceDimension = LEVEL_BY_FEED.get(feedId);
        ServerLevel sourceLevel = sourceDimension == null ? null : server.getLevel(sourceDimension);
        if (sourceLevel == null) return;
        FeedSourceSnapshot stopped = new FeedSourceSnapshot(feedId, previous.sourceId(), previous.generation(),
                nextSequence(previous.sourceId()), previous.sourcePos(), previous.track(), previous.canonicalVideoUrl(),
                previous.startOffsetMs(), previous.rangeBlocks(), previous.volumePercent(), previous.directional(),
                server.getTickCount() * 50L, false, false, true, false);
        publish(sourceLevel, feedId, stopped, true);
    }

    private static String videoUrl(GoldenJukeboxBlockEntity source, CustomTrackData track) {
        ItemStack disc = source.effectiveDisc();
        CustomData customData = disc.get(DataComponents.CUSTOM_DATA);
        if (customData != null) {
            String original = YoutubeVideoUrls.canonicalVideoUrl(
                    customData.copyTag().getString("music_disc_maker:original_source_url"));
            if (!original.isEmpty()) return original;
        }
        return track == null ? "" : YoutubeVideoUrls.canonicalVideoUrl(track.url());
    }
}
