package com.tobiasguta.burp2postman;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.tobiasguta.burp2postman.Models.FolderRef;
import static com.tobiasguta.burp2postman.Models.ItemRef;

final class PostmanClient {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;

    PostmanClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                // Do not forward X-API-Key through redirects. A caller must
                // explicitly configure and confirm the final API endpoint.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    List<ItemRef> getWorkspaces(ApiEndpoint endpoint, String apiKey) throws IOException, InterruptedException {
        requireApiKey(apiKey);
        List<ItemRef> results = new ArrayList<>();
        String cursor = null;
        int pages = 0;

        do {
            String path = "/workspaces?limit=100" + (cursor == null ? "" : "&cursor=" + encode(cursor));
            Map<String, Object> root = getJson(endpoint, path, apiKey);
            for (Object value : MiniJson.asArray(root.get("workspaces"))) {
                Map<String, Object> item = MiniJson.asObject(value);
                String id = firstText(item, "uid", "id");
                if (!id.isBlank()) {
                    results.add(new ItemRef(id, firstTextOr(item, "(unnamed)", "name")));
                }
            }
            Map<String, Object> meta = MiniJson.asObject(root.get("meta"));
            cursor = firstText(meta, "nextCursor", "next_cursor");
            pages++;
        } while (!cursor.isBlank() && pages < 100);

        results.sort(Comparator.comparing(ItemRef::name, String.CASE_INSENSITIVE_ORDER));
        return deduplicateItems(results);
    }

    List<ItemRef> getCollections(ApiEndpoint endpoint, String apiKey, String workspaceId)
            throws IOException, InterruptedException {
        requireApiKey(apiKey);
        if (workspaceId == null || workspaceId.isBlank()) {
            return List.of();
        }

        List<ItemRef> results = new ArrayList<>();
        int offset = 0;
        int limit = 100;
        int total;
        int pages = 0;
        do {
            String path = "/collections?workspace=" + encode(workspaceId)
                    + "&limit=" + limit + "&offset=" + offset;
            Map<String, Object> root = getJson(endpoint, path, apiKey);
            List<Object> collections = MiniJson.asArray(root.get("collections"));
            for (Object value : collections) {
                Map<String, Object> item = MiniJson.asObject(value);
                String id = firstText(item, "uid", "id");
                if (!id.isBlank()) {
                    results.add(new ItemRef(id, firstTextOr(item, "(unnamed)", "name")));
                }
            }
            Map<String, Object> meta = MiniJson.asObject(root.get("meta"));
            total = MiniJson.integer(meta, "total", results.size());
            offset += collections.size();
            pages++;
            if (collections.isEmpty()) {
                break;
            }
        } while (offset < total && pages < 100);

        results.sort(Comparator.comparing(ItemRef::name, String.CASE_INSENSITIVE_ORDER));
        return deduplicateItems(results);
    }

    List<FolderRef> getFolders(ApiEndpoint endpoint, String apiKey, String collectionId)
            throws IOException, InterruptedException {
        requireApiKey(apiKey);
        if (collectionId == null || collectionId.isBlank()) {
            return List.of();
        }

        Map<String, Object> root = getJson(endpoint, "/collections/" + encodePath(collectionId), apiKey);
        Map<String, Object> collection = MiniJson.asObject(root.get("collection"));
        List<FolderRef> folders = new ArrayList<>();
        collectFolders(MiniJson.asArray(collection.get("item")), "", folders);
        folders.sort(Comparator.comparing(FolderRef::path, String.CASE_INSENSITIVE_ORDER));
        return folders;
    }

    ItemRef createCollection(ApiEndpoint endpoint, String apiKey, String workspaceId, String name)
            throws IOException, InterruptedException {
        requireNonBlank(workspaceId, "Choose a workspace first.");
        requireNonBlank(name, "Collection name cannot be empty.");

        Map<String, Object> info = MiniJson.object();
        info.put("name", name.trim());
        info.put("schema", "https://schema.getpostman.com/json/collection/v2.1.0/collection.json");

        Map<String, Object> collection = MiniJson.object();
        collection.put("info", info);
        collection.put("item", MiniJson.array());

        Map<String, Object> body = MiniJson.object();
        body.put("collection", collection);

        Map<String, Object> root = sendJson(
                "POST",
                endpoint,
                "/collections?workspace=" + encode(workspaceId),
                apiKey,
                body
        );
        Map<String, Object> created = MiniJson.asObject(root.get("collection"));
        String id = firstText(created, "uid", "id");
        if (id.isBlank()) {
            throw new IOException("Postman created the collection but did not return its ID.");
        }
        return new ItemRef(id, firstTextOr(created, name.trim(), "name"));
    }

    FolderRef createFolder(ApiEndpoint endpoint, String apiKey, String collectionId, String parentFolderId, String name)
            throws IOException, InterruptedException {
        requireNonBlank(collectionId, "Choose a collection first.");
        requireNonBlank(name, "Folder name cannot be empty.");

        Map<String, Object> body = MiniJson.object();
        body.put("name", name.trim());
        if (parentFolderId != null && !parentFolderId.isBlank()) {
            body.put("folder", parentFolderId);
        }

        Map<String, Object> root = sendJson(
                "POST",
                endpoint,
                "/collections/" + encodePath(collectionId) + "/folders",
                apiKey,
                body
        );
        Map<String, Object> data = MiniJson.asObject(root.get("data"));
        if (data.isEmpty()) {
            data = MiniJson.asObject(root.get("folder"));
        }
        String id = firstText(data, "uid", "id", "model_id");
        if (id.isBlank()) {
            throw new IOException("Postman created the folder but did not return its ID.");
        }
        String returnedName = firstTextOr(data, name.trim(), "name");
        return new FolderRef(id, returnedName, returnedName);
    }

    String createRequest(
            ApiEndpoint endpoint,
            String apiKey,
            String collectionId,
            String folderId,
            Map<String, Object> request
    ) throws IOException, InterruptedException {
        requireNonBlank(collectionId, "Choose a collection first.");
        String path = "/collections/" + encodePath(collectionId) + "/requests";
        if (folderId != null && !folderId.isBlank()) {
            path += "?folderId=" + encode(folderId);
        }
        Map<String, Object> root = sendJson("POST", endpoint, path, apiKey, request);
        Map<String, Object> data = MiniJson.asObject(root.get("data"));
        return firstText(data, "uid", "id", "model_id").isBlank()
                ? firstText(root, "uid", "model_id", "id")
                : firstText(data, "uid", "id", "model_id");
    }

    Map<String, Object> getCollectionDocument(ApiEndpoint endpoint, String apiKey, String collectionId)
            throws IOException, InterruptedException {
        requireNonBlank(collectionId, "Choose a collection first.");
        return getJson(endpoint, "/collections/" + encodePath(collectionId), apiKey);
    }

    void deleteRequest(ApiEndpoint endpoint, String apiKey, String collectionId, String requestId)
            throws IOException, InterruptedException {
        requireNonBlank(collectionId, "Choose a collection first.");
        requireNonBlank(requestId, "Request ID is required.");
        HttpRequest request = requestBuilder(
                endpoint,
                "/collections/" + encodePath(collectionId) + "/requests/" + encodePath(requestId),
                apiKey
        ).DELETE().build();
        execute(request);
    }

    private Map<String, Object> getJson(ApiEndpoint endpoint, String path, String apiKey)
            throws IOException, InterruptedException {
        HttpRequest request = requestBuilder(endpoint, path, apiKey).GET().build();
        return execute(request);
    }

    private Map<String, Object> sendJson(
            String method,
            ApiEndpoint endpoint,
            String path,
            String apiKey,
            Map<String, Object> body
    ) throws IOException, InterruptedException {
        String payload = MiniJson.stringify(body);
        HttpRequest request = requestBuilder(endpoint, path, apiKey)
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        return execute(request);
    }

    private HttpRequest.Builder requestBuilder(ApiEndpoint endpoint, String path, String apiKey) {
        requireApiKey(apiKey);
        if (endpoint == null) {
            throw new IllegalArgumentException("Postman API endpoint is required.");
        }
        return HttpRequest.newBuilder(URI.create(endpoint.baseUrl() + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("X-API-Key", apiKey.trim())
                .header("User-Agent", "Burp2Postman/0.2.0 PostmanRuntime-compatible");
    }

    private Map<String, Object> execute(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        int status = response.statusCode();
        String body = response.body() == null ? "" : response.body();
        if (status < 200 || status >= 300) {
            throw new PostmanApiException(status, extractError(body));
        }
        if (body.isBlank()) {
            return MiniJson.object();
        }
        try {
            return MiniJson.asObject(MiniJson.parse(body));
        } catch (IllegalArgumentException e) {
            throw new IOException("Postman returned invalid JSON.", e);
        }
    }

    private static String extractError(String body) {
        if (body == null || body.isBlank()) {
            return "Postman returned an empty error response.";
        }
        try {
            Map<String, Object> root = MiniJson.asObject(MiniJson.parse(body));
            String direct = firstText(root, "error", "message", "detail");
            if (!direct.isBlank()) {
                return direct;
            }
            Map<String, Object> error = MiniJson.asObject(root.get("error"));
            String nested = firstText(error, "message", "detail", "name");
            if (!nested.isBlank()) {
                return nested;
            }
        } catch (Exception ignored) {
            // Fall back to the raw response below.
        }
        return body.length() > 500 ? body.substring(0, 500) + "…" : body;
    }

    private static void collectFolders(List<Object> items, String parentPath, List<FolderRef> output) {
        for (Object value : items) {
            Map<String, Object> item = MiniJson.asObject(value);
            Object childValue = item.get("item");
            if (!(childValue instanceof List<?>)) {
                continue; // Requests do not contain a nested item array.
            }
            List<Object> children = MiniJson.asArray(childValue);
            String name = firstTextOr(item, "(unnamed)", "name");
            String path = parentPath.isBlank() ? name : parentPath + " / " + name;
            String id = firstText(item, "uid", "id", "_postman_id");
            if (!id.isBlank()) {
                output.add(new FolderRef(id, name, path));
            }
            collectFolders(children, path, output);
        }
    }

    static String normalizeBaseUrl(String baseUrl) {
        return ApiEndpoint.normalize(baseUrl);
    }

    HttpClient.Redirect redirectPolicy() {
        return httpClient.followRedirects();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String encodePath(String value) {
        return encode(value).replace("+", "%20");
    }

    private static void requireApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Enter a Postman API key first.");
        }
    }

    private static void requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private static String firstText(Map<String, Object> object, String... keys) {
        for (String key : keys) {
            Object value = object.get(key);
            if (value instanceof String string && !string.isBlank()) {
                return string;
            }
        }
        return "";
    }

    private static String firstTextOr(Map<String, Object> object, String fallback, String... keys) {
        String value = firstText(object, keys);
        return value.isBlank() ? fallback : value;
    }

    private static List<ItemRef> deduplicateItems(List<ItemRef> values) {
        Map<String, ItemRef> unique = new LinkedHashMap<>();
        for (ItemRef value : values) {
            unique.putIfAbsent(value.id(), value);
        }
        return new ArrayList<>(unique.values());
    }

    static final class PostmanApiException extends IOException {
        private static final long serialVersionUID = 1L;
        private final int statusCode;

        PostmanApiException(int statusCode, String message) {
            super("Postman API returned HTTP " + statusCode + ": " + message);
            this.statusCode = statusCode;
        }

        int statusCode() {
            return statusCode;
        }
    }
}
