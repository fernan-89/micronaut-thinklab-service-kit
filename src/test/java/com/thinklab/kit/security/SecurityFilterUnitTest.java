package com.thinklab.kit.security;

import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpHeaders;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.filter.ServerFilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Covers the fallback used when the server hands the filter an immutable request. */
class SecurityFilterUnitTest {

    @Test
    @DisplayName("an immutable request is mutated into a derived copy that carries the token identity")
    void immutableRequestFallback() {
        SecurityProperties properties = new SecurityProperties();
        properties.setSecret("0123456789abcdef0123456789abcdef");
        JwtService jwt = new JwtService(properties);
        String token = jwt.issue("user-7", "tenant-A", Role.OPERATOR);

        HttpRequest<?> request = mock(HttpRequest.class);
        HttpHeaders headers = mock(HttpHeaders.class);
        when(request.getPath()).thenReturn("/x");
        when(request.getMethod()).thenReturn(HttpMethod.GET);
        when(request.getHeaders()).thenReturn(headers);
        when(headers.get(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer " + token);
        MutableHttpRequest<?> derived = mock(MutableHttpRequest.class);
        MutableHttpHeaders derivedHeaders = mock(MutableHttpHeaders.class);
        when(request.mutate()).thenReturn((MutableHttpRequest) derived);
        when(derived.getHeaders()).thenReturn(derivedHeaders);
        ServerFilterChain chain = mock(ServerFilterChain.class);
        when(chain.proceed(any())).thenReturn(Mono.just(HttpResponse.ok()));

        StepVerifier.create(new SecurityFilter(jwt, properties).doFilter(request, chain))
                .expectNextMatches(response -> response.getStatus().getCode() == 200)
                .verifyComplete();

        verify(derivedHeaders).set("X-Tenant-Id", "tenant-A");
        verify(derivedHeaders).set("X-Executor", "user-7");
        verify(derivedHeaders).set("X-Role", "OPERATOR");
        verify(chain).proceed(derived);
        assertEquals(HttpMethod.GET, request.getMethod());
    }
}
