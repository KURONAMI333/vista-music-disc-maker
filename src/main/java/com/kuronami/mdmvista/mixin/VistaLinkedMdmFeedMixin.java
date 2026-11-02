package com.kuronami.mdmvista.mixin;

import com.kuronami.mdmvista.client.ClientFeedSessions;
import com.kuronami.mdmvista.client.MdmLinkedFeeds;
import com.kuronami.mdmvista.client.MdmPlaybackCapabilities;
import com.kuronami.mdmvista.client.MdmVistaFeedVideoSource;
import net.mehvahdjukaar.vista.client.video_source.BroadcastVideoSource;
import net.mehvahdjukaar.vista.client.video_source.IVideoSource;
import net.mehvahdjukaar.vista.common.tv.TVBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Renders only server-published MDM-linked Vista feeds without resolving the source chunk client-side. */
@Mixin(TVBlockEntity.class)
abstract class VistaLinkedMdmFeedMixin {
    @Inject(method = "getVideoSource", at = @At("HEAD"), cancellable = true)
    private void mdmVista$usePublishedMdmFeedHead(CallbackInfoReturnable<IVideoSource> cir) {
        if (!MdmPlaybackCapabilities.available()) return;
        TVBlockEntity self = (TVBlockEntity) (Object) this;
        java.util.UUID feedId = self.getViewingFeedId();
        if (feedId == null || !MdmLinkedFeeds.contains(feedId)) return;
        cir.setReturnValue(MdmVistaFeedVideoSource.forLinkedFeed(feedId));
    }

    @Inject(method = "getVideoSource", at = @At("RETURN"), cancellable = true)
    private void mdmVista$usePublishedMdmFeed(CallbackInfoReturnable<IVideoSource> cir) {
        IVideoSource current = cir.getReturnValue();
        if (!(current instanceof BroadcastVideoSource broadcast) || !MdmPlaybackCapabilities.available()) return;
        var snapshot = ClientFeedSessions.feed(broadcast.uuid());
        if (snapshot == null || (snapshot.stopPlayback() && !snapshot.paused()) || snapshot.sourceStopped()) return;
        cir.setReturnValue(MdmVistaFeedVideoSource.forLinkedFeed(broadcast.uuid()));
    }
}
