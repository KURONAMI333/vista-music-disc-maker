package com.kuronami.mdmvista.client.media;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/** Exact deterministic cache path for one source UUID and one canonical video URL. */
public final class ManagedMuxedFileIndex implements MuxedFileIndex {
    private final Path root;
    public ManagedMuxedFileIndex(Path root) { this.root = root.toAbsolutePath().normalize(); }

    Path pathFor(UUID sourceId, String exactUrl) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    ("mdm-vista-av-policy-v2-h264-480p\n" + sourceId + "\n" + exactUrl)
                            .getBytes(StandardCharsets.UTF_8));
            return root.resolve(HexFormat.of().formatHex(digest) + ".mp4");
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    @Override public boolean contains(UUID sourceId, String exactTrackUrl) {
        return sourceId != null && exactTrackUrl != null && Files.isRegularFile(pathFor(sourceId, exactTrackUrl));
    }
    @Override public Optional<Path> resolve(UUID sourceId, String exactTrackUrl) {
        if (!contains(sourceId, exactTrackUrl)) return Optional.empty();
        return Optional.of(pathFor(sourceId, exactTrackUrl));
    }
}
