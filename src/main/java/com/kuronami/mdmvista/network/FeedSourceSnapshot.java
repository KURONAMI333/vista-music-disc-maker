package com.kuronami.mdmvista.network;

import java.util.Objects;
import java.util.UUID;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import net.minecraft.core.BlockPos;

/** Server-authoritative MDM playback request routed to clients tracking a linked Vista TV. */
public record FeedSourceSnapshot(UUID feedId, UUID sourceId, long generation, long sequence,
        BlockPos sourcePos, CustomTrackData track, String canonicalVideoUrl, long startOffsetMs,
        int rangeBlocks, int volumePercent, boolean directional, long serverPlaybackTimeMs,
        boolean active, boolean paused, boolean stopPlayback, boolean sourceStopped) {
    public FeedSourceSnapshot {
        Objects.requireNonNull(feedId, "feedId");
        Objects.requireNonNull(sourceId, "sourceId");
        sourcePos = Objects.requireNonNull(sourcePos, "sourcePos").immutable();
        canonicalVideoUrl = Objects.requireNonNull(canonicalVideoUrl, "canonicalVideoUrl");
        if (generation < 0 || sequence < 0 || startOffsetMs < 0 || serverPlaybackTimeMs < 0)
            throw new IllegalArgumentException("negative source clock, sequence, or generation");
        if (canonicalVideoUrl.length() > 2048) throw new IllegalArgumentException("video URL too long");
        if (track != null && track.sourceKey().length() > 2048) throw new IllegalArgumentException("track identity too long");
        if (rangeBlocks < 0 || rangeBlocks > 256 || volumePercent < 0 || volumePercent > 200)
            throw new IllegalArgumentException("invalid MDM speaker configuration");
        if (active && (track == null || track.isEmpty())) throw new IllegalArgumentException("active source requires track data");
        if (stopPlayback && active) throw new IllegalArgumentException("playing source cannot request a stop");
        if (paused && sourceStopped) throw new IllegalArgumentException("stopped source cannot be paused");
        if (paused && !stopPlayback) throw new IllegalArgumentException("paused source must detach AV playback");
        if (sourceStopped && !stopPlayback) throw new IllegalArgumentException("stopped source must stop playback");
    }
    public String trackIdentity() { return track == null ? "" : track.sourceKey(); }
}
