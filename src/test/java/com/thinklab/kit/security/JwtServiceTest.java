package com.thinklab.kit.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import io.micronaut.http.HttpMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtServiceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");

    private static SecurityProperties properties(String secret) {
        SecurityProperties p = new SecurityProperties();
        p.setSecret(secret);
        return p;
    }

    private static JwtService service(SecurityProperties p, Instant now) {
        return new JwtService(p, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("an issued token verifies back to the same identity")
    void roundTrip() {
        JwtService jwt = service(properties(SECRET), NOW);

        AuthenticatedPrincipal principal = jwt.verify(jwt.issue("user-1", "tenant-1", Role.OPERATOR));

        assertEquals(new AuthenticatedPrincipal("user-1", "tenant-1", Role.OPERATOR), principal);
    }

    @Test
    @DisplayName("the public constructor uses the system clock")
    void systemClock() {
        JwtService jwt = new JwtService(properties(SECRET));

        assertEquals("u", jwt.verify(jwt.issue("u", "t", Role.ADMIN)).subject());
    }

    @Test
    @DisplayName("issue rejects null arguments and a missing or short secret")
    void issueGuards() {
        JwtService jwt = service(properties(SECRET), NOW);
        assertThrows(NullPointerException.class, () -> new JwtService(null));
        assertThrows(NullPointerException.class, () -> jwt.issue(null, "t", Role.ADMIN));
        assertThrows(NullPointerException.class, () -> jwt.issue("u", null, Role.ADMIN));
        assertThrows(NullPointerException.class, () -> jwt.issue("u", "t", null));
        assertThrows(IllegalStateException.class, () -> service(properties(null), NOW).issue("u", "t", Role.ADMIN));
        assertThrows(IllegalStateException.class, () -> service(properties("too-short"), NOW).issue("u", "t", Role.ADMIN));
    }

    @Test
    @DisplayName("verify rejects missing, blank and malformed tokens")
    void verifyMalformed() {
        JwtService jwt = service(properties(SECRET), NOW);

        assertThrows(AuthenticationFailedException.class, () -> jwt.verify(null));
        assertThrows(AuthenticationFailedException.class, () -> jwt.verify(" "));
        assertThrows(AuthenticationFailedException.class, () -> jwt.verify("not.a.jwt"));
    }

    @Test
    @DisplayName("verify rejects a token signed with another secret")
    void wrongSecret() {
        String forged = service(properties("ffffffffffffffffffffffffffffffff"), NOW).issue("u", "t", Role.ADMIN);

        assertThrows(AuthenticationFailedException.class, () -> service(properties(SECRET), NOW).verify(forged));
    }

    @Test
    @DisplayName("verify rejects the none algorithm and any other algorithm")
    void wrongAlgorithm() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().subject("u").issuer("thinklab").claim("tid", "t").claim("role", "ADMIN")
                .expirationTime(Date.from(NOW.plusSeconds(60))).build();
        String none = new PlainJWT(claims).serialize();
        SignedJWT hs512 = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), claims);
        hs512.sign(new MACSigner("x".repeat(64).getBytes()));
        JwtService jwt = service(properties(SECRET), NOW);

        assertThrows(AuthenticationFailedException.class, () -> jwt.verify(none));
        assertThrows(AuthenticationFailedException.class, () -> jwt.verify(hs512.serialize()));
    }

    @Test
    @DisplayName("verify rejects an expired token and one without expiry")
    void expiry() throws Exception {
        String token = service(properties(SECRET), NOW).issue("u", "t", Role.ADMIN);
        JwtService later = service(properties(SECRET), NOW.plusSeconds(3601));
        assertThrows(AuthenticationFailedException.class, () -> later.verify(token));

        JWTClaimsSet noExpiry = new JWTClaimsSet.Builder().subject("u").issuer("thinklab").claim("tid", "t").claim("role", "ADMIN").build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), noExpiry);
        jwt.sign(new MACSigner(SECRET.getBytes()));
        assertThrows(AuthenticationFailedException.class, () -> service(properties(SECRET), NOW).verify(jwt.serialize()));
    }

    @Test
    @DisplayName("verify rejects an untrusted issuer")
    void issuer() {
        SecurityProperties other = properties(SECRET);
        other.setIssuer("someone-else");
        String token = service(other, NOW).issue("u", "t", Role.ADMIN);

        assertThrows(AuthenticationFailedException.class, () -> service(properties(SECRET), NOW).verify(token));
    }

    @Test
    @DisplayName("verify rejects tokens with missing claims or an unknown role")
    void claims() throws Exception {
        for (JWTClaimsSet claims : new JWTClaimsSet[]{
                base().claim("tid", "t").claim("role", "ADMIN").subject(null).build(),
                base().subject("u").claim("role", "ADMIN").build(),
                base().subject("u").claim("tid", "t").build(),
                base().subject("u").claim("tid", "t").claim("role", "GOD").build()}) {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(SECRET.getBytes()));
            assertThrows(AuthenticationFailedException.class, () -> service(properties(SECRET), NOW).verify(jwt.serialize()));
        }
    }

    private static JWTClaimsSet.Builder base() {
        return new JWTClaimsSet.Builder().issuer("thinklab").expirationTime(Date.from(NOW.plusSeconds(60)));
    }

    @Test
    @DisplayName("cryptographic failures are reported as an issuing error or a rejected token")
    void cryptoFailures() {
        class Broken extends JwtService {
            Broken() {
                super(properties(SECRET), Clock.fixed(NOW, ZoneOffset.UTC));
            }

            @Override
            com.nimbusds.jose.JWSSigner signerFor(byte[] secret) throws com.nimbusds.jose.JOSEException {
                throw new com.nimbusds.jose.JOSEException("no signer");
            }

            @Override
            com.nimbusds.jose.JWSVerifier verifierFor(byte[] secret) throws com.nimbusds.jose.JOSEException {
                throw new com.nimbusds.jose.JOSEException("no verifier");
            }
        }
        String valid = service(properties(SECRET), NOW).issue("u", "t", Role.ADMIN);

        assertThrows(IllegalStateException.class, () -> new Broken().issue("u", "t", Role.ADMIN));
        assertThrows(AuthenticationFailedException.class, () -> new Broken().verify(valid));
    }

    @Test
    @DisplayName("the token time-to-live is configurable")
    void ttl() {
        SecurityProperties p = properties(SECRET);
        p.setTtlSeconds(10);
        String token = service(p, NOW).issue("u", "t", Role.VIEWER);

        assertThrows(AuthenticationFailedException.class, () -> service(p, NOW.plusSeconds(11)).verify(token));
        assertEquals(Role.VIEWER, service(p, NOW.plusSeconds(5)).verify(token).role());
    }

    @Test
    @DisplayName("the role matrix is method based")
    void roleMatrix() {
        assertTrue(Role.VIEWER.allows(HttpMethod.GET));
        assertTrue(Role.VIEWER.allows(HttpMethod.HEAD));
        assertTrue(Role.VIEWER.allows(HttpMethod.OPTIONS));
        assertFalse(Role.VIEWER.allows(HttpMethod.POST));
        assertFalse(Role.VIEWER.allows(HttpMethod.PUT));
        assertTrue(Role.OPERATOR.allows(HttpMethod.POST));
        assertTrue(Role.OPERATOR.allows(HttpMethod.PUT));
        assertFalse(Role.OPERATOR.allows(HttpMethod.DELETE));
        assertTrue(Role.ADMIN.allows(HttpMethod.DELETE));
        assertTrue(Role.SERVICE.allows(HttpMethod.DELETE));
    }

    @Test
    @DisplayName("the client filter attaches a SERVICE token named after the calling service")
    void clientFilter() {
        SecurityProperties p = properties(SECRET);
        p.setServiceName("it-asset-registry-service");
        JwtService jwt = service(p, NOW);
        io.micronaut.http.MutableHttpRequest<?> request = io.micronaut.http.HttpRequest.GET("/x");

        new ServiceTokenClientFilter(jwt, p).addServiceToken(request);

        String header = request.getHeaders().get("Authorization");
        AuthenticatedPrincipal principal = jwt.verify(header.substring("Bearer ".length()));
        assertEquals("it-asset-registry-service", principal.subject());
        assertEquals("platform", principal.tenantId());
        assertEquals(Role.SERVICE, principal.role());
    }

    @Test
    @DisplayName("security properties expose their defaults and setters")
    void properties() {
        SecurityProperties p = new SecurityProperties();
        assertFalse(p.isEnabled());
        assertEquals("thinklab", p.getIssuer());
        assertEquals(3600, p.getTtlSeconds());
        assertEquals("thinklab-service", p.getServiceName());
        p.setServiceName("x");
        assertEquals("x", p.getServiceName());
        assertTrue(p.getPublicPaths().contains("/health"));
        p.setEnabled(true);
        p.setPublicPaths(java.util.List.of("/login"));
        assertTrue(p.isEnabled());
        assertEquals(java.util.List.of("/login"), p.getPublicPaths());
    }
}
