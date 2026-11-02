package com.kuronami.mdmvista.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Bounded server-to-client update for a Vista feed linked to an MDM source. */
public record FeedSourcePayload(FeedSourceSnapshot snapshot) implements CustomPacketPayload {
    public static final Type<FeedSourcePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("mdm_vista", "feed_source"));
    public static final StreamCodec<FriendlyByteBuf, FeedSourcePayload> STREAM_CODEC = StreamCodec.of(
            FeedSourcePayload::write, FeedSourcePayload::read);

    private static void write(FriendlyByteBuf buf, FeedSourcePayload payload) {
        FeedSourceSnapshot s = payload.snapshot();
        buf.writeUUID(s.feedId());
        buf.writeUUID(s.sourceId());
        buf.writeVarLong(s.generation());
        buf.writeVarLong(s.sequence());
        buf.writeLong(s.sourcePos().asLong());
        buf.writeBoolean(s.track() != null);
        if (s.track() != null) s.track().write(buf);
        buf.writeUtf(s.canonicalVideoUrl(), 2048);
        buf.writeVarLong(s.startOffsetMs());
        buf.writeVarInt(s.rangeBlocks());
        buf.writeVarInt(s.volumePercent());
        buf.writeBoolean(s.directional());
        buf.writeVarLong(s.serverPlaybackTimeMs());
        buf.writeBoolean(s.active());
        buf.writeBoolean(s.paused());
        buf.writeBoolean(s.stopPlayback());
        buf.writeBoolean(s.sourceStopped());
    }

    private static FeedSourcePayload read(FriendlyByteBuf buf) {
        java.util.UUID feed = buf.readUUID(), source = buf.readUUID();
        long generation = buf.readVarLong(), sequence = buf.readVarLong();
        net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.of(buf.readLong());
        com.kuronami.musicdiscmaker.component.CustomTrackData track = buf.readBoolean()
                ? com.kuronami.musicdiscmaker.component.CustomTrackData.read(buf) : null;
        return new FeedSourcePayload(new FeedSourceSnapshot(feed, source, generation, sequence, pos, track,
                buf.readUtf(2048), buf.readVarLong(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(),
                buf.readVarLong(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean()));
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
