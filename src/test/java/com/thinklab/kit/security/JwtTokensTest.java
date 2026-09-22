package com.thinklab.kit.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import io.micronaut.http.HttpMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtTokensTest {

    private final Clock clock = Clock.fixed(TestKeys.NOW, ZoneOffset.UTC);
    private SecurityProperties properties;
    private LocalKeyStore store;
    private ECKey key;
    private RevocationList revocations;
    private JwtSigner signer;
    private JwtVerifier verifier;

    @BeforeEach
    void setUp() {
        key = TestKeys.newKey("kid-1");
        properties = TestKeys.propertiesWith(key);
        store = new LocalKeyStore(properties);
        revocations = new RevocationList(clock);
        signer = new JwtSigner(properties, store, clock);
        verifier = new JwtVerifier(properties, new KeyProvider(properties, store), revocations, clock);
    }

    private void assertRejected(String token) {
        assertThrows(AuthenticationFailedException.class, () -> verifier.verify(token));
    }

    @Test
    @DisplayName("an issued token verifies back to the same identity, with and without a session")
    void roundTrip() {
        assertEquals(new AuthenticatedPrincipal("u", "t", Role.OPERATOR, "sess-1"), verifier.verify(signer.issue("u", "t", Role.OPERATOR, "sess-1")));
        assertNull(verifier.verify(signer.issue("svc", "platform", Role.SERVICE, null)).sessionId());
    }

    @Test
    @DisplayName("public constructors work with the system clock and expose the token lifetime")
    void publicConstructors() {
        JwtSigner realSigner = new JwtSigner(properties, store);
        JwtVerifier realVerifier = new JwtVerifier(properties, new KeyProvider(properties, store), new RevocationList());

        assertEquals("u", realVerifier.verify(realSigner.issue("u", "t", Role.ADMIN, null)).subject());
        assertEquals(600, realSigner.ttlSeconds());
    }

    @Test
    @DisplayName("issue rejects null arguments")
    void issueGuards() {
        assertThrows(NullPointerException.class, () -> new JwtSigner(null, store));
        assertThrows(NullPointerException.class, () -> signer.issue(null, "t", Role.ADMIN, null));
        assertThrows(NullPointerException.class, () -> signer.issue("u", null, Role.ADMIN, null));
        assertThrows(NullPointerException.class, () -> signer.issue("u", "t", null, null));
    }

    @Test
    @DisplayName("missing, blank and malformed tokens are rejected")
    void malformed() {
        assertRejected(null);
        assertRejected(" ");
        assertRejected("not.a.jwt");
    }

    @Test
    @DisplayName("none, HMAC and any non-ES256 algorithm are rejected before any key is used")
    void wrongAlgorithm() throws Exception {
        JWTClaimsSet claims = TestKeys.validClaims().build();
        SignedJWT hmac = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("kid-1").build(), claims);
        hmac.sign(new MACSigner("0123456789abcdef0123456789abcdef".getBytes()));

        assertRejected(new PlainJWT(claims).serialize());
        assertRejected(hmac.serialize());
    }

    @Test
    @DisplayName("a token without a key id, with an unknown key id or signed by another key is rejected")
    void keyProblems() {
        JWTClaimsSet claims = TestKeys.validClaims().build();
        ECKey attacker = TestKeys.newKey("kid-1");

        assertRejected(TestKeys.sign(key, null, claims));
        assertRejected(TestKeys.sign(key, "unknown", claims));
        assertRejected(TestKeys.sign(attacker, "kid-1", claims));
    }

    @Test
    @DisplayName("a trusted key that is not an EC key is rejected")
    void nonEcKey() throws Exception {
        RSAKey rsa = new RSAKeyGenerator(2048).keyID("rsa-kid").generate();
        SecurityProperties withRsa = new SecurityProperties();
        withRsa.setPublicKey(new JWKSet(rsa.toPublicJWK()).toString());
        JwtVerifier rsaVerifier = new JwtVerifier(withRsa, new KeyProvider(withRsa, new LocalKeyStore(withRsa)), revocations, clock);

        assertThrows(AuthenticationFailedException.class,
                () -> rsaVerifier.verify(TestKeys.sign(key, "rsa-kid", TestKeys.validClaims().build())));
    }

    @Test
    @DisplayName("an untrusted issuer, an expired token and a token without expiry are rejected")
    void claimValidation() {
        assertRejected(TestKeys.sign(key, "kid-1", TestKeys.validClaims().issuer("someone-else").build()));
        assertRejected(TestKeys.sign(key, "kid-1", TestKeys.validClaims().expirationTime(Date.from(TestKeys.NOW.minusSeconds(1))).build()));
        assertRejected(TestKeys.sign(key, "kid-1", TestKeys.validClaims().expirationTime(null).build()));
    }

    @Test
    @DisplayName("missing mandatory claims and unknown roles are rejected")
    void mandatoryClaims() {
        assertRejected(TestKeys.sign(key, "kid-1", TestKeys.validClaims().subject(null).build()));
        assertRejected(TestKeys.sign(key, "kid-1", TestKeys.validClaims().claim("tid", null).build()));
        assertRejected(TestKeys.sign(key, "kid-1", TestKeys.validClaims().claim("role", null).build()));
        assertRejected(TestKeys.sign(key, "kid-1", TestKeys.validClaims().claim("role", "GOD").build()));
    }

    @Test
    @DisplayName("a token of a revoked session is rejected until the revocation lapses")
    void revokedSession() {
        String token = signer.issue("u", "t", Role.ADMIN, "sess-9");
        assertEquals("sess-9", verifier.verify(token).sessionId());

        revocations.revoke("sess-9", TestKeys.NOW.plusSeconds(60));

        assertRejected(token);
        assertEquals("u", verifier.verify(signer.issue("u", "t", Role.ADMIN, "other-session")).subject());
    }

    @Test
    @DisplayName("cryptographic failures surface as an issuing error or a rejected token")
    void cryptoFailures() {
        JwtSigner brokenSigner = new JwtSigner(properties, store, clock) {
            @Override
            JWSSigner signerFor(ECKey ignored) throws JOSEException {
                throw new JOSEException("no signer");
            }
        };
        JwtVerifier brokenVerifier = new JwtVerifier(properties, new KeyProvider(properties, store), revocations, clock) {
            @Override
            JWSVerifier verifierFor(ECKey ignored) throws JOSEException {
                throw new JOSEException("no verifier");
            }
        };

        assertThrows(IllegalStateException.class, () -> brokenSigner.issue("u", "t", Role.ADMIN, null));
        assertThrows(AuthenticationFailedException.class, () -> brokenVerifier.verify(signer.issue("u", "t", Role.ADMIN, null)));
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
    @DisplayName("security properties expose their defaults and setters")
    void propertiesBean() {
        SecurityProperties p = new SecurityProperties();
        assertFalse(p.isEnabled());
        assertEquals("thinklab", p.getIssuer());
        assertEquals(600, p.getTtlSeconds());
        assertEquals(7 * 24 * 3600L, p.getRefreshTtlSeconds());
        assertEquals("thinklab-service", p.getServiceName());
        assertEquals(10, p.getJwksMinRefreshSeconds());
        assertTrue(p.getPublicPaths().contains("/health"));

        p.setEnabled(true);
        p.setIssuer("i");
        p.setTtlSeconds(1);
        p.setRefreshTtlSeconds(2);
        p.setServiceName("s");
        p.setPrivateKey("priv");
        p.setPublicKey("pub");
        p.setJwksUrl("jwks");
        p.setJwksMinRefreshSeconds(3);
        p.setRevocationUrl("rev");
        p.setTokenUrl("tok");
        p.setClientId("cid");
        p.setClientSecret("sec");
        p.setPublicPaths(java.util.List.of("/x"));

        assertTrue(p.isEnabled());
        assertEquals("i", p.getIssuer());
        assertEquals(1, p.getTtlSeconds());
        assertEquals(2, p.getRefreshTtlSeconds());
        assertEquals("s", p.getServiceName());
        assertEquals("priv", p.getPrivateKey());
        assertEquals("pub", p.getPublicKey());
        assertEquals("jwks", p.getJwksUrl());
        assertEquals(3, p.getJwksMinRefreshSeconds());
        assertEquals("rev", p.getRevocationUrl());
        assertEquals("tok", p.getTokenUrl());
        assertEquals("cid", p.getClientId());
        assertEquals("sec", p.getClientSecret());
        assertEquals(java.util.List.of("/x"), p.getPublicPaths());
    }
}
