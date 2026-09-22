package com.thinklab.kit.security;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.util.List;

/**
 * Binds {@code thinklab.security.*}. Security is off by default so local development stays simple.
 *
 * <p>Trust model (asymmetric): only the authentication service holds a private key ({@link #privateKey}, a JWK)
 * and signs tokens (ES256). Every other service verifies with public keys only, taken from a static
 * {@link #publicKey} (JWK or JWK Set) or fetched from the issuer's {@link #jwksUrl}.
 */
@ConfigurationProperties("thinklab.security")
public class SecurityProperties {

    private boolean enabled;
    private String issuer = "thinklab";
    private long ttlSeconds = 600;
    private long refreshTtlSeconds = 7 * 24 * 3600L;
    private String serviceName = "thinklab-service";
    private String privateKey;
    private String publicKey;
    private String jwksUrl;
    private long jwksMinRefreshSeconds = 10;
    private String revocationUrl;
    private String tokenUrl;
    private String clientId;
    private String clientSecret;
    private List<String> publicPaths = List.of("/health", "/prometheus", "/metrics", "/swagger", "/swagger-ui", "/openapi");

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    /** Lifetime of an access token. Kept short because revocation of access tokens is polled, not instant. */
    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public long getRefreshTtlSeconds() {
        return refreshTtlSeconds;
    }

    public void setRefreshTtlSeconds(long refreshTtlSeconds) {
        this.refreshTtlSeconds = refreshTtlSeconds;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    /** Issuer only: the EC P-256 private key as a JWK JSON document. When absent the issuer generates an ephemeral key. */
    public String getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey;
    }

    /** Verifier: a static public JWK or JWK Set (JSON). */
    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }

    /** Verifier: URL of the issuer's JWK Set, fetched lazily and refreshed when an unknown key id appears. */
    public String getJwksUrl() {
        return jwksUrl;
    }

    public void setJwksUrl(String jwksUrl) {
        this.jwksUrl = jwksUrl;
    }

    public long getJwksMinRefreshSeconds() {
        return jwksMinRefreshSeconds;
    }

    public void setJwksMinRefreshSeconds(long jwksMinRefreshSeconds) {
        this.jwksMinRefreshSeconds = jwksMinRefreshSeconds;
    }

    /** Verifier: URL of the list of revoked sessions, polled periodically. */
    public String getRevocationUrl() {
        return revocationUrl;
    }

    public void setRevocationUrl(String revocationUrl) {
        this.revocationUrl = revocationUrl;
    }

    /** Client: URL of the issuer's service-token endpoint (client credentials). */
    public String getTokenUrl() {
        return tokenUrl;
    }

    public void setTokenUrl(String tokenUrl) {
        this.tokenUrl = tokenUrl;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public List<String> getPublicPaths() {
        return publicPaths;
    }

    public void setPublicPaths(List<String> publicPaths) {
        this.publicPaths = publicPaths;
    }
}
