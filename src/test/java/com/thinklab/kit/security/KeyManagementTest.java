package com.thinklab.kit.security;

import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyManagementTest {

    /** Scriptable transport recording how many times it was used. */
    private static class FakeTransport implements HttpTransport {
        final AtomicInteger calls = new AtomicInteger();
        volatile String body = "{}";
        volatile Exception failure;

        @Override
        public String get(String url) throws IOException, InterruptedException {
            calls.incrementAndGet();
            throwIfFailing();
            return body;
        }

        @Override
        public String postForm(String url, Map<String, String> form) throws IOException, InterruptedException {
            calls.incrementAndGet();
            throwIfFailing();
            return body;
        }

        private void throwIfFailing() throws IOException, InterruptedException {
            if (failure instanceof IOException io) {
                throw io;
            }
            if (failure instanceof InterruptedException ie) {
                throw ie;
            }
        }
    }

    // ------------------------------------------------------------------------------ LocalKeyStore

    @Test
    @DisplayName("the key store generates an ephemeral P-256 key on first use and exposes only its public half")
    void generatedKey() {
        LocalKeyStore store = new LocalKeyStore(new SecurityProperties());

        assertTrue(store.publicSet().isEmpty());
        ECKey key = store.signingKey();

        assertNotNull(key.getKeyID());
        assertTrue(key.isPrivate());
        assertEquals(key, store.signingKey());
        assertFalse(store.publicSet().orElseThrow().getKeys().get(0).isPrivate());
    }

    @Test
    @DisplayName("a blank configured key means generate, and a generator failure is reported")
    void blankAndBrokenGeneration() {
        SecurityProperties blank = new SecurityProperties();
        blank.setPrivateKey("  ");

        assertNotNull(new LocalKeyStore(blank).signingKey());
        assertThrows(IllegalStateException.class, () -> new LocalKeyStore(new SecurityProperties()) {
            @Override
            ECKey generateKey() throws com.nimbusds.jose.JOSEException {
                throw new com.nimbusds.jose.JOSEException("no entropy");
            }
        }.signingKey());
    }

    @Test
    @DisplayName("the key store loads the configured private JWK")
    void configuredKey() {
        ECKey configured = TestKeys.newKey("kid-configured");

        assertEquals("kid-configured", new LocalKeyStore(TestKeys.propertiesWith(configured)).signingKey().getKeyID());
    }

    @Test
    @DisplayName("the key store rejects malformed, public-only and non-P-256 keys")
    void invalidKeys() throws Exception {
        SecurityProperties malformed = new SecurityProperties();
        malformed.setPrivateKey("not json");
        SecurityProperties publicOnly = new SecurityProperties();
        publicOnly.setPrivateKey(TestKeys.newKey("k").toPublicJWK().toJSONString());
        SecurityProperties rsa = new SecurityProperties();
        RSAKey rsaKey = new RSAKeyGenerator(2048).keyID("rsa").generate();
        rsa.setPrivateKey(rsaKey.toJSONString());
        SecurityProperties otherCurve = new SecurityProperties();
        otherCurve.setPrivateKey(new com.nimbusds.jose.jwk.gen.ECKeyGenerator(com.nimbusds.jose.jwk.Curve.P_384).generate().toJSONString());

        assertThrows(IllegalStateException.class, () -> new LocalKeyStore(malformed).signingKey());
        assertThrows(IllegalStateException.class, () -> new LocalKeyStore(publicOnly).signingKey());
        assertThrows(IllegalStateException.class, () -> new LocalKeyStore(rsa).signingKey());
        assertThrows(IllegalStateException.class, () -> new LocalKeyStore(otherCurve).signingKey());
    }

    // ------------------------------------------------------------------------------ KeyProvider

    private KeyProvider provider(SecurityProperties properties, LocalKeyStore store, FakeTransport transport, Instant now) {
        return new KeyProvider(properties, store, transport, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("keys resolve from the local issuer key and from a static public JWK or JWK Set")
    void localAndStaticKeys() {
        ECKey local = TestKeys.newKey("local");
        LocalKeyStore store = new LocalKeyStore(TestKeys.propertiesWith(local));
        store.signingKey();
        ECKey single = TestKeys.newKey("single");
        ECKey inSet = TestKeys.newKey("in-set");

        SecurityProperties one = new SecurityProperties();
        one.setPublicKey(single.toPublicJWK().toJSONString());
        SecurityProperties many = new SecurityProperties();
        many.setPublicKey(new JWKSet(inSet.toPublicJWK()).toString());

        assertTrue(provider(one, store, new FakeTransport(), TestKeys.NOW).find("local").isPresent());
        KeyProvider reused = provider(one, new LocalKeyStore(new SecurityProperties()), new FakeTransport(), TestKeys.NOW);
        assertTrue(reused.find("single").isPresent());
        assertTrue(reused.find("single").isPresent());
        assertTrue(provider(one, new LocalKeyStore(new SecurityProperties()), new FakeTransport(), TestKeys.NOW).find("single").isPresent());
        assertTrue(provider(many, new LocalKeyStore(new SecurityProperties()), new FakeTransport(), TestKeys.NOW).find("in-set").isPresent());
        assertTrue(provider(one, new LocalKeyStore(new SecurityProperties()), new FakeTransport(), TestKeys.NOW).find("other").isEmpty());
    }

    @Test
    @DisplayName("a malformed static public key fails loudly")
    void malformedStaticKey() {
        SecurityProperties bad = new SecurityProperties();
        bad.setPublicKey("nonsense");

        assertThrows(IllegalStateException.class,
                () -> provider(bad, new LocalKeyStore(new SecurityProperties()), new FakeTransport(), TestKeys.NOW).find("x"));
    }

    @Test
    @DisplayName("with nothing configured, no key is ever found and nothing is fetched")
    void nothingConfigured() {
        FakeTransport transport = new FakeTransport();
        SecurityProperties blank = new SecurityProperties();
        blank.setPublicKey(" ");
        blank.setJwksUrl(" ");

        assertTrue(provider(blank, new LocalKeyStore(blank), transport, TestKeys.NOW).find("x").isEmpty());
        assertEquals(0, transport.calls.get());
    }

    @Test
    @DisplayName("an unknown kid triggers one JWKS fetch, cached afterwards and rate limited for further unknown kids")
    void remoteRefresh() {
        ECKey remote = TestKeys.newKey("remote");
        FakeTransport transport = new FakeTransport();
        transport.body = new JWKSet(remote.toPublicJWK()).toString();
        SecurityProperties properties = new SecurityProperties();
        properties.setJwksUrl("http://issuer/jwks");
        KeyProvider provider = provider(properties, new LocalKeyStore(properties), transport, TestKeys.NOW);

        assertTrue(provider.find("remote").isPresent());
        assertTrue(provider.find("remote").isPresent());
        assertEquals(1, transport.calls.get());
        assertTrue(provider.find("forged-1").isEmpty());
        assertTrue(provider.find("forged-2").isEmpty());
        assertEquals(1, transport.calls.get());
    }

    @Test
    @DisplayName("after the minimum interval an unknown kid fetches again, picking up a rotated key")
    void rotation() {
        FakeTransport transport = new FakeTransport();
        transport.body = new JWKSet(TestKeys.newKey("old").toPublicJWK()).toString();
        SecurityProperties properties = new SecurityProperties();
        properties.setJwksUrl("http://issuer/jwks");
        properties.setJwksMinRefreshSeconds(0);
        KeyProvider provider = provider(properties, new LocalKeyStore(properties), transport, TestKeys.NOW);
        assertTrue(provider.find("new").isEmpty());

        transport.body = new JWKSet(TestKeys.newKey("new").toPublicJWK()).toString();

        assertTrue(provider.find("new").isPresent());
        assertEquals(2, transport.calls.get());
    }

    @Test
    @DisplayName("a failing or unparsable JWKS fetch is tolerated and keeps the previous set")
    void remoteFailures() {
        FakeTransport transport = new FakeTransport();
        SecurityProperties properties = new SecurityProperties();
        properties.setJwksUrl("http://issuer/jwks");
        properties.setJwksMinRefreshSeconds(0);
        KeyProvider provider = provider(properties, new LocalKeyStore(properties), transport, TestKeys.NOW);

        transport.failure = new IOException("down");
        assertTrue(provider.find("a").isEmpty());
        transport.failure = null;
        transport.body = "not a jwk set";
        assertTrue(provider.find("b").isEmpty());
        transport.failure = new InterruptedException("stop");
        assertTrue(provider.find("c").isEmpty());
        assertTrue(Thread.interrupted());
    }

    @Test
    @DisplayName("the default constructor uses the JDK transport and the system clock")
    void defaultConstructor() {
        SecurityProperties properties = new SecurityProperties();

        assertTrue(new KeyProvider(properties, new LocalKeyStore(properties)).find("x").isEmpty());
    }

    // ------------------------------------------------------------------------------ RevocationList

    @Test
    @DisplayName("revoked sessions are reported until their expiry and then forgotten")
    void revocationList() {
        Instant[] now = {TestKeys.NOW};
        RevocationList list = new RevocationList(new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now[0];
            }
        });

        assertFalse(list.isRevoked("s1"));
        list.revoke("s1", TestKeys.NOW.plusSeconds(60));
        assertTrue(list.isRevoked("s1"));
        now[0] = TestKeys.NOW.plusSeconds(61);
        assertFalse(list.isRevoked("s1"));
        assertFalse(list.isRevoked("s1"));
    }

    @Test
    @DisplayName("replaceAll makes the list mirror the issuer's view")
    void replaceAll() {
        RevocationList list = new RevocationList();
        list.revoke("stale", Instant.now().plusSeconds(600));

        list.replaceAll(Map.of("fresh", Instant.now().plusSeconds(600)));

        assertFalse(list.isRevoked("stale"));
        assertTrue(list.isRevoked("fresh"));
    }

    // ------------------------------------------------------------------------------ RevocationPoller

    @Test
    @DisplayName("the poller loads the issuer's revoked sessions, tolerates an empty answer and keeps the list on failure")
    void poller() {
        FakeTransport transport = new FakeTransport();
        RevocationList list = new RevocationList();
        SecurityProperties properties = new SecurityProperties();
        properties.setRevocationUrl("http://issuer/revoked");
        RevocationPoller poller = new RevocationPoller(properties, list, transport);
        long until = Instant.now().plusSeconds(600).getEpochSecond();

        transport.body = "{\"revoked\":[{\"sid\":\"s1\",\"until\":" + until + "}]}";
        poller.poll();
        assertTrue(list.isRevoked("s1"));

        transport.failure = new IOException("down");
        poller.poll();
        assertTrue(list.isRevoked("s1"));

        transport.failure = null;
        transport.body = "garbage";
        poller.poll();
        assertTrue(list.isRevoked("s1"));

        transport.body = "{\"revoked\":[{\"sid\":\"s1\"}]}";
        poller.poll();
        assertTrue(list.isRevoked("s1"));

        transport.failure = new InterruptedException("stop");
        poller.poll();
        assertTrue(Thread.interrupted());

        transport.failure = null;
        transport.body = "{}";
        poller.poll();
        assertFalse(list.isRevoked("s1"));
    }

    @Test
    @DisplayName("the poller's public constructor wires the JDK transport")
    void pollerDefaultConstructor() {
        SecurityProperties properties = new SecurityProperties();
        properties.setRevocationUrl("http://127.0.0.1:1/revoked");

        new RevocationPoller(properties, new RevocationList()).poll();
    }

    // ------------------------------------------------------------------------------ ClientCredentialsTokenProvider

    @Test
    @DisplayName("the client-credentials provider caches the token and renews it before expiry")
    void clientCredentials() {
        FakeTransport transport = new FakeTransport();
        transport.body = "{\"accessToken\":\"tok-1\",\"expiresIn\":600}";
        SecurityProperties properties = new SecurityProperties();
        properties.setTokenUrl("http://issuer/token");
        properties.setClientId("svc");
        properties.setClientSecret("secret");
        Instant[] now = {TestKeys.NOW};
        Clock clock = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now[0];
            }
        };
        ClientCredentialsTokenProvider provider = new ClientCredentialsTokenProvider(properties, transport, clock);

        assertEquals("tok-1", provider.token());
        assertEquals("tok-1", provider.token());
        assertEquals(1, transport.calls.get());

        transport.body = "{\"accessToken\":\"tok-2\",\"expiresIn\":600}";
        now[0] = TestKeys.NOW.plusSeconds(571);
        assertEquals("tok-2", provider.token());
        assertEquals(2, transport.calls.get());
    }

    @Test
    @DisplayName("the client-credentials provider reports issuer failures")
    void clientCredentialsFailures() {
        FakeTransport transport = new FakeTransport();
        SecurityProperties properties = new SecurityProperties();
        properties.setTokenUrl("http://issuer/token");
        ClientCredentialsTokenProvider provider = new ClientCredentialsTokenProvider(properties, transport, Clock.systemUTC());

        transport.failure = new IOException("down");
        assertThrows(IllegalStateException.class, provider::token);
        transport.failure = null;
        transport.body = "garbage";
        assertThrows(IllegalStateException.class, provider::token);
        transport.body = "{}";
        assertThrows(IllegalStateException.class, provider::token);
        transport.failure = new InterruptedException("stop");
        assertThrows(IllegalStateException.class, provider::token);
        assertTrue(Thread.interrupted());
        assertNotNull(new ClientCredentialsTokenProvider(properties));
    }
}
