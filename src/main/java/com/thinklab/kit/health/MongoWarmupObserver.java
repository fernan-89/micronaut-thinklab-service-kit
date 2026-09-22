package com.thinklab.kit.health;

import com.mongodb.ConnectionString;
import com.mongodb.reactivestreams.client.MongoClient;
import com.thinklab.kit.support.HostInfo;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.slf4j.MDC;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.net.InetAddress;
import java.time.Duration;
import java.util.Objects;

/**
 * Infrastructure Component: Proactively initializes the MongoDB Reactive Driver connection pool.
 *
 * <p><b>Architectural Role:</b>
 * By executing a deterministic 'ping' command upon application startup, this observer forces the
 * Server Discovery and Monitoring (SDAM) mechanism to resolve the cluster topology before Kubernetes
 * Readiness Probes poll the health endpoint. This eliminates transient 'UNKNOWN' states and cold-start latency.
 *
 * <p><b>Two-Phase Verification:</b>
 * <ol>
 * <li><b>Cluster Health:</b> Validates global connectivity via the 'admin' database.</li>
 * <li><b>Application Context:</b> Verifies RBAC and connectivity against the specific application database dynamically extracted from the URI.</li>
 * </ol>
 *
 * <p><b>Resilience & Circuit Breaker (ADR-002):</b>
 * If the database is unreachable, this component acts as a passive Circuit Breaker with progressive backoff.
 *
 * <p><b>Synchronous Barrier (ADR-007):</b>
 * Blocks the startup thread until database checks succeed or fail-fast triggers.
 *
 * @author Thinklab Systems Engineering Team
 * @version 2.9.1-NASA-SRE
 * @since 1.0
 */
@Singleton
@Requires(notEnv = "test")
@Requires(beans = MongoClient.class)
@Slf4j
public class MongoWarmupObserver implements ApplicationEventListener<StartupEvent> {

    private final MongoClient mongoClient;
    private final String applicationDatabase;
    private final String clusterHosts;
    private final String infrastructureIpAddress;
    private final ApplicationContext applicationContext;
    private final Duration[] backoff;

    @Inject
    public MongoWarmupObserver(
            MongoClient mongoClient,
            @Property(name = "mongodb.uri") String mongoUri,
            ApplicationContext applicationContext
    ) {
        this(mongoClient, mongoUri, applicationContext,
                new Duration[]{Duration.ofSeconds(15), Duration.ofSeconds(30), Duration.ofSeconds(60)});
    }

    /** Test seam: the retry back-off schedule (one entry per retry, then the failure is definitive). */
    MongoWarmupObserver(MongoClient mongoClient, String mongoUri, ApplicationContext applicationContext, Duration[] backoff) {
        this.backoff = backoff.clone();
        this.mongoClient = Objects.requireNonNull(mongoClient, "Application constraint violated: MongoClient cannot be null.");
        this.applicationContext = Objects.requireNonNull(applicationContext, "Application constraint violated: ApplicationContext cannot be null.");
        this.infrastructureIpAddress = HostInfo.ipAddress("unknown-ip");

        String parsedDatabase = null;
        String parsedHosts = "unknown-cluster";

        try {
            ConnectionString connectionString = new ConnectionString(mongoUri);
            parsedDatabase = connectionString.getDatabase();

            parsedHosts = String.join(",", connectionString.getHosts());
        } catch (IllegalArgumentException e) {
            log.warn("[MONGODB_WARMUP] URI Parse Error: Failed to extract topology dynamically. Reason: {}. Falling back to 'admin'...", e.getMessage());
        }

        this.applicationDatabase = parsedDatabase != null ? parsedDatabase : "admin";
        this.clusterHosts = parsedHosts;
    }

    private void injectBootContext() {
        HostInfo.injectSystemContext("SYSTEM-BOOT", this.infrastructureIpAddress, "Micronaut-Engine/Startup");
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        Objects.requireNonNull(event, "Application constraint violated: StartupEvent cannot be null.");

        injectBootContext();

        log.info("[MONGODB_WARMUP] Initializing SDAM topology discovery... [component=mongodb | status=INIT | host={}]", clusterHosts);
        log.debug("[MONGODB_WARMUP] Target database dynamically resolved from URI: [{}]", applicationDatabase);

        BsonDocument pingCommand = new BsonDocument("ping", new BsonInt32(1));
        long startTime = System.currentTimeMillis();

        try {
            Mono.from(mongoClient.getDatabase("admin").runCommand(pingCommand))
                    .doOnSuccess(adminRes -> {
                        injectBootContext();
                        log.debug("[MONGODB_WARMUP] Cluster connectivity verified. Checking application database: [{}]", applicationDatabase);
                    })
                    .flatMap(adminRes -> Mono.from(mongoClient.getDatabase(applicationDatabase).runCommand(pingCommand)))
                    .timeout(Duration.ofSeconds(5))
                    .retryWhen(Retry.from(retrySignals -> retrySignals.flatMap(rs -> {
                        injectBootContext();
                        int attempt = (int) rs.totalRetries();
                        Throwable error = rs.failure();

                        if (attempt < backoff.length) {
                            log.warn("[MONGODB_CIRCUIT_BREAKER] Attempt {} failed. Pod is UNREADY. Retrying in {}s... [error={}]",
                                    attempt + 1, backoff[attempt].toSeconds(), error.getMessage());
                            return Mono.delay(backoff[attempt]);
                        }

                        log.error("[MONGODB_CIRCUIT_BREAKER] Exhausted all connection retry attempts.");
                        return Mono.error(error);
                    })))
                    .doOnSuccess(appResult -> {
                        injectBootContext();
                        long duration = System.currentTimeMillis() - startTime;
                        log.info("[MONGODB_WARMUP] Telemetry established successfully. [component=mongodb | status=UP | host={} | db={} | latency={}ms]",
                                clusterHosts, applicationDatabase, duration);
                    })
                    .doOnError(error -> {
                        injectBootContext();
                        long duration = System.currentTimeMillis() - startTime;
                        log.error("[MONGODB_WARMUP] CRITICAL: Topology discovery definitively failed! Initiating container shutdown. [component=mongodb | status=DOWN | host={} | db={} | latency={}ms | cause='{}']",
                                clusterHosts, applicationDatabase, duration, error.getMessage(), error);
                        applicationContext.stop();
                    })
                    .block(Duration.ofSeconds(120));

            injectBootContext();
            log.debug("[MONGODB_WARMUP] Warmup lifecycle completed successfully.");

        } catch (Exception e) {
            injectBootContext();
            log.error("[MONGODB_WARMUP] Synchronous barrier caught terminal error. Awaiting context shutdown...");
        } finally {
            injectBootContext();
        }
    }
}
