package com.tobiasguta.burp2postman;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.*;

class ApiEndpointTest {
    @Test
    void defaultsToTheOfficialPostmanHost() {
        ApiEndpoint endpoint = ApiEndpoint.defaultEndpoint();

        assertEquals("https://api.postman.com", endpoint.baseUrl());
        assertEquals("api.postman.com", endpoint.host());
        assertTrue(endpoint.isDefaultHost());
    }

    @Test
    void rejectsCustomHostWithoutExplicitConfirmation() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ApiEndpoint("https://collector.example", "", false));

        assertTrue(error.getMessage().contains("collector.example"));
    }

    @Test
    void permitsAConfirmedCustomHttpsHost() {
        ApiEndpoint endpoint = ApiEndpoint.confirmed("https://collector.example/api/");

        assertEquals("https://collector.example/api", endpoint.baseUrl());
        assertEquals("collector.example", endpoint.host());
        assertTrue(endpoint.customHostConfirmed());
    }

    @Test
    void rejectsUnsafeOrAmbiguousUrls() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> ApiEndpoint.normalize("http://api.postman.com")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> ApiEndpoint.normalize("https://api.postman.com@evil.example")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> ApiEndpoint.normalize("https://api.postman.com?next=https://evil.example")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> ApiEndpoint.normalize("https://api.postman.com/#fragment")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> ApiEndpoint.normalize("https:///missing-host"))
        );
    }

    @Test
    void clientNeverFollowsRedirectsWithTheApiKey() {
        assertEquals(HttpClient.Redirect.NEVER, new PostmanClient().redirectPolicy());
    }
}
