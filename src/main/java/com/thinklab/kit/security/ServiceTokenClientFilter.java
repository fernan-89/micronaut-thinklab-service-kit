package com.thinklab.kit.security;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.annotation.ClientFilter;
import io.micronaut.http.annotation.RequestFilter;
import jakarta.inject.Inject;

/**
 * Authenticates this service to the other ThinkLab services it calls (currently the Hash Token Registry) with a
 * {@link Role#SERVICE} token obtained through {@link ServiceTokenProvider}.
 */
@ClientFilter(serviceId = "hash-service")
@Requires(property = "thinklab.security.enabled", value = "true")
public class ServiceTokenClientFilter {

    private final ServiceTokenProvider tokenProvider;

    @Inject
    public ServiceTokenClientFilter(ServiceTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @RequestFilter
    public void addServiceToken(MutableHttpRequest<?> request) {
        request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.token());
    }
}
