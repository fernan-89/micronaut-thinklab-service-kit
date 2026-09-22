package com.thinklab.kit.security;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.annotation.ClientFilter;
import io.micronaut.http.annotation.RequestFilter;
import jakarta.inject.Inject;

/**
 * Authenticates this service to the other ThinkLab services it calls (currently the Hash Token Registry). When
 * security is enabled every outbound call carries a short-lived {@link Role#SERVICE} token whose subject is the
 * caller's service name ({@code thinklab.security.service-name}, defaulting to the application name).
 */
@ClientFilter(serviceId = "hash-service")
@Requires(property = "thinklab.security.enabled", value = "true")
public class ServiceTokenClientFilter {

    static final String PLATFORM_TENANT = "platform";

    private final JwtService jwtService;
    private final SecurityProperties properties;

    @Inject
    public ServiceTokenClientFilter(JwtService jwtService, SecurityProperties properties) {
        this.jwtService = jwtService;
        this.properties = properties;
    }

    @RequestFilter
    public void addServiceToken(MutableHttpRequest<?> request) {
        request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(properties.getServiceName(), PLATFORM_TENANT, Role.SERVICE));
    }
}
