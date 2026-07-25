package com.tobiasguta.burp2postman;

import burp.api.montoya.persistence.Preferences;

import static com.tobiasguta.burp2postman.Models.Destination;
import static com.tobiasguta.burp2postman.Models.FolderRef;
import static com.tobiasguta.burp2postman.Models.ItemRef;

final class ConfigStore {
    private static final String PREFIX = "burp2postman.";
    private final Preferences preferences;

    ConfigStore(Preferences preferences) {
        this.preferences = preferences;
    }

    String baseUrl() {
        return valueOrDefault(get("baseUrl"), "https://api.postman.com");
    }

    void baseUrl(String value) {
        set("baseUrl", value);
    }

    boolean customEndpointEnabled() {
        return Boolean.TRUE.equals(preferences.getBoolean(PREFIX + "customEndpointEnabled"));
    }

    void customEndpointEnabled(boolean value) {
        preferences.setBoolean(PREFIX + "customEndpointEnabled", value);
    }

    boolean rememberApiKey() {
        Boolean value = preferences.getBoolean(PREFIX + "rememberApiKey");
        return Boolean.TRUE.equals(value);
    }

    void rememberApiKey(boolean remember) {
        preferences.setBoolean(PREFIX + "rememberApiKey", remember);
        if (!remember) {
            preferences.deleteString(PREFIX + "apiKey");
        }
    }

    String apiKey() {
        return rememberApiKey() ? valueOrDefault(get("apiKey"), "") : "";
    }

    void apiKey(String value) {
        if (rememberApiKey()) {
            set("apiKey", value);
        }
    }

    boolean preserveHostHeader() {
        Boolean value = preferences.getBoolean(PREFIX + "preserveHostHeader");
        return value == null || value;
    }

    void preserveHostHeader(boolean value) {
        preferences.setBoolean(PREFIX + "preserveHostHeader", value);
    }

    boolean removeTransportHeaders() {
        Boolean value = preferences.getBoolean(PREFIX + "removeTransportHeaders");
        return value == null || value;
    }

    void removeTransportHeaders(boolean value) {
        preferences.setBoolean(PREFIX + "removeTransportHeaders", value);
    }

    RequestConverter.HeaderFormat headerFormat() {
        String value = get("headerFormat");
        if (value != null) {
            try {
                return RequestConverter.HeaderFormat.valueOf(value);
            } catch (IllegalArgumentException ignored) {
                // Fall through to the forward-compatible default.
            }
        }
        return RequestConverter.HeaderFormat.STRUCTURED;
    }

    void headerFormat(RequestConverter.HeaderFormat value) {
        set("headerFormat", value == null
                ? RequestConverter.HeaderFormat.STRUCTURED.name()
                : value.name());
    }

    Destination destination() {
        String endpointBaseUrl = get("destinationEndpointBaseUrl");
        String workspaceId = get("workspaceId");
        String collectionId = get("collectionId");
        if (endpointBaseUrl == null || endpointBaseUrl.isBlank()
                || workspaceId == null || workspaceId.isBlank()
                || collectionId == null || collectionId.isBlank()) {
            return null;
        }

        ItemRef workspace = new ItemRef(workspaceId, valueOrDefault(get("workspaceName"), workspaceId));
        ItemRef collection = new ItemRef(collectionId, valueOrDefault(get("collectionName"), collectionId));
        String folderId = valueOrDefault(get("folderId"), "");
        FolderRef folder = folderId.isBlank()
                ? null
                : new FolderRef(folderId, valueOrDefault(get("folderName"), folderId),
                valueOrDefault(get("folderPath"), valueOrDefault(get("folderName"), folderId)));
        try {
            return new Destination(endpointBaseUrl, workspace, collection, folder);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    void destination(Destination destination) {
        if (destination == null) {
            clearDestination();
            return;
        }

        set("destinationEndpointBaseUrl", destination.endpointBaseUrl());
        set("workspaceId", destination.workspace().id());
        set("workspaceName", destination.workspace().name());
        set("collectionId", destination.collection().id());
        set("collectionName", destination.collection().name());
        if (destination.folder() == null || destination.folder().id().isBlank()) {
            preferences.deleteString(PREFIX + "folderId");
            preferences.deleteString(PREFIX + "folderName");
            preferences.deleteString(PREFIX + "folderPath");
        } else {
            set("folderId", destination.folder().id());
            set("folderName", destination.folder().name());
            set("folderPath", destination.folder().path());
        }
    }

    private void clearDestination() {
        for (String key : new String[]{
                "destinationEndpointBaseUrl", "workspaceId", "workspaceName", "collectionId", "collectionName",
                "folderId", "folderName", "folderPath"
        }) {
            preferences.deleteString(PREFIX + key);
        }
    }

    private String get(String key) {
        return preferences.getString(PREFIX + key);
    }

    private void set(String key, String value) {
        preferences.setString(PREFIX + key, value == null ? "" : value);
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
