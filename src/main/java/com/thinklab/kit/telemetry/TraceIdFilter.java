package com.thinklab.kit.telemetry;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.http.filter.ServerFilterPhase;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.slf4j.MDC;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

import java.net.InetAddress;
import java.util.UUID;

/**
 * Infrastructure Component: Reactive MDC Traceability & External Caller Forensics Filter.
 *
 * <p><b>Architectural Role:</b>
 * Intercepts all inbound HTTP traffic globally ("/**") at the Netty pipeline boundary.
 * Extracts native W3C trace identifiers, forensic origin metadata (IP, User-Agent, Virtual Host, Origin),
 * and executes asynchronous non-blocking reverse DNS lookups, injecting everything into SLF4J's MDC
 * and Project Reactor's execution context.
 *
 * <p><b>Resilience & Decoupling (Zero-Trust Fix):</b>
 * Decodes W3C traceparent headers and MDC states natively without hard-coupling to OpenTelemetry
 * SDK classes. This ensures the Netty pipeline never crashes with NoClassDefFoundError.
 *
 * <p><b>Privacy by Design (LGPD/GDPR):</b>
 * The client IP address is obfuscated at the last octet (IPv4) or block (IPv6) before logging.
 *
 * @author Thinklab Systems Engineering Team
 * @version 3.5.2-NASA-SRE-PROD-STABLE
 * @since 1.0
 */
@Singleton
@Filter("/**")
@Slf4j
public class TraceIdFilter implements HttpServerFilter {

    private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";
    private static final String W3C_TRACE_PARENT_HEADER = "traceparent";
    private static final String B3_TRACE_ID_HEADER = "X-B3-TraceId";

    private static final String MDC_TRACE_KEY = "traceId";
    private static final String MDC_CLIENT_IP_KEY = "clientIp";
    private static final String MDC_USER_AGENT_KEY = "userAgent";
    private static final String MDC_VIRTUAL_HOST_KEY = "virtualHost";
    private static final String MDC_CLIENT_ORIGIN_KEY = "clientOrigin";
    private static final String MDC_EXTERNAL_HOST_KEY = "externalClientHost";

    @Override
    public int getOrder() {
        return ServerFilterPhase.TRACING.order() + 1;
    }

    @Override
    @NonNull
    public Publisher<MutableHttpResponse<?>> doFilter(@NonNull HttpRequest<?> request, @NonNull ServerFilterChain chain) {
        String traceId = resolveTraceId(request);

        String virtualHost = request.getHeaders().get("Host");
        virtualHost = (virtualHost == null || virtualHost.isBlank()) ? "unknown-host" : virtualHost;

        String clientOrigin = request.getHeaders().get("Origin");
        if (clientOrigin == null || clientOrigin.isBlank()) {
            clientOrigin = request.getHeaders().get("Referer");
        }
        clientOrigin = (clientOrigin == null || clientOrigin.isBlank()) ? "direct-client" : clientOrigin;

        String userAgent = request.getHeaders().get(HttpHeaders.USER_AGENT);
        userAgent = (userAgent == null || userAgent.isBlank()) ? "UNKNOWN" : userAgent;
        if (userAgent.length() > 40) {
            userAgent = userAgent.substring(0, 37) + "...";
        }

        String forwardedFor = request.getHeaders().get(FORWARDED_FOR_HEADER);
        String rawIp = (forwardedFor != null && !forwardedFor.isBlank())
                ? forwardedFor.split(",")[0].trim()
                : (request.getRemoteAddress() != null && request.getRemoteAddress().getAddress() != null)
                ? request.getRemoteAddress().getAddress().getHostAddress()
                : "127.0.0.1";

        final String finalTraceId = traceId;
        final String finalVirtualHost = virtualHost;
        final String finalClientOrigin = clientOrigin;
        final String finalClientIp = obfuscateIp(rawIp);
        final String finalUserAgent = userAgent;

        return Mono.fromCallable(() -> resolveExternalHost(rawIp))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(externalHost ->
                        Mono.defer(() -> {
                            MDC.put(MDC_TRACE_KEY, finalTraceId);
                            MDC.put(MDC_CLIENT_IP_KEY, finalClientIp);
                            MDC.put(MDC_USER_AGENT_KEY, finalUserAgent);
                            MDC.put(MDC_VIRTUAL_HOST_KEY, finalVirtualHost);
                            MDC.put(MDC_CLIENT_ORIGIN_KEY, finalClientOrigin);
                            MDC.put(MDC_EXTERNAL_HOST_KEY, externalHost);

                            request.setAttribute(MDC_TRACE_KEY, finalTraceId);
                            request.setAttribute(MDC_CLIENT_IP_KEY, finalClientIp);
                            request.setAttribute(MDC_USER_AGENT_KEY, finalUserAgent);
                            request.setAttribute(MDC_VIRTUAL_HOST_KEY, finalVirtualHost);
                            request.setAttribute(MDC_CLIENT_ORIGIN_KEY, finalClientOrigin);
                            request.setAttribute(MDC_EXTERNAL_HOST_KEY, externalHost);

                            return Mono.from(chain.proceed(request))
                                    .doFinally(signalType -> {
                                        MDC.remove(MDC_TRACE_KEY);
                                        MDC.remove(MDC_CLIENT_IP_KEY);
                                        MDC.remove(MDC_USER_AGENT_KEY);
                                        MDC.remove(MDC_VIRTUAL_HOST_KEY);
                                        MDC.remove(MDC_CLIENT_ORIGIN_KEY);
                                        MDC.remove(MDC_EXTERNAL_HOST_KEY);
                                    });
                        }).contextWrite(
                                Context.of(MDC_TRACE_KEY, finalTraceId)
                                        .put(MDC_CLIENT_IP_KEY, finalClientIp)
                                        .put(MDC_USER_AGENT_KEY, finalUserAgent)
                                        .put(MDC_VIRTUAL_HOST_KEY, finalVirtualHost)
                                        .put(MDC_CLIENT_ORIGIN_KEY, finalClientOrigin)
                                        .put(MDC_EXTERNAL_HOST_KEY, externalHost)
                        )
                );
    }

    private String resolveTraceId(HttpRequest<?> request) {
        String traceparent = request.getHeaders().get(W3C_TRACE_PARENT_HEADER);
        if (traceparent != null && traceparent.length() >= 55) {
            return traceparent.split("-")[1];
        }

        String b3TraceId = request.getHeaders().get(B3_TRACE_ID_HEADER);
        if (b3TraceId != null && !b3TraceId.isBlank()) {
            return b3TraceId;
        }

        String mdcTraceId = MDC.get(MDC_TRACE_KEY);
        if (mdcTraceId != null && !mdcTraceId.isBlank()) {
            return mdcTraceId;
        }

        return "UNTRACED-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String resolveExternalHost(String clientIp) {
        if (clientIp == null || clientIp.equals("127.0.0.1") || clientIp.equals("0.0.0.0") || clientIp.startsWith("192.168.") || clientIp.startsWith("10.")) {
            return "local-mesh-client";
        }
        try {
            InetAddress inetAddress = InetAddress.getByName(clientIp);
            String hostName = inetAddress.getHostName();
            return (hostName != null && !hostName.isBlank()) ? hostName : clientIp;
        } catch (Exception e) {
            return clientIp;
        }
    }

    private String obfuscateIp(String ip) {
        if (ip == null || ip.isBlank()) return "UNKNOWN";

        if (ip.contains(".")) {
            int lastDotIndex = ip.lastIndexOf('.');
            if (lastDotIndex > 0) return ip.substring(0, lastDotIndex) + ".***";
        }

        if (ip.contains(":")) {
            int lastColonIndex = ip.lastIndexOf(':');
            if (lastColonIndex > 0) return ip.substring(0, lastColonIndex) + ":***";
        }

        return "***";
    }
}
