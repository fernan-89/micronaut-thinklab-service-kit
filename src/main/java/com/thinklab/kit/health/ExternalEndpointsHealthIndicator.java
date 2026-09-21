package com.thinklab.kit.health;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.HealthIndicator;
import io.micronaut.management.health.indicator.HealthResult;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.File;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Infrastructure Component: Integrates external dependency validation into application startup and telemetry.
 *
 * <p><b>Architectural Role:</b>
 * Dual-phase resilient monitor:
 * <ol>
 * <li><b>Startup (Phase 1):</b> Eagerly warms up DNS resolution, TCP sockets, and SSL handshakes.</li>
 * <li><b>Runtime (Phase 2):</b> Continually evaluates external health for Kubernetes Readiness Probes.</li>
 * </ol>
 *
 * @author Thinklab Systems Engineering Team
 * @version 1.7.1-NASA-SRE
 * @since 1.0
 */
@Singleton
@Requires(notEnv = "test")
@Slf4j
public class ExternalEndpointsHealthIndicator implements HealthIndicator, ApplicationEventListener<StartupEvent> {

    private final Map<String, String> targetEndpoints;
    private final String infrastructureHostname;
    private final String infrastructureIpAddress;
    private final String executionEnvironment;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @Inject
    public ExternalEndpointsHealthIndicator(
            @Nullable @Property(name = "warmup.endpoints") Map<String, String> targetEndpoints
    ) {
        this.targetEndpoints = targetEndpoints != null ? targetEndpoints : Map.of();
        this.infrastructureHostname = resolveHostname();
        this.infrastructureIpAddress = resolveIpAddress();
        this.executionEnvironment = resolveExecutionEnvironment();
    }

    private void injectBootContext() {
        MDC.put("traceId", "SYSTEM-BOOT");
        MDC.put("clientIp", this.infrastructureIpAddress);
        MDC.put("userAgent", "Micronaut-Engine/Startup");
        MDC.put("ip", this.infrastructureIpAddress);
        MDC.put("client", "Micronaut-Engine/Startup");
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        Objects.requireNonNull(event, "Application constraint violated: StartupEvent cannot be null.");

        injectBootContext();

        if (targetEndpoints == null || targetEndpoints.isEmpty()) {
            log.info("[EXTERNAL_HEALTH_WARMUP] - No external targets configured. Skipping warmup phase.");
            return;
        }

        log.info("[EXTERNAL_HEALTH_WARMUP] - Environment: [{}]", executionEnvironment);
        log.info("[EXTERNAL_HEALTH_WARMUP] - Hostname:    [{}]", infrastructureHostname);
        log.info("[EXTERNAL_HEALTH_WARMUP] - IP Address:  [{}]", infrastructureIpAddress);
        log.info("[EXTERNAL_HEALTH_WARMUP] - Initiating proactive warmup for {} external dependencies...", targetEndpoints.size());

        try {
            Flux.fromIterable(targetEndpoints.entrySet())
                    .flatMap(this::checkEndpoint)
                    .doOnNext(entry -> {
                        injectBootContext();
                        log.info("[EXTERNAL_HEALTH_WARMUP] - Warmup state: [{}] -> {}", entry.getKey(), entry.getValue());
                    })
                    .blockLast(Duration.ofSeconds(10));
        } catch (Exception e) {
            log.warn("[EXTERNAL_HEALTH_WARMUP] Warmup barrier interrupted or timed out. Reason: {}", e.getMessage());
        } finally {
            injectBootContext();
        }
    }

    @Override
    public Publisher<HealthResult> getResult() {
        if (targetEndpoints == null || targetEndpoints.isEmpty()) {
            return Mono.just(HealthResult.builder("external-endpoints")
                    .status(HealthStatus.UP)
                    .details(Map.of(
                            "message", "No external endpoints configured for telemetry.",
                            "resolvedEnvironment", executionEnvironment,
                            "resolvedHostname", infrastructureHostname,
                            "resolvedIpAddress", infrastructureIpAddress
                    ))
                    .build());
        }

        return Flux.fromIterable(targetEndpoints.entrySet())
                .flatMap(this::checkEndpoint)
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .map(details -> {
                    boolean hasFailures = details.values().stream()
                            .anyMatch(status -> status.toString().startsWith("DOWN"));

                    HealthStatus aggregatedStatus = hasFailures ? HealthStatus.DOWN : HealthStatus.UP;

                    Map<String, Object> responseTopology = new HashMap<>(details);
                    responseTopology.put("resolvedEnvironment", executionEnvironment);
                    responseTopology.put("resolvedHostname", infrastructureHostname);
                    responseTopology.put("resolvedIpAddress", infrastructureIpAddress);

                    return HealthResult.builder("external-endpoints")
                            .status(aggregatedStatus)
                            .details(responseTopology)
                            .build();
                });
    }

    private Mono<Map.Entry<String, String>> checkEndpoint(Map.Entry<String, String> endpointDefinition) {
        String alias = endpointDefinition.getKey();
        String url = endpointDefinition.getValue();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(3))
                .build();

        return Mono.fromFuture(httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding()))
                .map(response -> {
                    boolean isUp = response.statusCode() >= 200 && response.statusCode() < 400;
                    String statusMessage = isUp ? "UP" : "DOWN (HTTP " + response.statusCode() + ")";
                    return (Map.Entry<String, String>) Map.entry(alias, statusMessage);
                })
                .onErrorResume(error -> {
                    injectBootContext();
                    log.debug("[EXTERNAL_HEALTH_PROBE] - Diagnostic failure for [{}] ({}) - Reason: {}", alias, url, error.getMessage());
                    return Mono.just((Map.Entry<String, String>) Map.entry(alias, "DOWN (" + error.getMessage() + ")"));
                });
    }

    private String resolveExecutionEnvironment() {
        String osName = System.getProperty("os.name", "Unknown OS");
        String osArch = System.getProperty("os.arch", "Unknown Arch");
        String osContext = String.format("(%s %s)", osName, osArch);
        try {
            boolean isKubernetes = System.getenv("KUBERNETES_SERVICE_HOST") != null;
            boolean isDocker = new File("/.dockerenv").exists() || new File("/run/.containerenv").exists();

            if (isKubernetes) return "Kubernetes Pod " + osContext;
            else if (isDocker) return "Docker Container " + osContext;
            else return "Bare-Metal / Local OS " + osContext;
        } catch (SecurityException e) {
            return "Restricted Environment " + osContext;
        }
    }

    private String resolveHostname() {
        String envHost = System.getenv("HOSTNAME");
        if (envHost != null && !envHost.isBlank()) return envHost;
        String winHost = System.getenv("COMPUTERNAME");
        if (winHost != null && !winHost.isBlank()) return winHost;
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-host";
        }
    }

    private String resolveIpAddress() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "unknown-ip";
        }
    }
}
