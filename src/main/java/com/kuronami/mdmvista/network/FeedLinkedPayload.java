package com.kuronami.mdmvista.network;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Early hint that a Vista feed belongs to an MDM source, so the TV never falls back to Vista's source resolver. */
public record FeedLinkedPayload(UUID feedId, boolean linked) implements CustomPacketPayload {
    public static final Type<FeedLinkedPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("mdm_vista", "feed_linked"));
    public static final StreamCodec<FriendlyByteBuf, FeedLinkedPayload> STREAM_CODEC = StreamCodec.of(
            FeedLinkedPayload::write, FeedLinkedPayload::read);

    public FeedLinkedPayload {
        Objects.requireNonNull(feedId, "feedId");
    }

    private static void write(FriendlyByteBuf buf, FeedLinkedPayload payload) {
        buf.writeUUID(payload.feedId());
        buf.writeBoolean(payload.linked());
    }

    private static FeedLinkedPayload read(FriendlyByteBuf buf) {
        return new FeedLinkedPayload(buf.readUUID(), buf.readBoolean());
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
