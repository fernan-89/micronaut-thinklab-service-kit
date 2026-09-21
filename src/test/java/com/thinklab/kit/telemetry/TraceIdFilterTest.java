package com.thinklab.kit.telemetry;

import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.http.filter.ServerFilterPhase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TraceIdFilterTest {

    private static final String W3C = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    /** Filter whose reverse DNS is deterministic and offline. */
    private static class OfflineFilter extends TraceIdFilter {
        boolean failLookup;

        @Override
        String reverseLookup(String clientIp) throws UnknownHostException {
            if (failLookup) {
                throw new UnknownHostException(clientIp);
            }
            return "host-of-" + clientIp;
        }
    }

    private final OfflineFilter filter = new OfflineFilter();
    private final Map<String, String> headerValues = new HashMap<>();
    private final Map<String, String> mdcSeenByChain = new HashMap<>();
    private final Map<String, Object> attributesSet = new HashMap<>();
    private InetSocketAddress remote;

    @BeforeEach
    void clean() {
        MDC.clear();
    }

    @AfterEach
    void cleanAfter() {
        MDC.clear();
    }

    /** Runs the filter against a mocked request/chain and records what the downstream chain observed. */
    private void run() {
        HttpHeaders headers = mock(HttpHeaders.class);
        when(headers.get(anyString())).thenAnswer(inv -> headerValues.get(inv.<String>getArgument(0)));
        HttpRequest<?> request = mock(HttpRequest.class);
        when(request.getHeaders()).thenReturn(headers);
        when(request.getRemoteAddress()).thenReturn(remote);
        when(request.setAttribute(anyString(), any())).thenAnswer(inv -> {
            attributesSet.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        });
        ServerFilterChain chain = mock(ServerFilterChain.class);
        when(chain.proceed(any())).thenAnswer(inv -> {
            Map<String, String> copy = MDC.getCopyOfContextMap();
            if (copy != null) {
                mdcSeenByChain.putAll(copy);
            }
            MutableHttpResponse<?> ok = HttpResponse.ok();
            return Mono.just(ok);
        });

        StepVerifier.create(filter.doFilter(request, chain))
                .expectNextMatches(response -> response.getStatus().getCode() == 200)
                .verifyComplete();
    }

    @Test
    @DisplayName("the filter runs right after the tracing phase")
    void order() {
        assertEquals(ServerFilterPhase.TRACING.order() + 1, filter.getOrder());
    }

    @Test
    @DisplayName("a valid W3C traceparent supplies the trace id, exposed in MDC and as a request attribute")
    void w3cTraceparent() {
        headerValues.put("traceparent", W3C);
        headerValues.put("Host", "api.thinklab.com");
        headerValues.put("Origin", "https://portal.thinklab.com");
        headerValues.put(HttpHeaders.USER_AGENT, "Mozilla/5.0");
        headerValues.put("X-Forwarded-For", "203.0.113.9, 10.0.0.1");

        run();

        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", mdcSeenByChain.get("traceId"));
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", attributesSet.get("traceId"));
        assertEquals("api.thinklab.com", mdcSeenByChain.get("virtualHost"));
        assertEquals("https://portal.thinklab.com", mdcSeenByChain.get("clientOrigin"));
        assertEquals("Mozilla/5.0", mdcSeenByChain.get("userAgent"));
        assertEquals("203.0.113.***", mdcSeenByChain.get("clientIp"));
        assertEquals("host-of-203.0.113.9", mdcSeenByChain.get("externalClientHost"));
    }

    @Test
    @DisplayName("MDC is purged once the response completes")
    void mdcIsPurged() {
        headerValues.put("traceparent", W3C);

        run();

        assertNull(MDC.get("traceId"));
        assertNull(MDC.get("clientIp"));
        assertNull(MDC.get("externalClientHost"));
    }

    @Test
    @DisplayName("a too-short traceparent falls back to the B3 header")
    void shortTraceparentUsesB3() {
        headerValues.put("traceparent", "00-short");
        headerValues.put("X-B3-TraceId", "b3-trace-id");

        run();

        assertEquals("b3-trace-id", mdcSeenByChain.get("traceId"));
    }

    @Test
    @DisplayName("a blank B3 header falls back to the trace id already in MDC")
    void blankB3UsesMdc() {
        headerValues.put("X-B3-TraceId", "  ");
        MDC.put("traceId", "already-there");

        run();

        assertEquals("already-there", mdcSeenByChain.get("traceId"));
    }

    @Test
    @DisplayName("a blank trace id in MDC is ignored and a fresh UNTRACED id is generated")
    void blankMdcGeneratesId() {
        MDC.put("traceId", " ");

        run();

        assertTrue(mdcSeenByChain.get("traceId").startsWith("UNTRACED-"));
    }

    @Test
    @DisplayName("no trace source at all generates an UNTRACED id")
    void noTraceSource() {
        run();

        assertTrue(mdcSeenByChain.get("traceId").startsWith("UNTRACED-"));
    }

    @Test
    @DisplayName("missing headers use the documented placeholders")
    void placeholders() {
        run();

        assertEquals("unknown-host", mdcSeenByChain.get("virtualHost"));
        assertEquals("direct-client", mdcSeenByChain.get("clientOrigin"));
        assertEquals("UNKNOWN", mdcSeenByChain.get("userAgent"));
        assertEquals("127.0.0.***", mdcSeenByChain.get("clientIp"));
        assertEquals("local-mesh-client", mdcSeenByChain.get("externalClientHost"));
    }

    @Test
    @DisplayName("blank Host, Origin and User-Agent are treated as missing; Referer replaces a blank Origin")
    void blankHeaders() {
        headerValues.put("Host", " ");
        headerValues.put("Origin", " ");
        headerValues.put("Referer", "https://thinklab.com/dashboard");
        headerValues.put(HttpHeaders.USER_AGENT, " ");

        run();

        assertEquals("unknown-host", mdcSeenByChain.get("virtualHost"));
        assertEquals("https://thinklab.com/dashboard", mdcSeenByChain.get("clientOrigin"));
        assertEquals("UNKNOWN", mdcSeenByChain.get("userAgent"));
    }

    @Test
    @DisplayName("a blank Referer with no Origin is direct-client")
    void blankReferer() {
        headerValues.put("Referer", " ");

        run();

        assertEquals("direct-client", mdcSeenByChain.get("clientOrigin"));
    }

    @Test
    @DisplayName("a long User-Agent is truncated to 40 characters")
    void longUserAgent() {
        headerValues.put(HttpHeaders.USER_AGENT, "VeryLongUserAgentStringThatExceedsFortyCharactersAndNeedsTruncation");

        run();

        assertEquals("VeryLongUserAgentStringThatExceedsFor...", mdcSeenByChain.get("userAgent"));
        assertEquals(40, mdcSeenByChain.get("userAgent").length());
    }

    @Test
    @DisplayName("a blank X-Forwarded-For falls back to the socket address")
    void blankForwardedFor() {
        headerValues.put("X-Forwarded-For", " ");
        remote = new InetSocketAddress("10.1.2.3", 8080);

        run();

        assertEquals("10.1.2.***", mdcSeenByChain.get("clientIp"));
        assertEquals("local-mesh-client", mdcSeenByChain.get("externalClientHost"));
    }

    @Test
    @DisplayName("an unresolved socket address falls back to 127.0.0.1")
    void unresolvedSocket() {
        remote = InetSocketAddress.createUnresolved("nowhere.invalid", 80);

        run();

        assertEquals("127.0.0.***", mdcSeenByChain.get("clientIp"));
    }

    @Test
    @DisplayName("private and wildcard addresses are never reverse-resolved")
    void localAddressesAreMesh() {
        for (String ip : new String[]{"127.0.0.1", "0.0.0.0", "192.168.5.5", "10.9.9.9"}) {
            mdcSeenByChain.clear();
            headerValues.put("X-Forwarded-For", ip);

            run();

            assertEquals("local-mesh-client", mdcSeenByChain.get("externalClientHost"), ip);
        }
    }

    @Test
    @DisplayName("a failing reverse lookup falls back to the raw client address")
    void lookupFailure() {
        filter.failLookup = true;
        headerValues.put("X-Forwarded-For", "198.51.100.7");

        run();

        assertEquals("198.51.100.7", mdcSeenByChain.get("externalClientHost"));
    }

    @Test
    @DisplayName("IPv6 addresses are obfuscated at the last block")
    void ipv6() {
        headerValues.put("X-Forwarded-For", "2001:db8::1");

        run();

        assertEquals("2001:db8::***", mdcSeenByChain.get("clientIp"));
    }

    @Test
    @DisplayName("addresses with no usable separator are fully masked")
    void unusableSeparators() {
        for (String ip : new String[]{"localhost", ".5", ":1"}) {
            mdcSeenByChain.clear();
            headerValues.put("X-Forwarded-For", ip);

            run();

            assertEquals("***", mdcSeenByChain.get("clientIp"), ip);
        }
    }

    @Test
    @DisplayName("an empty first forwarded address is reported as UNKNOWN")
    void emptyForwardedAddress() {
        headerValues.put("X-Forwarded-For", ", 10.0.0.1");

        run();

        assertEquals("UNKNOWN", mdcSeenByChain.get("clientIp"));
    }

    @Test
    @DisplayName("the default reverse lookup resolves a literal address without failing")
    void defaultReverseLookup() throws Exception {
        assertNotNull(new TraceIdFilter().reverseLookup("127.0.0.1"));
    }
}
