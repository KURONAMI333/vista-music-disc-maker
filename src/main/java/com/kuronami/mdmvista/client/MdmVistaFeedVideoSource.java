package com.kuronami.mdmvista.client;

import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.kuronami.mdmvista.network.FeedSourceSnapshot;
import com.kuronami.musicdiscmaker.client.audio.PlaybackSessionId;
import com.kuronami.musicdiscmaker.client.audio.PlaybackVideoFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.mehvahdjukaar.moonlight.api.util.math.Vec2i;
import net.mehvahdjukaar.vista.client.CrtOverlay;
import net.mehvahdjukaar.vista.client.textures.TvScreenVertexConsumers;
import net.mehvahdjukaar.vista.client.video_source.IVideoSource;
import net.mehvahdjukaar.vista.client.video_source.BroadcastVideoSource;
import net.mehvahdjukaar.vista.common.tv.IntAnimationState;
import net.mehvahdjukaar.vista.common.tv.TVBlockEntity;

/** Vista screen-only adapter. MDM remains the only audio owner; this class never starts TV audio. */
public final class MdmVistaFeedVideoSource implements IVideoSource {
    public static final MdmVistaFeedVideoSource INSTANCE = new MdmVistaFeedVideoSource(null);
    private static final ConcurrentHashMap<UUID, MdmVistaFeedVideoSource> LINKED_FEEDS = new ConcurrentHashMap<>();
    private record Uploaded(ResourceLocation id, DynamicTexture texture, int width, int height, long ptsUs,
            String trackKey) {}
    private final ConcurrentHashMap<UUID, Uploaded> uploaded = new ConcurrentHashMap<>();
    private final UUID linkedFeed;
    private MdmVistaFeedVideoSource(UUID linkedFeed) { this.linkedFeed = linkedFeed; }
    public static MdmVistaFeedVideoSource getInstance() { return INSTANCE; }
    public static MdmVistaFeedVideoSource forLinkedFeed(UUID feed) {
        return feed == null ? INSTANCE : LINKED_FEEDS.computeIfAbsent(feed, MdmVistaFeedVideoSource::new);
    }

    // Parameter order follows Vista 5.5.7 TvBlockEntityRenderer: the 7th argument is the TV's playback ticks
    // and the 8th is TVBlockEntity.isPaused().
    @Override public VertexConsumer getVideoFrameBuilder(TVBlockEntity tv, float partialTick,
            MultiBufferSource buffers, boolean isOn, Vec2i screenSize, Vec2i screenPixels, int playbackTicks,
            boolean paused, IntAnimationState fade, IntAnimationState channelFade, boolean hasEnergy) {
        UUID feed = linkedFeed == null ? resolveFeedId(tv) : linkedFeed;
        if (!isOn || feed == null) return IVideoSource.EMPTY.getVideoFrameBuilder(tv, partialTick, buffers, isOn,
                screenSize, screenPixels, playbackTicks, paused, fade, channelFade, hasEnergy);
        FeedSourceSnapshot snapshot = ClientFeedSessions.snapshotFor(feed);
        Uploaded last = uploaded.get(feed);
        Uploaded held = last != null && snapshot != null && last.trackKey().equals(trackKey(snapshot)) ? last : null;
        // A paused feed keeps its last frame of the same track as a still image. CrtOverlay.PAUSE is not used:
        // Vista pairs it with a VHS pause shader that jitters and wobbles the picture.
        if (paused || (snapshot != null && snapshot.paused())) {
            if (held != null) return TvScreenVertexConsumers.getSingleTextureVC(buffers, held.id(),
                    CrtOverlay.NONE, screenSize, fade, channelFade);
            if (paused) return IVideoSource.EMPTY.getVideoFrameBuilder(tv, partialTick, buffers, isOn,
                    screenSize, screenPixels, playbackTicks, paused, fade, channelFade, hasEnergy);
            return TvScreenVertexConsumers.getWaitingVc(buffers, screenSize, playbackTicks, fade);
        }
        PlaybackSessionId session = ClientFeedSessions.get(feed);
        PlaybackVideoFrame frame = session == null ? null
                : com.kuronami.musicdiscmaker.client.audio.PlaybackSessionRegistry.latestVideoFrame(session);
        // While resuming or seeking within the same track, hold the last frame instead of the spinner.
        if (frame == null) return held != null
                ? TvScreenVertexConsumers.getSingleTextureVC(buffers, held.id(), CrtOverlay.LOADING,
                        screenSize, fade, channelFade)
                : TvScreenVertexConsumers.getWaitingVc(buffers, screenSize, playbackTicks, fade);
        Uploaded current = last;
        String key = trackKey(snapshot);
        if (current == null || frame.presentationTimeUs() != current.ptsUs()
                || frame.width() != current.width() || frame.height() != current.height()
                || !current.trackKey().equals(key)) {
            current = upload(feed, frame, key);
            if (current == null) return TvScreenVertexConsumers.getWaitingVc(buffers, screenSize, playbackTicks, fade);
        }
        return TvScreenVertexConsumers.getSingleTextureVC(buffers, current.id(), CrtOverlay.NONE,
                screenSize, fade, channelFade);
    }

    // MDM bumps the playback generation on every pause and resume, so the held frame is keyed by source and track
    // only. A stopped source or an ejected disc releases the texture through the feed-removal handler.
    private static String trackKey(FeedSourceSnapshot snapshot) {
        return snapshot == null ? "" : snapshot.sourceId() + "#" + snapshot.trackIdentity();
    }

    private Uploaded upload(UUID feed, PlaybackVideoFrame frame, String trackKey) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getTextureManager() == null) return null;
        Uploaded previous = uploaded.get(feed);
        ResourceLocation id = previous == null
                ? ResourceLocation.fromNamespaceAndPath("mdm_vista", "feed/" + feed.toString().replace('-', '_'))
                : previous.id();
        DynamicTexture texture;
        NativeImage pixels;
        if (previous != null && previous.width() == frame.width() && previous.height() == frame.height()) {
            texture = previous.texture();
            pixels = texture.getPixels();
        } else {
            pixels = new NativeImage(frame.width(), frame.height(), false);
            texture = new DynamicTexture(pixels);
            if (previous != null) {
                mc.getTextureManager().release(previous.id());
                previous.texture().close();
            }
        }
        ByteBuffer rgba = frame.rgba();
        for (int y = 0; y < frame.height(); y++) for (int x = 0; x < frame.width(); x++) {
            int r = rgba.get() & 255, g = rgba.get() & 255, b = rgba.get() & 255, a = rgba.get() & 255;
            pixels.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
        }
        texture.upload();
        if (previous == null || previous.width() != frame.width() || previous.height() != frame.height())
            mc.getTextureManager().register(id, texture);
        Uploaded next = new Uploaded(id, texture, frame.width(), frame.height(), frame.presentationTimeUs(),
                trackKey);
        uploaded.put(feed, next);
        return next;
    }

    /** Called when a server feed is removed or the client leaves its world. */
    public void release(UUID feed) {
        MdmVistaFeedVideoSource linked = LINKED_FEEDS.remove(feed);
        if (linked != null) linked.releaseTexture(feed);
        releaseTexture(feed);
    }

    private void releaseTexture(UUID feed) {
        Uploaded old = uploaded.remove(feed);
        if (old != null) Minecraft.getInstance().execute(() -> {
            Minecraft.getInstance().getTextureManager().release(old.id());
            old.texture().close();
        });
    }

    private static UUID resolveFeedId(TVBlockEntity tv) {
        UUID fromVista = tv.getViewingFeedId();
        if (fromVista != null) return fromVista;
        IVideoSource source = tv.getVideoSource();
        return source instanceof BroadcastVideoSource broadcast ? broadcast.uuid() : null;
    }

    @Override public void updateAudio(TVBlockEntity tv, boolean isOn) { /* video-only: MDM owns all PCM */ }
    @Override public boolean isPlayingMusic(TVBlockEntity tv) { return false; }
}
