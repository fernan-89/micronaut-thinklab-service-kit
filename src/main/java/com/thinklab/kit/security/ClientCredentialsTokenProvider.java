package com.thinklab.kit.security;

import com.nimbusds.jose.util.JSONObjectUtils;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * OAuth2-style client-credentials flow against the issuer's service-token endpoint. The token is cached and renewed
 * shortly before it expires. The authentication service replaces this bean with a local implementation, because it
 * can sign its own service tokens without a network hop.
 */
@Singleton
@Requires(property = "thinklab.security.enabled", value = "true")
public class ClientCredentialsTokenProvider implements ServiceTokenProvider {

    private static final long RENEW_MARGIN_SECONDS = 30;

    private final SecurityProperties properties;
    private final HttpTransport transport;
    private final Clock clock;
    private String cached;
    private Instant cachedUntil = Instant.EPOCH;

    @Inject
    public ClientCredentialsTokenProvider(SecurityProperties properties) {
        this(properties, new HttpTransport.Default(), Clock.systemUTC());
    }

    ClientCredentialsTokenProvider(SecurityProperties properties, HttpTransport transport, Clock clock) {
        this.properties = properties;
        this.transport = transport;
        this.clock = clock;
    }

    @Override
    public synchronized String token() {
        if (cached != null && clock.instant().isBefore(cachedUntil)) {
            return cached;
        }
        try {
            Map<String, Object> body = JSONObjectUtils.parse(transport.postForm(properties.getTokenUrl(), Map.of(
                    "grant_type", "client_credentials",
                    "client_id", String.valueOf(properties.getClientId()),
                    "client_secret", String.valueOf(properties.getClientSecret()))));
            cached = (String) body.get("accessToken");
            cachedUntil = clock.instant().plusSeconds(((Number) body.get("expiresIn")).longValue() - RENEW_MARGIN_SECONDS);
            return cached;
        } catch (IOException | ParseException | RuntimeException e) {
            throw new IllegalStateException("Unable to obtain a service token from " + properties.getTokenUrl(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while obtaining a service token.", e);
        }
    }
}
