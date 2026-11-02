package com.kuronami.mdmvista.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/** Drops per-world feed/session mappings when the client disconnects. */
@EventBusSubscriber(modid = "mdm_vista", value = Dist.CLIENT)
public final class MdmVistaClientEvents {
    private MdmVistaClientEvents() {}
    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        com.kuronami.mdmvista.MdmVistaAddon.clearClientState();
    }
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) com.kuronami.mdmvista.MdmVistaAddon.clearClientState();
    }
}
