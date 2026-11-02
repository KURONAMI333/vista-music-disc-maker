package com.kuronami.mdmvista.mixin;

import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.kuronami.mdmvista.network.FeedAudienceRouter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.mehvahdjukaar.vista.common.tv.TVBlockEntity;

/** Tracks feed subscriptions from the actual Vista TV tick and removes them on unload/removal. */
@Mixin(TVBlockEntity.class)
abstract class VistaTvAudienceMixin {
    @Inject(method = "onTick", at = @At("RETURN"))
    private static void mdmVista$trackAudience(Level level, BlockPos pos, BlockState state,
            TVBlockEntity tv, CallbackInfo ci) {
        if (level.isClientSide() || level.getServer() == null) return;
        UUID feedId = tv.isScreenOn(0.0F) ? tv.getViewingFeedId() : null;
        FeedAudienceRouter.updateTv(GlobalPos.of(level.dimension(), pos), feedId);
    }

    @Inject(method = "onChunkUnloaded", at = @At("TAIL"))
    private void mdmVista$removeUnloaded(CallbackInfo ci) {
        TVBlockEntity self = (TVBlockEntity) (Object) this;
        if (self.getLevel() != null)
            FeedAudienceRouter.removeTv(GlobalPos.of(self.getLevel().dimension(), self.getBlockPos()));
    }

    @Inject(method = "setRemoved", at = @At("TAIL"))
    private void mdmVista$remove(CallbackInfo ci) {
        TVBlockEntity self = (TVBlockEntity) (Object) this;
        if (self.getLevel() != null)
            FeedAudienceRouter.removeTv(GlobalPos.of(self.getLevel().dimension(), self.getBlockPos()));
    }
}
