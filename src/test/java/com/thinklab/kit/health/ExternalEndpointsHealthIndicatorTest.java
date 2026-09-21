package com.thinklab.kit.health;

import com.sun.net.httpserver.HttpServer;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.HealthResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ExternalEndpointsHealthIndicatorTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/up", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/broken", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private HealthResult resultOf(ExternalEndpointsHealthIndicator indicator) {
        return Mono.from(indicator.getResult()).block();
    }

    @Test
    @DisplayName("no configured endpoints reports UP with the resolved runtime context")
    void noEndpointsIsUp() {
        HealthResult result = resultOf(new ExternalEndpointsHealthIndicator(null));

        assertEquals(HealthStatus.UP, result.getStatus());
        Map<?, ?> details = (Map<?, ?>) result.getDetails();
        assertNotNull(details.get("resolvedEnvironment"));
        assertNotNull(details.get("resolvedHostname"));
    }

    @Test
    @DisplayName("an empty endpoint map behaves like no configuration")
    void emptyEndpointsIsUp() {
        assertEquals(HealthStatus.UP, resultOf(new ExternalEndpointsHealthIndicator(Map.of())).getStatus());
    }

    @Test
    @DisplayName("every reachable endpoint answering 2xx/3xx is UP")
    void reachableEndpointsAreUp() {
        HealthResult result = resultOf(new ExternalEndpointsHealthIndicator(Map.of("dep", baseUrl + "/up")));

        assertEquals(HealthStatus.UP, result.getStatus());
        assertEquals("UP", ((Map<?, ?>) result.getDetails()).get("dep"));
    }

    @Test
    @DisplayName("an endpoint answering 5xx turns the aggregate DOWN with the HTTP status in the detail")
    void serverErrorIsDown() {
        Map<String, String> endpoints = new LinkedHashMap<>();
        endpoints.put("good", baseUrl + "/up");
        endpoints.put("bad", baseUrl + "/broken");

        HealthResult result = resultOf(new ExternalEndpointsHealthIndicator(endpoints));

        assertEquals(HealthStatus.DOWN, result.getStatus());
        Map<?, ?> details = (Map<?, ?>) result.getDetails();
        assertEquals("UP", details.get("good"));
        assertEquals("DOWN (HTTP 500)", details.get("bad"));
    }

    @Test
    @DisplayName("an unreachable endpoint turns the aggregate DOWN instead of failing the probe")
    void unreachableIsDown() {
        server.stop(0);

        HealthResult result = resultOf(new ExternalEndpointsHealthIndicator(Map.of("gone", baseUrl + "/up")));

        assertEquals(HealthStatus.DOWN, result.getStatus());
        assertTrue(String.valueOf(((Map<?, ?>) result.getDetails()).get("gone")).startsWith("DOWN"));
    }

    @Test
    @DisplayName("the startup warmup tolerates both configured and missing targets")
    void startupWarmup() {
        StartupEvent event = mock(StartupEvent.class);

        new ExternalEndpointsHealthIndicator(null).onApplicationEvent(event);
        new ExternalEndpointsHealthIndicator(Map.of("dep", baseUrl + "/up")).onApplicationEvent(event);
        new ExternalEndpointsHealthIndicator(Map.of("bad", baseUrl + "/broken")).onApplicationEvent(event);
    }

    @Test
    @DisplayName("the startup warmup rejects a null event")
    void startupNull() {
        assertThrows(NullPointerException.class, () -> new ExternalEndpointsHealthIndicator(null).onApplicationEvent(null));
    }

    @Test
    @DisplayName("a warm-up that outlives its barrier is abandoned without failing startup")
    void startupWarmupTimeout() {
        new ExternalEndpointsHealthIndicator(Map.of("slow", baseUrl + "/slow"), java.time.Duration.ofMillis(30))
                .onApplicationEvent(mock(StartupEvent.class));
    }
}
