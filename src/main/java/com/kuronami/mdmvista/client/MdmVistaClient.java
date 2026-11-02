package com.kuronami.mdmvista.client;

import java.nio.file.Files;
import java.nio.file.Path;
import com.kuronami.mdmvista.client.media.ManagedMuxedFileIndex;
import com.kuronami.mdmvista.client.media.MuxedFilePlaybackProvider;
import com.kuronami.musicdiscmaker.client.audio.PlaybackSessionRegistry;
import net.neoforged.fml.loading.FMLPaths;

/** Client-only addon bootstrap. Registers the exact-source provider and addon-owned managed acquisition path. */
public final class MdmVistaClient {
    private static final System.Logger LOGGER = System.getLogger(MdmVistaClient.class.getName());
    private static AutoCloseable providerRegistration;
    private MdmVistaClient() {}

    public static void register() {
        if (providerRegistration != null || !MdmPlaybackCapabilities.available()) return;
        try {
            ClientFeedSessions.setFeedRemovalHandler(MdmVistaFeedVideoSource.INSTANCE::release);
            Path cache = FMLPaths.GAMEDIR.get().resolve("mdm-vista").resolve("muxed-cache");
            Files.createDirectories(cache);
            providerRegistration = PlaybackSessionRegistry.register(
                    new MuxedFilePlaybackProvider(cache, new ManagedMuxedFileIndex(cache)));
            LOGGER.log(System.Logger.Level.INFO, "MDM × Vista managed YouTube provider enabled at {0}", cache);
        } catch (Exception failure) {
            LOGGER.log(System.Logger.Level.WARNING, "MDM × Vista local muxed cache unavailable", failure);
        }
    }
}
