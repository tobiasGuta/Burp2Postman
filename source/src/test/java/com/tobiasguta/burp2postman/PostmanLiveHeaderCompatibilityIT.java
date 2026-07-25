package com.tobiasguta.burp2postman;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in integration test for a scratch Postman collection. Run with:
 * POSTMAN_API_KEY and POSTMAN_TEST_COLLECTION_ID set, then
 * mvn -Plive-postman verify.
 */
class PostmanLiveHeaderCompatibilityIT {
    @Test
    void duplicateAndHostHeadersSurviveBothPayloadFormats() throws Exception {
        String apiKey = System.getenv("POSTMAN_API_KEY");
        String collectionId = System.getenv("POSTMAN_TEST_COLLECTION_ID");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "POSTMAN_API_KEY is required");
        assumeTrue(collectionId != null && !collectionId.isBlank(),
                "POSTMAN_TEST_COLLECTION_ID must identify a disposable collection");

        PostmanClient client = new PostmanClient();
        ApiEndpoint endpoint = ApiEndpoint.defaultEndpoint();
        String suffix = UUID.randomUUID().toString();
        String structuredName = "Burp2Postman structured compatibility " + suffix;
        String legacyName = "Burp2Postman legacy compatibility " + suffix;
        List<String> createdIds = new ArrayList<>();

        try {
            createdIds.add(client.createRequest(endpoint, apiKey, collectionId, "", payload(structuredName, false)));
            createdIds.add(client.createRequest(endpoint, apiKey, collectionId, "", payload(legacyName, true)));

            Map<String, Object> structured = awaitItem(client, endpoint, apiKey, collectionId, structuredName);
            Map<String, Object> legacy = awaitItem(client, endpoint, apiKey, collectionId, legacyName);
            assertHeaders(structured);
            assertHeaders(legacy);
        } finally {
            for (String requestId : createdIds) {
                if (requestId == null || requestId.isBlank()) continue;
                try {
                    client.deleteRequest(endpoint, apiKey, collectionId, requestId);
                } catch (Exception ignored) {
                    // The target is explicitly required to be a disposable test collection.
                }
            }
        }
    }

    private static Map<String, Object> payload(String name, boolean legacy) {
        Map<String, Object> payload = MiniJson.object();
        payload.put("name", name);
        payload.put("method", "GET");
        payload.put("url", "https://origin.example/header-compatibility");
        if (legacy) {
            payload.put("headers",
                    "Host: virtual.example\nX-Duplicate: first\nX-Duplicate: second\n");
        } else {
            List<Object> headers = MiniJson.array();
            headers.add(header("Host", "virtual.example"));
            headers.add(header("X-Duplicate", "first"));
            headers.add(header("X-Duplicate", "second"));
            payload.put("headers", headers);
        }
        return payload;
    }

    private static Map<String, Object> header(String key, String value) {
        Map<String, Object> header = MiniJson.object();
        header.put("key", key);
        header.put("value", value);
        return header;
    }

    private static Map<String, Object> awaitItem(
            PostmanClient client,
            ApiEndpoint endpoint,
            String apiKey,
            String collectionId,
            String name
    ) throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            Map<String, Object> root = client.getCollectionDocument(endpoint, apiKey, collectionId);
            Map<String, Object> item = findByName(
                    MiniJson.asArray(MiniJson.asObject(root.get("collection")).get("item")), name);
            if (item != null) return item;
            Thread.sleep(1_000);
        }
        return null;
    }

    private static Map<String, Object> findByName(List<Object> items, String name) {
        for (Object value : items) {
            Map<String, Object> item = MiniJson.asObject(value);
            if (name.equals(item.get("name"))) return item;
            Map<String, Object> nested = findByName(MiniJson.asArray(item.get("item")), name);
            if (nested != null) return nested;
        }
        return null;
    }

    private static void assertHeaders(Map<String, Object> item) {
        assertNotNull(item, "Created request did not appear in the collection");
        List<Object> headers = MiniJson.asArray(MiniJson.asObject(item.get("request")).get("header"));
        List<String> duplicateValues = headers.stream()
                .map(MiniJson::asObject)
                .filter(header -> "X-Duplicate".equalsIgnoreCase(MiniJson.text(header, "key")))
                .map(header -> MiniJson.text(header, "value"))
                .toList();
        assertEquals(List.of("first", "second"), duplicateValues);
        assertEquals("virtual.example", headers.stream()
                .map(MiniJson::asObject)
                .filter(header -> "Host".equalsIgnoreCase(MiniJson.text(header, "key")))
                .map(header -> MiniJson.text(header, "value"))
                .findFirst()
                .orElse(null));
    }
}
