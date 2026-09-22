package com.thinklab.kit.security;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Resolves the public key for a token's {@code kid}. Sources, in order: the issuer's own key (when this service is
 * the issuer), a static {@code thinklab.security.public-key}, and the issuer's JWKS endpoint. The remote set is
 * cached and only re-fetched when an unknown {@code kid} appears, at most once per
 * {@code thinklab.security.jwks-min-refresh-seconds}, so key rotation is picked up without a restart and a stream of
 * forged {@code kid} values cannot hammer the issuer.
 */
@Singleton
public class KeyProvider {

    private static final Logger log = LoggerFactory.getLogger(KeyProvider.class);

    private final SecurityProperties properties;
    private final LocalKeyStore localKeyStore;
    private final HttpTransport transport;
    private final Clock clock;
    private volatile JWKSet staticSet;
    private volatile JWKSet remoteSet;
    private volatile Instant lastRemoteAttempt = Instant.EPOCH;

    @Inject
    public KeyProvider(SecurityProperties properties, LocalKeyStore localKeyStore) {
        this(properties, localKeyStore, new HttpTransport.Default(), Clock.systemUTC());
    }

    KeyProvider(SecurityProperties properties, LocalKeyStore localKeyStore, HttpTransport transport, Clock clock) {
        this.properties = properties;
        this.localKeyStore = localKeyStore;
        this.transport = transport;
        this.clock = clock;
    }

    /** The key with this id, refreshing the remote set once if it is unknown. */
    public Optional<JWK> find(String keyId) {
        Optional<JWK> found = lookup(keyId);
        if (found.isPresent() || !remoteConfigured()) {
            return found;
        }
        refreshRemote();
        return lookup(keyId);
    }

    private Optional<JWK> lookup(String keyId) {
        Optional<JWKSet> local = localKeyStore.publicSet();
        if (local.isPresent() && local.get().getKeyByKeyId(keyId) != null) {
            return Optional.of(local.get().getKeyByKeyId(keyId));
        }
        JWKSet configured = configuredSet();
        if (configured != null && configured.getKeyByKeyId(keyId) != null) {
            return Optional.of(configured.getKeyByKeyId(keyId));
        }
        JWKSet remote = remoteSet;
        return remote == null ? Optional.empty() : Optional.ofNullable(remote.getKeyByKeyId(keyId));
    }

    private boolean remoteConfigured() {
        String url = properties.getJwksUrl();
        return url != null && !url.isBlank();
    }

    private synchronized void refreshRemote() {
        Instant now = clock.instant();
        if (now.isBefore(lastRemoteAttempt.plusSeconds(properties.getJwksMinRefreshSeconds()))) {
            return;
        }
        lastRemoteAttempt = now;
        try {
            remoteSet = JWKSet.parse(transport.get(properties.getJwksUrl()));
        } catch (IOException | ParseException e) {
            log.warn("[SECURITY] Unable to refresh the JWK Set from {}: {}", properties.getJwksUrl(), e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private JWKSet configuredSet() {
        if (staticSet == null) {
            String configured = properties.getPublicKey();
            if (configured == null || configured.isBlank()) {
                return null;
            }
            try {
                staticSet = configured.contains("\"keys\"") ? JWKSet.parse(configured) : new JWKSet(JWK.parse(configured));
            } catch (ParseException e) {
                throw new IllegalStateException("thinklab.security.public-key is not a valid JWK or JWK Set.", e);
            }
        }
        return staticSet;
    }
}
