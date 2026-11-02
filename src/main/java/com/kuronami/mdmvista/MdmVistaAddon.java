package com.kuronami.mdmvista;

import com.kuronami.mdmvista.network.FeedLinkedPayload;
import com.kuronami.mdmvista.network.FeedSourcePayload;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Standalone NeoForge addon entrypoint; MDM remains an optional-session API dependency. */
@Mod(MdmVistaAddon.MOD_ID)
public final class MdmVistaAddon {
    public static final String MOD_ID = "mdm_vista";

    public MdmVistaAddon(IEventBus modBus, ModContainer container) {
        modBus.addListener(this::registerPayloads);
        modBus.addListener(this::clientSetup);
        com.kuronami.mdmvista.network.MdmVistaServerSync.register();
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(FeedSourcePayload.TYPE, FeedSourcePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (FMLEnvironment.dist == Dist.CLIENT) ClientPayloadHandler.update(payload);
                }));
        registrar.playToClient(FeedLinkedPayload.TYPE, FeedLinkedPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (FMLEnvironment.dist == Dist.CLIENT) ClientPayloadHandler.updateLink(payload);
                }));
    }

    private void clientSetup(FMLClientSetupEvent event) {
        if (FMLEnvironment.dist == Dist.CLIENT
                && com.kuronami.mdmvista.client.MdmPlaybackCapabilities.available())
            event.enqueueWork(com.kuronami.mdmvista.client.MdmVistaClient::register);
    }

    public static void clearClientState() { ClientPayloadHandler.clear(); }

    /** Snapshot handling stores source/video metadata only; MDM owns every playback command. */
    private static final class ClientPayloadHandler {
        private static void update(FeedSourcePayload payload) {
            if (!com.kuronami.mdmvista.client.MdmPlaybackCapabilities.available()) return;
            com.kuronami.mdmvista.client.ClientFeedSessions.update(payload.snapshot());
        }

        private static void updateLink(FeedLinkedPayload payload) {
            com.kuronami.mdmvista.client.MdmLinkedFeeds.update(payload.feedId(), payload.linked());
        }

        private static void clear() {
            com.kuronami.mdmvista.client.ClientFeedSessions.clear();
            com.kuronami.mdmvista.client.MdmLinkedFeeds.clear();
        }
    }
}
