package com.kuronami.mdmvista.client;

import java.lang.reflect.Method;

/** Checks only the MDM AV session API this addon consumes; playback commands remain core-owned. */
public final class MdmPlaybackCapabilities {
    private static final System.Logger LOGGER = System.getLogger(MdmPlaybackCapabilities.class.getName());
    private static final String REGISTRY = "com.kuronami.musicdiscmaker.client.audio.PlaybackSessionRegistry";
    private static final String PROVIDER = "com.kuronami.musicdiscmaker.client.audio.PlaybackSessionProvider";
    private static final String SESSION_ID = "com.kuronami.musicdiscmaker.client.audio.PlaybackSessionId";
    private static final String AUDIENCE_REGISTRY = "com.kuronami.musicdiscmaker.event.SourcePlaybackAudienceRegistry";
    private static final String AUDIENCE_PROVIDER = "com.kuronami.musicdiscmaker.event.SourcePlaybackAudienceProvider";
    private static final boolean AVAILABLE = supports(MdmPlaybackCapabilities.class.getClassLoader());
    private static boolean reportedUnavailable;

    private MdmPlaybackCapabilities() {}

    public static boolean available() {
        if (!AVAILABLE && !reportedUnavailable) {
            reportedUnavailable = true;
            LOGGER.log(System.Logger.Level.WARNING,
                    "Vista video adapter disabled: installed MDM lacks the required AV session API");
        }
        return AVAILABLE;
    }

    static boolean supports(ClassLoader loader) {
        try {
            Class<?> registry = Class.forName(REGISTRY, false, loader);
            Class<?> provider = Class.forName(PROVIDER, false, loader);
            Class<?> sessionId = Class.forName(SESSION_ID, false, loader);
            Class<?> audienceRegistry = Class.forName(AUDIENCE_REGISTRY, false, loader);
            Class<?> audienceProvider = Class.forName(AUDIENCE_PROVIDER, false, loader);
            Class<?> source = Class.forName("com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity", false, loader);
            Class<?> player = Class.forName("net.minecraft.server.level.ServerPlayer", false, loader);
            Method register = registry.getMethod("register", provider);
            Method latestFrame = registry.getMethod("latestVideoFrame", sessionId);
            Method registerAudience = audienceRegistry.getMethod("register", audienceProvider);
            audienceProvider.getMethod("viewers", source);
            audienceProvider.getMethod("beforeCorePlay", source, player);
            audienceRegistry.getMethod("beforeCorePlay", source, player);
            return register.getReturnType() == AutoCloseable.class
                    && registerAudience.getReturnType() == AutoCloseable.class
                    && latestFrame.getReturnType().getName().equals(
                            "com.kuronami.musicdiscmaker.client.audio.PlaybackVideoFrame");
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return false;
        }
    }
}
