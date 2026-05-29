package com.viatabs.agent;

import android.net.Uri;

import java.util.Locale;

final class TabUrls {
    private TabUrls() {
    }

    static boolean isMeaningful(String url) {
        if (url == null) {
            return false;
        }
        String safe = url.trim();
        if (safe.length() == 0 || "null".equalsIgnoreCase(safe)) {
            return false;
        }
        return safe.startsWith("http://") || safe.startsWith("https://")
                || safe.startsWith("file://") || safe.startsWith("content://");
    }

    static String key(String url) {
        if (url == null) {
            return "";
        }
        String safe = url.trim();
        if (safe.length() == 0 || "null".equalsIgnoreCase(safe)) {
            return "";
        }
        try {
            Uri uri = Uri.parse(safe);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return safe.toLowerCase(Locale.US);
            }
            Uri.Builder builder = new Uri.Builder()
                    .scheme(scheme.toLowerCase(Locale.US))
                    .encodedAuthority(host.toLowerCase(Locale.US)
                            + (uri.getPort() >= 0 ? ":" + uri.getPort() : ""))
                    .encodedPath(normalizePath(uri.getEncodedPath()))
                    .encodedQuery(uri.getEncodedQuery());
            return builder.build().toString();
        } catch (Throwable ignored) {
            return safe.toLowerCase(Locale.US);
        }
    }

    static String titleFromUrl(String url) {
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host != null && host.length() > 0) {
                return host;
            }
        } catch (Throwable ignored) {
        }
        return url;
    }

    static boolean isHomepage(String url) {
        if (url == null) {
            return false;
        }
        String normalized = url.trim().toLowerCase(Locale.US);
        return normalized.startsWith("file://") && normalized.contains("homepage");
    }

    static boolean isBookmarkable(String url) {
        if (url == null) {
            return false;
        }
        String normalized = url.trim().toLowerCase(Locale.US);
        return normalized.startsWith("http://") || normalized.startsWith("https://");
    }

    static String domainGroupName(String url) {
        try {
            String host = Uri.parse(url).getHost();
            if (host == null || host.trim().length() == 0) {
                return "other";
            }
            String[] parts = normalizeHostParts(host);
            for (int i = effectiveDomainEnd(parts); i >= 0; i--) {
                String part = parts[i] == null ? "" : parts[i].trim();
                if (part.length() == 0 || isHostPrefixLabel(part) || isPublicSuffixLabel(part)) {
                    continue;
                }
                return part;
            }
        } catch (Throwable ignored) {
        }
        return "other";
    }

    private static String normalizePath(String path) {
        if (path == null || path.length() == 0) {
            return "/";
        }
        String normalized = path;
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String[] normalizeHostParts(String host) {
        if (host == null) {
            return new String[0];
        }
        String normalized = host.toLowerCase(Locale.US).trim();
        while (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.split("\\.");
    }

    private static int effectiveDomainEnd(String[] parts) {
        if (parts == null || parts.length == 0) {
            return -1;
        }
        int end = parts.length - 1;
        if (end >= 1 && isTwoPartPublicSuffix(parts[end - 1], parts[end])) {
            return end - 2;
        }
        while (end >= 0 && isPublicSuffixLabel(parts[end])) {
            end--;
        }
        return end;
    }

    private static boolean isHostPrefixLabel(String label) {
        return "www".equals(label)
                || "m".equals(label)
                || "mobile".equals(label)
                || "wap".equals(label)
                || "touch".equals(label);
    }

    private static boolean isTwoPartPublicSuffix(String left, String right) {
        String suffix = left + "." + right;
        return "com.cn".equals(suffix)
                || "net.cn".equals(suffix)
                || "org.cn".equals(suffix)
                || "gov.cn".equals(suffix)
                || "com.hk".equals(suffix)
                || "com.tw".equals(suffix)
                || "co.uk".equals(suffix)
                || "org.uk".equals(suffix)
                || "ac.uk".equals(suffix)
                || "co.jp".equals(suffix)
                || "com.au".equals(suffix);
    }

    private static boolean isPublicSuffixLabel(String label) {
        return "com".equals(label)
                || "cn".equals(label)
                || "net".equals(label)
                || "org".equals(label)
                || "edu".equals(label)
                || "gov".equals(label)
                || "mil".equals(label)
                || "io".equals(label)
                || "ai".equals(label)
                || "ws".equals(label)
                || "xyz".equals(label)
                || "top".equals(label)
                || "cc".equals(label)
                || "me".equals(label)
                || "app".equals(label)
                || "dev".equals(label)
                || "info".equals(label)
                || "biz".equals(label)
                || "co".equals(label)
                || "uk".equals(label)
                || "jp".equals(label)
                || "tv".equals(label);
    }
}
