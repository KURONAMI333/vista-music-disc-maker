package com.kuronami.mdmvista.client.media;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/** Exact URL/source lookup populated only by a future managed acquisition stage; never title/thumbnail search. */
public interface MuxedFileIndex {
    boolean contains(UUID sourceId, String exactTrackUrl);
    Optional<Path> resolve(UUID sourceId, String exactTrackUrl);
}
