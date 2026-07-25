package com.tobiasguta.burp2postman;

import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class RequestConverter {
    enum HeaderFormat {
        STRUCTURED("Structured array (recommended)"),
        LEGACY_STRING("Legacy newline-separated string");

        private final String label;

        HeaderFormat(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final Set<String> TRANSPORT_HEADERS = Set.of(
            "content-length", "transfer-encoding", "connection", "proxy-connection",
            "keep-alive", "upgrade", "http2-settings", "te", "trailer"
    );

    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie",
            "x-api-key", "api-key", "x-auth-token", "x-access-token",
            "x-csrf-token", "x-xsrf-token", "csrf-token", "x-amz-security-token"
    );
    private static final String SANITIZED_QUERY_VALUE = "{{secret}}";
    private static final String SANITIZED_RAW_QUERY_VALUE = "%7B%7Bsecret%7D%7D";

    record Options(
            boolean preserveHostHeader,
            boolean removeTransportHeaders,
            HeaderFormat headerFormat
    ) {
        Options {
            if (headerFormat == null) {
                headerFormat = HeaderFormat.STRUCTURED;
            }
        }

        static Options defaults() {
            return new Options(true, true, HeaderFormat.STRUCTURED);
        }
    }

    Map<String, Object> convert(HttpRequest request, boolean sanitized, Options options) {
        Map<String, Object> result = MiniJson.object();
        String method = safe(request.method(), "GET");
        String url = safe(request.url(), "");
        String path = safe(request.pathWithoutQuery(), "/");
        String name = method + " " + (path.isBlank() ? "/" : path);

        result.put("name", name);
        result.put("description", "Captured from Burp Suite by Burp2Postman.");
        result.put("method", method);
        result.put("url", sanitized ? sanitizeUrlQuery(url) : url);

        StringBuilder legacyHeaders = new StringBuilder();
        List<Object> structuredHeaders = MiniJson.array();
        String contentType = "";
        for (HttpHeader header : request.headers()) {
            if (header == null) {
                continue;
            }
            String headerName = safe(header.name(), "");
            String lower = headerName.toLowerCase(Locale.ROOT);

            if (headerName.isBlank() || headerName.startsWith(":")) {
                continue;
            }
            if (options.removeTransportHeaders() && TRANSPORT_HEADERS.contains(lower)) {
                continue;
            }
            if (!options.preserveHostHeader() && lower.equals("host")) {
                continue;
            }

            String originalValue = safe(header.value(), "");
            String value = sanitized ? sanitizeHeader(lower, originalValue) : originalValue;
            
            if (options.headerFormat() == HeaderFormat.LEGACY_STRING) {
                legacyHeaders.append(headerName).append(": ").append(value).append("\n");
            } else {
                Map<String, Object> structuredHeader = MiniJson.object();
                structuredHeader.put("key", headerName);
                structuredHeader.put("value", value);
                structuredHeaders.add(structuredHeader);
            }

            if (lower.equals("content-type")) {
                contentType = originalValue.toLowerCase(Locale.ROOT);
            }
        }
        result.put("headers", options.headerFormat() == HeaderFormat.LEGACY_STRING
                ? legacyHeaders.toString()
                : structuredHeaders);

        addQueryParameters(result, url, sanitized);
        addBody(result, safe(request.bodyToString(), ""), contentType, sanitized);
        return result;
    }

    private static void addQueryParameters(Map<String, Object> result, String url, boolean sanitized) {
        List<Object> queryParams = MiniJson.array();
        result.put("queryParams", queryParams);
        String rawQuery = rawQueryOf(url);
        if (rawQuery == null || rawQuery.isBlank()) {
            return;
        }
        for (String pair : rawQuery.split("&", -1)) {
            String[] parts = pair.split("=", 2);
            String key = decodeQueryComponent(parts[0]);
            boolean hasValue = parts.length == 2;
            String value = hasValue ? decodeQueryComponent(parts[1]) : "";
            if (sanitized && hasValue && isSensitiveQueryKey(key)) {
                value = SANITIZED_QUERY_VALUE;
            }

            Map<String, Object> parameter = MiniJson.object();
            parameter.put("key", key);
            parameter.put("value", value);
            parameter.put("enabled", true);
            parameter.put("equals", hasValue);
            queryParams.add(parameter);
        }
    }

    static String sanitizeUrlQuery(String url) {
        String value = safe(url, "");
        int fragmentStart = value.indexOf('#');
        int queryStart = value.indexOf('?');
        if (queryStart < 0 || (fragmentStart >= 0 && queryStart > fragmentStart)) {
            return value;
        }

        int queryEnd = fragmentStart >= 0 ? fragmentStart : value.length();
        String rawQuery = value.substring(queryStart + 1, queryEnd);
        if (rawQuery.isBlank()) {
            return value;
        }

        StringBuilder sanitizedQuery = new StringBuilder(rawQuery.length());
        String[] pairs = rawQuery.split("&", -1);
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) sanitizedQuery.append('&');
            String pair = pairs[i];
            String[] parts = pair.split("=", 2);
            sanitizedQuery.append(parts[0]);
            if (parts.length == 2) {
                sanitizedQuery.append('=');
                sanitizedQuery.append(isSensitiveQueryKey(decodeQueryComponent(parts[0]))
                        ? SANITIZED_RAW_QUERY_VALUE
                        : parts[1]);
            }
        }
        return value.substring(0, queryStart + 1) + sanitizedQuery + value.substring(queryEnd);
    }

    private static String rawQueryOf(String url) {
        String value = safe(url, "");
        int fragmentStart = value.indexOf('#');
        int queryStart = value.indexOf('?');
        if (queryStart < 0 || (fragmentStart >= 0 && queryStart > fragmentStart)) {
            return null;
        }
        int queryEnd = fragmentStart >= 0 ? fragmentStart : value.length();
        return value.substring(queryStart + 1, queryEnd);
    }

    private static void addBody(
            Map<String, Object> result,
            String body,
            String contentType,
            boolean sanitized
    ) {
        if (body.isEmpty()) {
            return;
        }

        String finalBody = sanitized ? sanitizeBody(body, contentType) : body;
        if (contentType.contains("application/x-www-form-urlencoded")) {
            result.put("dataMode", "urlencoded");
            List<Object> data = MiniJson.array();
            result.put("data", data);
            for (String pair : finalBody.split("&", -1)) {
                String[] parts = pair.split("=", 2);
                Map<String, Object> field = MiniJson.object();
                field.put("key", decodeFormComponent(parts[0]));
                field.put("value", parts.length == 2 ? decodeFormComponent(parts[1]) : "");
                field.put("type", "text");
                field.put("enabled", true);
                data.add(field);
            }
            return;
        }

        // Multipart bodies are intentionally preserved as raw bytes-as-text in v0.2.0.
        // Postman cannot reconstruct the original local file path from a Burp-captured upload.
        result.put("dataMode", "raw");
        result.put("rawModeData", finalBody);
        Map<String, Object> raw = MiniJson.object();
        raw.put("language", languageFor(contentType));
        Map<String, Object> dataOptions = MiniJson.object();
        dataOptions.put("raw", raw);
        result.put("dataOptions", dataOptions);
    }

    private static String sanitizeHeader(String lowerName, String value) {
        if (!SENSITIVE_HEADERS.contains(lowerName)
                && !lowerName.contains("token")
                && !lowerName.contains("secret")
                && !lowerName.contains("api-key")
                && !lowerName.contains("apikey")) {
            return value;
        }

        return switch (lowerName) {
            case "authorization" -> sanitizeAuthorization(value);
            case "proxy-authorization" -> "{{proxyAuthorization}}";
            case "cookie" -> "{{cookie}}";
            case "set-cookie" -> "{{setCookie}}";
            case "x-csrf-token", "x-xsrf-token", "csrf-token" -> "{{csrfToken}}";
            case "x-api-key", "api-key" -> "{{apiKey}}";
            default -> "{{secret}}";
        };
    }

    private static String sanitizeAuthorization(String value) {
        String lower = safe(value, "").toLowerCase(Locale.ROOT);
        if (lower.startsWith("bearer ")) {
            return "Bearer {{bearerToken}}";
        }
        if (lower.startsWith("basic ")) {
            return "Basic {{basicAuth}}";
        }
        if (lower.startsWith("digest ")) {
            return "Digest {{digestAuth}}";
        }
        return "{{authorization}}";
    }

    static String sanitizeBody(String body, String contentType) {
        if (contentType.contains("json")) {
            try {
                Object parsed = MiniJson.parse(body);
                sanitizeJsonValue(parsed);
                return MiniJson.stringify(parsed);
            } catch (IllegalArgumentException ignored) {
                // Preserve best-effort protection for malformed captured JSON.
                return sanitizeMalformedJson(body);
            }
        }
        if (contentType.contains("application/x-www-form-urlencoded")) {
            StringBuilder rebuilt = new StringBuilder();
            for (String pair : body.split("&", -1)) {
                if (rebuilt.length() > 0) rebuilt.append('&');
                String[] parts = pair.split("=", 2);
                String decodedKey = decodeFormComponent(parts[0]);
                String lower = normalizeKey(decodedKey);
                rebuilt.append(parts[0]);
                if (parts.length == 2) {
                    rebuilt.append('=');
                    if (isSensitiveKey(lower)) rebuilt.append("%7B%7Bsecret%7D%7D");
                    else rebuilt.append(parts[1]);
                }
            }
            return rebuilt.toString();
        }
        return body;
    }

    private static void sanitizeJsonValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    continue;
                }
                if (isSensitiveKey(normalizeKey(key))) {
                    @SuppressWarnings("unchecked")
                    Map<Object, Object> mutable = (Map<Object, Object>) map;
                    mutable.put(key, "{{secret}}");
                } else {
                    sanitizeJsonValue(entry.getValue());
                }
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                sanitizeJsonValue(item);
            }
        }
    }

    private static String sanitizeMalformedJson(String body) {
        return body.replaceAll(
                "(?i)(\\\"(?:access[_-]?token|refresh[_-]?token|id[_-]?token|api[_-]?key|password|passwd|secret|session|authorization)\\\"\\s*:\\s*)"
                        + "(?:\\\"(?:\\\\.|[^\\\"\\\\])*\\\"|-?(?:\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)|true|false|null)",
                "$1\"{{secret}}\""
        );
    }

    private static String normalizeKey(String key) {
        return key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replace(" ", "");
    }

    private static boolean isSensitiveKey(String lower) {
        return lower.contains("token") || lower.contains("password") || lower.contains("passwd")
                || lower.contains("secret") || lower.contains("apikey")
                || lower.contains("session") || lower.contains("authorization");
    }

    private static boolean isSensitiveQueryKey(String key) {
        String normalized = normalizeKey(safe(key, ""));
        return normalized.equals("key")
                || normalized.equals("code")
                || normalized.equals("sig")
                || normalized.contains("token")
                || normalized.contains("apikey")
                || normalized.contains("secret")
                || normalized.contains("password")
                || normalized.contains("passwd")
                || normalized.contains("session")
                || normalized.contains("authorization")
                || normalized.contains("signature");
    }

    private static String languageFor(String contentType) {
        if (contentType.contains("json")) return "json";
        if (contentType.contains("xml")) return "xml";
        if (contentType.contains("html")) return "html";
        if (contentType.contains("javascript")) return "javascript";
        return "text";
    }

    private static String decodeQueryComponent(String value) {
        // URLDecoder implements HTML form rules and treats '+' as a space.
        // Escaping literal plus signs first preserves raw query semantics.
        return decodeFormComponent(safe(value, "").replace("+", "%2B"));
    }

    private static String decodeFormComponent(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static String safe(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
