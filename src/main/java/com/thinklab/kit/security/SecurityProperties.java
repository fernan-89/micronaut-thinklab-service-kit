package com.thinklab.kit.security;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.util.List;

/** Binds {@code thinklab.security.*}. Security is off by default so local development and the E2E stack stay simple. */
@ConfigurationProperties("thinklab.security")
public class SecurityProperties {

    private boolean enabled;
    private String secret;
    private String issuer = "thinklab";
    private long ttlSeconds = 3600;
    private String serviceName = "thinklab-service";
    private List<String> publicPaths = List.of("/health", "/prometheus", "/metrics", "/swagger", "/swagger-ui", "/openapi");

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public List<String> getPublicPaths() {
        return publicPaths;
    }

    public void setPublicPaths(List<String> publicPaths) {
        this.publicPaths = publicPaths;
    }
}
