package com.kuronami.mdmvista.network;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded per-feed/source/generation recipient leases, reclaimed by terminal, logout, or dimension change. */
public final class AudienceLeaseBook {
    private static final int MAX_RETAINED_RECIPIENTS = 1024;
    public record SessionKey(UUID feedId, UUID sourceId, long generation) {}
    private final Map<SessionKey, Set<UUID>> recipients = new ConcurrentHashMap<>();

    public Set<UUID> destinations(SessionKey key, Set<UUID> currentViewers, boolean terminal) {
        Set<UUID> result = new HashSet<>(currentViewers);
        if (terminal) {
            boolean newerLeaseExists = recipients.keySet().stream().anyMatch(old ->
                    old.feedId().equals(key.feedId()) && old.sourceId().equals(key.sourceId())
                            && old.generation() > key.generation());
            if (newerLeaseExists) return Set.of();
            for (var entry : recipients.entrySet()) {
                SessionKey old = entry.getKey();
                if (old.feedId().equals(key.feedId()) && old.sourceId().equals(key.sourceId())
                        && old.generation() <= key.generation()) {
                    result.addAll(entry.getValue());
                    recipients.remove(old, entry.getValue());
                }
            }
        } else {
            Set<UUID> retained = recipients.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet());
            retained.addAll(currentViewers);
            if (retained.size() > MAX_RETAINED_RECIPIENTS) {
                retained.stream().sorted().skip(MAX_RETAINED_RECIPIENTS).forEach(retained::remove);
            }
            result.addAll(retained);
        }
        return result;
    }

    /** Returns and drops one exact prior session before its feed/source/generation is replaced. */
    public Set<UUID> retire(SessionKey key) {
        Set<UUID> removed = recipients.remove(key);
        return removed == null ? Set.of() : Set.copyOf(removed);
    }

    /** Read-only recipient snapshot for one exact feed/source/generation lease. */
    public Set<UUID> viewers(SessionKey key) {
        Set<UUID> current = recipients.get(key);
        return current == null ? Set.of() : Set.copyOf(current);
    }

    /** Includes pre-Play leases even before the first periodic feed snapshot exists. */
    public Set<UUID> viewersForSource(UUID feedId, UUID sourceId, long throughGeneration) {
        Set<UUID> result = new HashSet<>();
        recipients.forEach((key, viewers) -> {
            if (key.feedId().equals(feedId) && key.sourceId().equals(sourceId)
                    && key.generation() <= throughGeneration) result.addAll(viewers);
        });
        return Set.copyOf(result);
    }

    /** Carries only this feed/source's earlier leases into the core generation being played. */
    public void advancePrior(SessionKey next) {
        for (SessionKey old : Set.copyOf(recipients.keySet())) {
            if (old.feedId().equals(next.feedId()) && old.sourceId().equals(next.sourceId())
                    && old.generation() < next.generation()) advance(old, next);
        }
    }

    public boolean contains(SessionKey key, UUID player) {
        Set<UUID> current = recipients.get(key);
        return current != null && current.contains(player);
    }

    /** Carries an ongoing source's audience to its next logical playback generation. */
    public Set<UUID> advance(SessionKey previous, SessionKey next) {
        if (!previous.feedId().equals(next.feedId()) || !previous.sourceId().equals(next.sourceId())
                || next.generation() <= previous.generation()) return Set.of();
        Set<UUID> moved = recipients.remove(previous);
        if (moved == null || moved.isEmpty()) return Set.of();
        Set<UUID> current = recipients.computeIfAbsent(next, ignored -> ConcurrentHashMap.newKeySet());
        current.addAll(moved);
        if (current.size() > MAX_RETAINED_RECIPIENTS)
            current.stream().sorted().skip(MAX_RETAINED_RECIPIENTS).forEach(current::remove);
        return Set.copyOf(current);
    }

    public void removeRecipient(UUID player) {
        recipients.values().forEach(viewers -> viewers.remove(player));
        recipients.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }
    public void removeFeed(UUID feed) { recipients.keySet().removeIf(key -> key.feedId().equals(feed)); }
    public void clear() { recipients.clear(); }
    public int sessionCount() { return recipients.size(); }
}
