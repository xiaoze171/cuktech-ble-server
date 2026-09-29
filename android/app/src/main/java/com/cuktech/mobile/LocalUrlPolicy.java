package com.cuktech.mobile;

import java.net.URI;
import java.net.URISyntaxException;

/** The runtime chooses the port; only that exact loopback origin is trusted. */
final class LocalUrlPolicy {
    private final int port;
    final String baseUrl;

    LocalUrlPolicy(String base) {
        URI uri = parse(base);
        if (uri == null || !"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                || uri.getPort() < 1 || uri.getPort() > 65535 || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !("".equals(uri.getRawPath()) || "/".equals(uri.getRawPath()))) {
            throw new IllegalArgumentException("Runtime must use an explicit loopback HTTP port");
        }
        port = uri.getPort();
        baseUrl = "http://127.0.0.1:" + port;
    }

    boolean isLocal(String url) {
        URI uri = parse(url);
        return uri != null && "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost())
                && uri.getPort() == port && uri.getRawUserInfo() == null;
    }

    boolean isCsvExport(String url) {
        if (!isLocal(url)) return false;
        URI uri = parse(url);
        return uri != null && uri.getRawPath().matches("/api/export/[1-4]") && uri.getRawFragment() == null;
    }

    static boolean isExternalWeb(String url) {
        URI uri = parse(url);
        return uri != null && ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                && uri.getHost() != null && uri.getRawUserInfo() == null;
    }

    private static URI parse(String url) {
        if (url == null || url.indexOf('\\') >= 0) return null;
        try { return new URI(url); } catch (URISyntaxException ignored) { return null; }
    }
}
