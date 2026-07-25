package com.tobiasguta.burp2postman;

import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.tobiasguta.burp2postman.RequestConverter.HeaderFormat.LEGACY_STRING;
import static com.tobiasguta.burp2postman.RequestConverter.HeaderFormat.STRUCTURED;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequestConverterTest {
    private final RequestConverter converter = new RequestConverter();

    @Test
    void structuredHeadersPreserveDuplicatesAndCustomHost() {
        HttpRequest request = request(
                "https://origin.example/path?x=1&x=2",
                "",
                header("Host", "virtual.example"),
                header("X-Duplicate", "first"),
                header("X-Duplicate", "second")
        );

        Map<String, Object> payload = converter.convert(
                request, false, new RequestConverter.Options(true, true, STRUCTURED));
        List<Object> headers = MiniJson.asArray(payload.get("headers"));

        assertEquals(List.of(
                Map.of("key", "Host", "value", "virtual.example"),
                Map.of("key", "X-Duplicate", "value", "first"),
                Map.of("key", "X-Duplicate", "value", "second")
        ), headers);
        assertEquals(2, MiniJson.asArray(payload.get("queryParams")).size());
    }

    @Test
    void legacyHeadersPreserveDuplicatesAndCustomHostAsLines() {
        HttpRequest request = request(
                "https://origin.example/path",
                "",
                header("Host", "virtual.example"),
                header("X-Duplicate", "first"),
                header("X-Duplicate", "second")
        );

        Map<String, Object> payload = converter.convert(
                request, false, new RequestConverter.Options(true, true, LEGACY_STRING));

        assertEquals(
                "Host: virtual.example\nX-Duplicate: first\nX-Duplicate: second\n",
                payload.get("headers")
        );
    }

    @Test
    void filtersTransportHeadersAndCanRemoveHost() {
        HttpRequest request = request(
                "https://origin.example/",
                "",
                header("Content-Length", "12"),
                header("Connection", "close"),
                header("Host", "virtual.example"),
                header("X-Keep", "yes")
        );

        Map<String, Object> payload = converter.convert(
                request, false, new RequestConverter.Options(false, true, STRUCTURED));

        assertEquals(List.of(Map.of("key", "X-Keep", "value", "yes")),
                MiniJson.asArray(payload.get("headers")));
    }

    @Test
    void recursivelySanitizesJsonObjectsArraysEscapesAndNonStringSecrets() {
        String input = """
                {
                  "Password": "p\\\\\\"w",
                  "profile": {
                    "api_key": 12345,
                    "items": [
                      {"refresh-Token": true},
                      {"safe": "visible", "nested": {"clientSecret": null}}
                    ]
                  }
                }
                """;

        Object sanitized = MiniJson.parse(RequestConverter.sanitizeBody(input, "application/json"));
        Map<String, Object> root = MiniJson.asObject(sanitized);
        Map<String, Object> profile = MiniJson.asObject(root.get("profile"));
        List<Object> items = MiniJson.asArray(profile.get("items"));

        assertEquals("{{secret}}", root.get("Password"));
        assertEquals("{{secret}}", profile.get("api_key"));
        assertEquals("{{secret}}", MiniJson.asObject(items.get(0)).get("refresh-Token"));
        assertEquals("visible", MiniJson.asObject(items.get(1)).get("safe"));
        assertEquals("{{secret}}",
                MiniJson.asObject(MiniJson.asObject(items.get(1)).get("nested")).get("clientSecret"));
    }

    @Test
    void malformedJsonUsesRegexFallbackForStringAndNumericSecrets() {
        String malformed = "{\"password\":\"escaped\\\\\\\"value\",\"apiKey\":123,";

        String sanitized = RequestConverter.sanitizeBody(malformed, "application/json");

        assertTrue(sanitized.contains("\"password\":\"{{secret}}\""));
        assertTrue(sanitized.contains("\"apiKey\":\"{{secret}}\""));
    }

    @Test
    void malformedUrlDoesNotDiscardTheCapturedRequest() {
        HttpRequest request = request("not a valid URI [", "body", header("Content-Type", "text/plain"));

        Map<String, Object> payload = assertDoesNotThrow(
                () -> converter.convert(request, false, RequestConverter.Options.defaults()));

        assertEquals("not a valid URI [", payload.get("url"));
        assertEquals(List.of(), payload.get("queryParams"));
        assertEquals("body", payload.get("rawModeData"));
    }

    @Test
    void sanitizedModeRemovesSecretsFromRawUrlAndStructuredQueryParameters() {
        String url = "https://origin.example/password-reset"
                + "?token=real-secret"
                + "&access%5Ftoken=a%2Bb"
                + "&language=C++"
                + "&encodedLanguage=C%2B%2B"
                + "&monkey=banana"
                + "&code=oauth-code"
                + "&signature=signed"
                + "#section";
        HttpRequest request = request(url, "");

        Map<String, Object> payload = converter.convert(
                request, true, new RequestConverter.Options(true, true, STRUCTURED));
        String sanitizedUrl = (String) payload.get("url");
        List<Object> query = MiniJson.asArray(payload.get("queryParams"));

        assertEquals(
                "https://origin.example/password-reset"
                        + "?token=%7B%7Bsecret%7D%7D"
                        + "&access%5Ftoken=%7B%7Bsecret%7D%7D"
                        + "&language=C++"
                        + "&encodedLanguage=C%2B%2B"
                        + "&monkey=banana"
                        + "&code=%7B%7Bsecret%7D%7D"
                        + "&signature=%7B%7Bsecret%7D%7D"
                        + "#section",
                sanitizedUrl
        );
        assertEquals("{{secret}}", queryValue(query, "token"));
        assertEquals("{{secret}}", queryValue(query, "access_token"));
        assertEquals("C++", queryValue(query, "language"));
        assertEquals("C++", queryValue(query, "encodedLanguage"));
        assertEquals("banana", queryValue(query, "monkey"));
        assertEquals("{{secret}}", queryValue(query, "code"));
        assertEquals("{{secret}}", queryValue(query, "signature"));
    }

    @Test
    void exactModePreservesRawQueryAndLiteralPlusCharacters() {
        String url = "https://origin.example/search?language=C++&encoded=C%2B%2B&token=real";
        Map<String, Object> payload = converter.convert(
                request(url, ""), false, RequestConverter.Options.defaults());
        List<Object> query = MiniJson.asArray(payload.get("queryParams"));

        assertEquals(url, payload.get("url"));
        assertEquals("C++", queryValue(query, "language"));
        assertEquals("C++", queryValue(query, "encoded"));
        assertEquals("real", queryValue(query, "token"));
    }

    @Test
    void sanitizedModeCoversSupportedSensitiveQueryKeyNames() {
        List<String> sensitiveKeys = List.of(
                "token", "access_token", "refresh_token", "api_key", "apikey", "key",
                "secret", "password", "session", "authorization", "code", "signature", "sig"
        );
        String queryString = sensitiveKeys.stream()
                .map(key -> key + "=value-for-" + key)
                .reduce((left, right) -> left + "&" + right)
                .orElseThrow();
        Map<String, Object> payload = converter.convert(
                request("https://origin.example/?" + queryString, ""),
                true,
                RequestConverter.Options.defaults()
        );
        List<Object> query = MiniJson.asArray(payload.get("queryParams"));
        String sanitizedUrl = (String) payload.get("url");

        for (String key : sensitiveKeys) {
            assertEquals("{{secret}}", queryValue(query, key), key);
            assertFalse(sanitizedUrl.contains("value-for-" + key), key);
        }
    }

    private static HttpRequest request(String url, String body, HttpHeader... headers) {
        HttpRequest request = mock(HttpRequest.class);
        when(request.method()).thenReturn("POST");
        when(request.url()).thenReturn(url);
        when(request.pathWithoutQuery()).thenReturn("/path");
        when(request.headers()).thenReturn(List.of(headers));
        when(request.bodyToString()).thenReturn(body);
        return request;
    }

    private static HttpHeader header(String name, String value) {
        HttpHeader header = mock(HttpHeader.class);
        when(header.name()).thenReturn(name);
        when(header.value()).thenReturn(value);
        return header;
    }

    private static String queryValue(List<Object> query, String key) {
        return query.stream()
                .map(MiniJson::asObject)
                .filter(parameter -> key.equals(parameter.get("key")))
                .map(parameter -> String.valueOf(parameter.get("value")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing query parameter: " + key));
    }
}
