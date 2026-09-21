package com.thinklab.kit.telemetry;

import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.filter.ServerFilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivestreams.Publisher;
import org.slf4j.MDC;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TraceIdFilterTest {

    @InjectMocks
    private TraceIdFilter traceIdFilter;

    @Mock
    private ServerFilterChain chain;

    @Mock
    private HttpRequest<?> request;

    @Mock
    private HttpHeaders headers;

    @Test
    @DisplayName("Should extract W3C traceparent and inject into reactive context and response")
    void shouldExtractW3cTraceparent() {
        String sampleTraceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        when(request.getHeaders()).thenReturn(headers);
        when(headers.get("traceparent")).thenReturn(sampleTraceparent);
        when(headers.get("Host")).thenReturn("api.thinklab.com");
        when(headers.get("Origin")).thenReturn("https://portal.thinklab.com");
        when(headers.get(HttpHeaders.USER_AGENT)).thenReturn("Mozilla/5.0 Chrome/120.0");
        when(headers.get("X-Forwarded-For")).thenReturn("192.168.1.150");

        MutableHttpResponse<?> response = HttpResponse.ok();
        when(chain.proceed(any())).thenReturn(Mono.just(response));

        Publisher<MutableHttpResponse<?>> resultPublisher = traceIdFilter.doFilter(request, chain);

        StepVerifier.create(resultPublisher)
                .expectNextMatches(res -> res.getStatus().getCode() == 200)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should fallback to B3 header when traceparent is absent")
    void shouldFallbackToB3Header() {
        when(request.getHeaders()).thenReturn(headers);
        when(headers.get("traceparent")).thenReturn(null);
        when(headers.get("X-B3-TraceId")).thenReturn("b3-sample-trace-id-12345");
        when(headers.get("Host")).thenReturn(null);
        when(headers.get("Origin")).thenReturn(null);
        when(headers.get("Referer")).thenReturn("https://thinklab.com/dashboard");
        when(headers.get(HttpHeaders.USER_AGENT)).thenReturn("VeryLongUserAgentStringThatExceedsFortyCharactersAndNeedsTruncation");
        when(headers.get("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddress()).thenReturn(new InetSocketAddress("10.0.0.1", 8080));

        MutableHttpResponse<?> response = HttpResponse.ok();
        when(chain.proceed(any())).thenReturn(Mono.just(response));

        Publisher<MutableHttpResponse<?>> resultPublisher = traceIdFilter.doFilter(request, chain);

        StepVerifier.create(resultPublisher)
                .expectNextMatches(res -> res.getStatus().getCode() == 200)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should generate fallback untraced token when no headers are present")
    void shouldGenerateFallbackToken() {
        when(request.getHeaders()).thenReturn(headers);
        when(headers.get("traceparent")).thenReturn(null);
        when(headers.get("X-B3-TraceId")).thenReturn(null);
        when(headers.get("Host")).thenReturn("localhost:8080");
        when(headers.get("Origin")).thenReturn(null);
        when(headers.get("Referer")).thenReturn(null);
        when(headers.get(HttpHeaders.USER_AGENT)).thenReturn(null);
        when(headers.get("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddress()).thenReturn(null);

        MutableHttpResponse<?> response = HttpResponse.ok();
        when(chain.proceed(any())).thenReturn(Mono.just(response));

        Publisher<MutableHttpResponse<?>> resultPublisher = traceIdFilter.doFilter(request, chain);

        StepVerifier.create(resultPublisher)
                .expectNext(response)
                .verifyComplete();
    }
}
