package com.thinklab.kit.security;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServiceTokenClientFilterTest {

    @Test
    @DisplayName("the client filter presents the provider's service token as a bearer credential")
    void attachesToken() {
        MutableHttpRequest<?> request = HttpRequest.GET("/x");

        new ServiceTokenClientFilter(() -> "service-token").addServiceToken(request);

        assertEquals("Bearer service-token", request.getHeaders().get("Authorization"));
    }
}
