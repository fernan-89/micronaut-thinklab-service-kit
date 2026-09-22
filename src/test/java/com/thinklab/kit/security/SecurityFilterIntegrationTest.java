package com.thinklab.kit.security;

import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** End-to-end check of the filter against a real Netty server, including the header derivation. */
@MicronautTest
@Property(name = "thinklab.security.enabled", value = "true")
@Property(name = "thinklab.security.public-paths", value = "/open,/health/")
class SecurityFilterIntegrationTest {

    @io.micronaut.serde.annotation.Serdeable
    record Named(String name) {}

    @Controller("/")
    static class EchoController {
        @Get("/whoami")
        Map<String, String> whoami(@Header("X-Tenant-Id") String tenant, @Header("X-Executor") String executor, @Header("X-Role") String role) {
            return Map.of("tenant", tenant, "executor", executor, "role", role);
        }

        @Get("/open")
        String open() {
            return "open";
        }

        @Post("/echo-record")
        Map<String, String> echoRecord(@io.micronaut.http.annotation.Body Named body) {
            return Map.of("name", body.name());
        }

        @Post("/echo-body")
        Map<String, String> echoBody(@io.micronaut.http.annotation.Body Map<String, String> body, @Header("X-Tenant-Id") String tenant) {
            return Map.of("name", body.get("name"), "tenant", tenant);
        }

        @Post("/write")
        String write() {
            return "written";
        }

        @Delete("/erase")
        String erase() {
            return "erased";
        }

        @Get("/service-echo")
        Map<String, String> serviceEcho(@Header("X-Tenant-Id") String tenant, @Header(value = "X-Executor", defaultValue = "none") String executor) {
            return Map.of("tenant", tenant, "executor", executor);
        }
    }

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    JwtSigner signer;

    @Inject
    RevocationList revocations;

    private HttpStatus statusOf(HttpRequest<?> request) {
        try {
            return client.toBlocking().exchange(request).getStatus();
        } catch (HttpClientResponseException e) {
            return e.getStatus();
        }
    }

    @Test
    @DisplayName("public paths need no token")
    void publicPath() {
        assertEquals(HttpStatus.OK, statusOf(HttpRequest.GET("/open")));
        assertEquals(HttpStatus.NOT_FOUND, statusOf(HttpRequest.GET("/open/nested")));
    }

    @Test
    @DisplayName("a missing, non-bearer or invalid token is 401 with a Bearer challenge and an RFC 7807 body")
    void unauthenticated() {
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(HttpRequest.GET("/whoami")));
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(HttpRequest.GET("/whoami").header("Authorization", "Basic abc")));

        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().exchange(HttpRequest.GET("/whoami").bearerAuth("garbage")));
        assertEquals("Bearer", ex.getResponse().getHeaders().get("WWW-Authenticate"));
        assertEquals("ERR-AUTH-00401", ex.getResponse().getBody(Map.class).map(m -> m.get("error_code")).orElse(null));
    }

    @Test
    @DisplayName("the tenant and executor headers are derived from the token")
    void headersDerivedFromToken() {
        String token = signer.issue("user-7", "tenant-A", Role.OPERATOR, null);

        Map<?, ?> body = client.toBlocking().retrieve(HttpRequest.GET("/whoami").bearerAuth(token), Map.class);

        assertEquals("tenant-A", body.get("tenant"));
        assertEquals("OPERATOR", body.get("role"));
    }

    @Test
    @DisplayName("a client-supplied executor cannot impersonate someone else")
    void executorOverwritten() {
        String token = signer.issue("user-7", "tenant-A", Role.OPERATOR, null);

        Map<?, ?> body = client.toBlocking().retrieve(
                HttpRequest.GET("/whoami").bearerAuth(token).header("X-Tenant-Id", "tenant-A").header("X-Executor", "root").header("X-Role", "ADMIN"), Map.class);

        assertEquals("OPERATOR", body.get("role"));
    }

    @Test
    @DisplayName("a conflicting tenant header is 403")
    void tenantMismatch() {
        String token = signer.issue("user-7", "tenant-A", Role.OPERATOR, null);

        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().exchange(HttpRequest.GET("/whoami").bearerAuth(token).header("X-Tenant-Id", "tenant-B")));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals("ERR-AUTH-00403", ex.getResponse().getBody(Map.class).map(m -> m.get("error_code")).orElse(null));
    }

    @Test
    @DisplayName("a request body survives the header derivation")
    void bodyIsPreserved() {
        String token = signer.issue("user-7", "tenant-A", Role.OPERATOR, null);

        Map<?, ?> echoed = client.toBlocking().retrieve(
                HttpRequest.POST("/echo-body", Map.of("name", "srv-01")).bearerAuth(token), Map.class);

        assertEquals("srv-01", echoed.get("name"));
        assertEquals("tenant-A", echoed.get("tenant"));
    }

    @Test
    @DisplayName("a record body survives the header derivation")
    void recordBodyIsPreserved() {
        String token = signer.issue("svc", "platform", Role.SERVICE, null);

        Map<?, ?> echoed = client.toBlocking().retrieve(
                HttpRequest.POST("/echo-record", "{\"name\":\"srv-02\"}").contentType("application/json").bearerAuth(token), Map.class);

        assertEquals("srv-02", echoed.get("name"));
    }

    @Test
    @DisplayName("a revoked session is rejected even though its token has not expired")
    void revokedSession() {
        String token = signer.issue("user-9", "tenant-A", Role.OPERATOR, "sess-77");
        assertEquals(HttpStatus.OK, statusOf(HttpRequest.GET("/whoami").bearerAuth(token)));

        revocations.revoke("sess-77", java.time.Instant.now().plusSeconds(60));

        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(HttpRequest.GET("/whoami").bearerAuth(token)));
    }

    @Test
    @DisplayName("roles gate the HTTP method")
    void rbac() {
        String viewer = signer.issue("v", "t", Role.VIEWER, null);
        String operator = signer.issue("o", "t", Role.OPERATOR, null);
        String admin = signer.issue("a", "t", Role.ADMIN, null);

        assertEquals(HttpStatus.FORBIDDEN, statusOf(HttpRequest.POST("/write", "{}").bearerAuth(viewer)));
        assertEquals(HttpStatus.OK, statusOf(HttpRequest.POST("/write", "{}").bearerAuth(operator)));
        assertEquals(HttpStatus.FORBIDDEN, statusOf(HttpRequest.DELETE("/erase").bearerAuth(operator)));
        assertEquals(HttpStatus.OK, statusOf(HttpRequest.DELETE("/erase").bearerAuth(admin)));
    }

    @Test
    @DisplayName("a service token keeps the tenant it sends and gets an executor when none is sent")
    void serviceToken() {
        String service = signer.issue("hash-client", "platform", Role.SERVICE, null);

        Map<?, ?> defaulted = client.toBlocking().retrieve(
                HttpRequest.GET("/service-echo").bearerAuth(service).header("X-Tenant-Id", "tenant-Z"), Map.class);
        Map<?, ?> explicit = client.toBlocking().retrieve(
                HttpRequest.GET("/service-echo").bearerAuth(service).header("X-Tenant-Id", "tenant-Z").header("X-Executor", "op-1"), Map.class);

        assertEquals("tenant-Z", defaulted.get("tenant"));
        assertEquals("hash-client", defaulted.get("executor"));
        assertEquals("op-1", explicit.get("executor"));
    }
}
