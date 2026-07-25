package com.tobiasguta.burp2postman;

import burp.api.montoya.persistence.Preferences;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static com.tobiasguta.burp2postman.Models.Destination;
import static com.tobiasguta.burp2postman.Models.ItemRef;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DestinationEndpointTest {
    @Test
    void destinationNormalizesAndEnforcesItsEndpointIdentity() {
        Destination destination = new Destination(
                "https://gateway.example/api/",
                new ItemRef("workspace", "Workspace"),
                new ItemRef("collection", "Collection"),
                null
        );

        assertEquals("https://gateway.example/api", destination.endpointBaseUrl());
        assertTrue(destination.isFor(ApiEndpoint.confirmed("https://gateway.example/api")));
        assertFalse(destination.isFor(ApiEndpoint.defaultEndpoint()));
    }

    @Test
    void configStorePersistsAndRestoresDestinationEndpoint() {
        Map<String, String> values = new HashMap<>();
        Preferences preferences = preferencesBackedBy(values);
        ConfigStore store = new ConfigStore(preferences);
        Destination expected = new Destination(
                ApiEndpoint.DEFAULT_BASE_URL,
                new ItemRef("workspace", "Workspace"),
                new ItemRef("collection", "Collection"),
                null
        );

        store.destination(expected);

        assertEquals(ApiEndpoint.DEFAULT_BASE_URL,
                values.get("burp2postman.destinationEndpointBaseUrl"));
        assertEquals(expected, store.destination());
    }

    @Test
    void legacyDestinationWithoutEndpointIdentityIsRejected() {
        Map<String, String> values = new HashMap<>();
        values.put("burp2postman.workspaceId", "workspace");
        values.put("burp2postman.collectionId", "collection");
        ConfigStore store = new ConfigStore(preferencesBackedBy(values));

        assertNull(store.destination());
    }

    private static Preferences preferencesBackedBy(Map<String, String> values) {
        Preferences preferences = mock(Preferences.class);
        when(preferences.getString(anyString()))
                .thenAnswer(invocation -> values.get(invocation.getArgument(0, String.class)));
        doAnswer(invocation -> {
            values.put(
                    invocation.getArgument(0, String.class),
                    invocation.getArgument(1, String.class)
            );
            return null;
        }).when(preferences).setString(anyString(), anyString());
        doAnswer(invocation -> {
            values.remove(invocation.getArgument(0, String.class));
            return null;
        }).when(preferences).deleteString(anyString());
        return preferences;
    }
}
