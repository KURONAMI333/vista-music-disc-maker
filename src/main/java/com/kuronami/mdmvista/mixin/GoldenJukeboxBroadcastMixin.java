package com.kuronami.mdmvista.mixin;

import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.mehvahdjukaar.vista.common.broadcast.LevelBEBroadcastLocation;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.mdmvista.network.MdmVistaServerSync;
import net.mehvahdjukaar.vista.client.video_source.IVideoSource;
import net.mehvahdjukaar.vista.common.cassette.IBroadcastSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

/** Makes the existing MDM Golden Jukebox a Vista source without changing its playback/audio ownership. */
@Mixin(GoldenJukeboxBlockEntity.class)
abstract class GoldenJukeboxBroadcastMixin implements IBroadcastSource {
    @Override public UUID getBroadcastUUID() {
        GoldenJukeboxBlockEntity source = (GoldenJukeboxBlockEntity) (Object) this;
        return source.getLevel() instanceof ServerLevel ? source.sourceIdentity() : source.peekSourceIdentity();
    }

    @Inject(method = "setLevel", at = @At("TAIL"))
    private void mdmVista$ensureLinked(Level level, CallbackInfo ci) {
        if (level == null) return;
        ensureLinked(level, LevelBEBroadcastLocation.of((GoldenJukeboxBlockEntity) (Object) this));
        if (level instanceof ServerLevel serverLevel)
            MdmVistaServerSync.broadcastLink(serverLevel, getBroadcastUUID(), true);
    }

    @Override public IVideoSource getBroadcastVideo() {
        if (FMLEnvironment.dist == Dist.CLIENT
                && com.kuronami.mdmvista.client.MdmPlaybackCapabilities.available())
            return com.kuronami.mdmvista.client.MdmVistaFeedVideoSource.getInstance();
        return IVideoSource.EMPTY;
    }

    @Inject(method = "startPlayback(J)V", at = @At(value = "INVOKE", target =
            "Lcom/kuronami/musicdiscmaker/event/SpeakerPlayback;play(Lcom/kuronami/musicdiscmaker/block/GoldenJukeboxBlockEntity;Lcom/kuronami/musicdiscmaker/component/CustomTrackData;J)Z"))
    private void mdmVista$announceBeforeMdmPlay(long offsetMs, CallbackInfo ci) {
        MdmVistaServerSync.publishImmediately((GoldenJukeboxBlockEntity) (Object) this, offsetMs);
    }

    @Inject(method = "stopPlayback(Z)V", at = @At("TAIL"))
    private void mdmVista$announceStop(boolean naturalEnd, CallbackInfo ci) {
        MdmVistaServerSync.publishImmediately((GoldenJukeboxBlockEntity) (Object) this);
    }

    @Inject(method = "setPaused(Z)V", at = @At("TAIL"))
    private void mdmVista$announcePause(boolean paused, CallbackInfo ci) {
        MdmVistaServerSync.publishImmediately((GoldenJukeboxBlockEntity) (Object) this);
    }

    @Inject(method = "seekTo(J)V", at = @At("TAIL"))
    private void mdmVista$announceSeek(long offsetMs, CallbackInfo ci) {
        MdmVistaServerSync.publishImmediately((GoldenJukeboxBlockEntity) (Object) this);
    }

    @Inject(method = "onBlockRemoved()V", at = @At("TAIL"))
    private void mdmVista$announceRemoval(CallbackInfo ci) {
        GoldenJukeboxBlockEntity self = (GoldenJukeboxBlockEntity) (Object) this;
        UUID feedId = getBroadcastUUID();
        MdmVistaServerSync.publishImmediately(self);
        if (self.getLevel() instanceof ServerLevel serverLevel)
            MdmVistaServerSync.broadcastLink(serverLevel, feedId, false);
        if (self.getLevel() != null) removeLink(self.getLevel());
    }
}
