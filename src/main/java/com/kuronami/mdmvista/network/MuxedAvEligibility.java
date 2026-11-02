package com.kuronami.mdmvista.network;

/** Limits addon session selection to explicit YouTube video tracks, excluding local and radio media. */
public final class MuxedAvEligibility {
    private MuxedAvEligibility() {}
    public static boolean accepts(boolean local, boolean radio, String videoUrl) {
        return !local && !radio && !YoutubeVideoUrls.canonicalVideoUrl(videoUrl).isEmpty();
    }
}
