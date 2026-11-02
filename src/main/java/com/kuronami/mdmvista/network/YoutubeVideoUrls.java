package com.kuronami.mdmvista.network;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** Strictly extracts a YouTube video URL without searching or substituting another video. */
public final class YoutubeVideoUrls {
    private YoutubeVideoUrls() {}

    public static String canonicalVideoUrl(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 2048) return "";
        try {
            URI uri = URI.create(raw);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                    || uri.getRawUserInfo() != null || uri.getPort() != -1 && uri.getPort() != 443
                    || uri.getRawFragment() != null) return "";
            host = host.toLowerCase(java.util.Locale.ROOT);
            String id = null;
            if (host.equals("youtu.be")) {
                String path = uri.getRawPath();
                if (path != null && path.matches("/[A-Za-z0-9_-]{11}/?")) id = path.substring(1, 12);
            } else if (host.equals("youtube.com") || host.equals("www.youtube.com") || host.equals("m.youtube.com")) {
                String path = uri.getRawPath();
                if ("/watch".equals(path)) {
                    String query = uri.getRawQuery();
                    if (query != null) for (String pair : query.split("&")) {
                        int equals = pair.indexOf('=');
                        if (equals < 0 || !URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8).equals("v")) continue;
                        if (id != null) return "";
                        id = URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
                    }
                } else if (path != null && path.matches("/(?:shorts|live)/[A-Za-z0-9_-]{11}/?")) {
                    id = path.split("/")[2];
                }
            }
            return id != null && id.matches("[A-Za-z0-9_-]{11}")
                    ? "https://www.youtube.com/watch?v=" + id : "";
        } catch (IllegalArgumentException invalid) {
            return "";
        }
    }
}
