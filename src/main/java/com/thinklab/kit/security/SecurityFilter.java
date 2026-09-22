package com.thinklab.kit.security;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpHeaders;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.http.filter.ServerFilterPhase;
import jakarta.inject.Inject;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Enforces authentication and RBAC in front of every service when {@code thinklab.security.enabled=true}.
 *
 * <ul>
 *   <li>a valid {@code Authorization: Bearer <jwt>} is mandatory outside the public paths (401 otherwise);</li>
 *   <li>the role must allow the HTTP method (403 otherwise);</li>
 *   <li>{@code X-Tenant-Id} and {@code X-Executor} are <b>derived from the token</b>, never trusted from the client:
 *       a conflicting tenant header is rejected (403) and the executor header is overwritten;</li>
 *   <li>{@code X-Role} carries the verified role so services can apply finer rules (a client-supplied value is overwritten);</li>
 *   <li>a {@link Role#SERVICE} token acts on behalf of any tenant, so it keeps the tenant header it sends.</li>
 * </ul>
 */
@Filter(Filter.MATCH_ALL_PATTERN)
@Requires(property = "thinklab.security.enabled", value = "true")
public class SecurityFilter implements HttpServerFilter {

    static final String TENANT_HEADER = "X-Tenant-Id";
    static final String EXECUTOR_HEADER = "X-Executor";
    static final String ROLE_HEADER = "X-Role";
    private static final Logger log = LoggerFactory.getLogger(SecurityFilter.class);
    private static final String BEARER = "bearer ";

    private final JwtService jwtService;
    private final SecurityProperties properties;

    @Inject
    public SecurityFilter(JwtService jwtService, SecurityProperties properties) {
        this.jwtService = jwtService;
        this.properties = properties;
    }

    @Override
    public int getOrder() {
        return ServerFilterPhase.SECURITY.order();
    }

    @Override
    @NonNull
    public Publisher<MutableHttpResponse<?>> doFilter(@NonNull HttpRequest<?> request, @NonNull ServerFilterChain chain) {
        if (isPublic(request.getPath())) {
            return chain.proceed(request);
        }

        AuthenticatedPrincipal principal;
        try {
            principal = jwtService.verify(bearerToken(request.getHeaders().get(HttpHeaders.AUTHORIZATION)));
        } catch (AuthenticationFailedException e) {
            log.warn("[ACTION: AUTHENTICATE] [PATH: {}] - Rejected: {}", request.getPath(), e.getMessage());
            return Mono.just(problem(HttpStatus.UNAUTHORIZED, "ERR-AUTH-00401", "Authentication is required: a valid bearer token must be supplied."));
        }

        if (!principal.role().allows(request.getMethod())) {
            log.warn("[ACTION: AUTHORIZE] [PATH: {}] [ROLE: {}] - Method {} denied.", request.getPath(), principal.role(), request.getMethod());
            return Mono.just(problem(HttpStatus.FORBIDDEN, "ERR-AUTH-00403", "The role " + principal.role() + " is not allowed to perform " + request.getMethod() + "."));
        }

        String requestedTenant = request.getHeaders().get(TENANT_HEADER);
        if (principal.role() != Role.SERVICE && requestedTenant != null && !requestedTenant.equals(principal.tenantId())) {
            log.warn("[ACTION: AUTHORIZE] [PATH: {}] - Tenant header does not match the token tenant.", request.getPath());
            return Mono.just(problem(HttpStatus.FORBIDDEN, "ERR-AUTH-00403", "The tenant header does not match the authenticated tenant."));
        }

        // Netty server headers are mutable in place. request.mutate() would drop the request body, so it is only
        // the fallback for a request whose headers are read-only.
        HttpRequest<?> target = request;
        MutableHttpHeaders headers;
        if (request.getHeaders() instanceof MutableHttpHeaders mutableHeaders) {
            headers = mutableHeaders;
        } else {
            MutableHttpRequest<?> derived = request.mutate();
            headers = derived.getHeaders();
            target = derived;
        }
        headers.set(ROLE_HEADER, principal.role().name());
        if (principal.role() != Role.SERVICE) {
            headers.set(TENANT_HEADER, principal.tenantId());
            headers.set(EXECUTOR_HEADER, principal.subject());
        } else if (request.getHeaders().get(EXECUTOR_HEADER) == null) {
            headers.set(EXECUTOR_HEADER, principal.subject());
        }
        return chain.proceed(target);
    }

    private boolean isPublic(String path) {
        return properties.getPublicPaths().stream()
                .anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix.endsWith("/") ? prefix : prefix + "/"));
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.toLowerCase(Locale.ROOT).startsWith(BEARER)) {
            return null;
        }
        return authorization.substring(BEARER.length()).trim();
    }

    private static MutableHttpResponse<?> problem(HttpStatus status, String code, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://api.thinklab.com/errors/" + code.toLowerCase(Locale.ROOT));
        body.put("title", status.getReason());
        body.put("status", status.getCode());
        body.put("error_code", code);
        body.put("detail", detail);
        body.put("timestamp", Instant.now().toString());
        MutableHttpResponse<Map<String, Object>> response = HttpResponse.<Map<String, Object>>status(status).body(body);
        if (status == HttpStatus.UNAUTHORIZED) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return response;
    }
}
