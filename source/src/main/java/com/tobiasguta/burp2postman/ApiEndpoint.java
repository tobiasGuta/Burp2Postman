package com.tobiasguta.burp2postman;

import java.net.URI;
import java.util.Locale;

/**
 * A normalized API endpoint whose use of a non-Postman hostname has been
 * explicitly approved by the user. PostmanClient accepts this type instead of
 * a raw URL so custom-host approval cannot be accidentally skipped.
 */
record ApiEndpoint(String baseUrl, String host, boolean customHostConfirmed) {
    static final String DEFAULT_BASE_URL = "https://api.postman.com";
    static final String DEFAULT_HOST = "api.postman.com";

    ApiEndpoint {
        baseUrl = normalize(baseUrl);
        URI uri = URI.create(baseUrl);
        host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!isDefaultHost(host) && !customHostConfirmed) {
            throw new IllegalArgumentException(
                    "Sending a Postman API key to " + host + " requires explicit confirmation.");
        }
    }

    static ApiEndpoint defaultEndpoint() {
        return new ApiEndpoint(DEFAULT_BASE_URL, DEFAULT_HOST, false);
    }

    static ApiEndpoint confirmed(String baseUrl) {
        String normalized = normalize(baseUrl);
        String hostname = URI.create(normalized).getHost().toLowerCase(Locale.ROOT);
        return new ApiEndpoint(normalized, hostname, !isDefaultHost(hostname));
    }

    static String normalize(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        if (value.isBlank()) {
            value = DEFAULT_BASE_URL;
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }

        final URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Postman API base URL is invalid.", e);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Postman API base URL must use HTTPS.");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("Postman API base URL must contain a valid host.");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Postman API base URL must not contain user information.");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Postman API base URL must not contain a query or fragment.");
        }
        return value;
    }

    static String hostOf(String baseUrl) {
        return URI.create(normalize(baseUrl)).getHost().toLowerCase(Locale.ROOT);
    }

    boolean isDefaultHost() {
        return isDefaultHost(host);
    }

    private static boolean isDefaultHost(String hostname) {
        return DEFAULT_HOST.equalsIgnoreCase(hostname);
    }
}
