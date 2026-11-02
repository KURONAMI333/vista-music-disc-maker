package com.kuronami.mdmvista.client;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Client-side set of feeds the server has declared MDM-owned. */
public final class MdmLinkedFeeds {
    private static final Set<UUID> LINKED = ConcurrentHashMap.newKeySet();
    private MdmLinkedFeeds() {}

    public static void update(UUID feedId, boolean linked) {
        if (feedId == null) return;
        if (linked) LINKED.add(feedId);
        else LINKED.remove(feedId);
    }

    public static boolean contains(UUID feedId) {
        return feedId != null && LINKED.contains(feedId);
    }

    public static int size() { return LINKED.size(); }

    public static void clear() { LINKED.clear(); }
}
