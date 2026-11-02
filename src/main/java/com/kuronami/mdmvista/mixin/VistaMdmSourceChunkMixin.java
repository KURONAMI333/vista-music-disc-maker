package com.kuronami.mdmvista.mixin;

import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import net.mehvahdjukaar.vista.common.broadcast.BroadcastManager;
import net.mehvahdjukaar.vista.common.chunk_tracking.ServerCameraChunkManager;
import net.mehvahdjukaar.vista.common.tv.TVBlockEntity;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vista's source-chunk viewfinder is unnecessary for MDM Golden Jukebox broadcasts. */
@Mixin(ServerCameraChunkManager.class)
abstract class VistaMdmSourceChunkMixin {
    @Inject(method = "findViewFinderShownBy", at = @At("RETURN"), cancellable = true)
    private static void mdmVista$skipMdmSourceChunk(TVBlockEntity tv, BroadcastManager manager,
                                                    MinecraftServer server,
                                                    CallbackInfoReturnable<GlobalPos> cir) {
        if (cir.getReturnValue() == null || manager == null) return;
        var feedId = tv.getViewingFeedId();
        if (feedId == null) return;
        var source = manager.getBroadcast(feedId, false);
        if (source instanceof GoldenJukeboxBlockEntity
                || com.kuronami.mdmvista.network.MdmVistaServerSync.isLinked(feedId)) {
            cir.setReturnValue(null);
        }
    }
}
