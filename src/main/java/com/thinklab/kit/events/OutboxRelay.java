package com.thinklab.kit.events;

import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Objects;

/**
 * Polls the {@link OutboxStore} for unpublished events and relays them through the {@link EventPublisher}.
 * At-least-once by construction: a crash between a successful publish and {@code markPublished}
 * redelivers the same event on the next tick, so every consumer must be idempotent (kit ADR-003).
 * Mirrors {@link com.thinklab.kit.security.RevocationPoller}: fail-open per event, never lets one bad
 * event stop the batch or the scheduler.
 */
@Singleton
@Requires(property = "thinklab.events.enabled", value = "true")
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxStore store;
    private final EventPublisher publisher;
    private final EventsProperties properties;

    @Inject
    public OutboxRelay(OutboxStore store, EventPublisher publisher, EventsProperties properties) {
        this.store = Objects.requireNonNull(store, "Infrastructure constraint violated: OutboxStore cannot be null.");
        this.publisher = Objects.requireNonNull(publisher, "Infrastructure constraint violated: EventPublisher cannot be null.");
        this.properties = Objects.requireNonNull(properties, "Infrastructure constraint violated: EventsProperties cannot be null.");
    }

    @Scheduled(fixedDelay = "${thinklab.events.relay-poll:5s}", initialDelay = "2s")
    public void relay() {
        store.findUnpublished(properties.getRelayBatchSize())
                .concatMap(this::relayOne)
                .then()
                .onErrorResume(e -> {
                    log.warn("[EVENTS] Outbox relay tick failed, will retry on the next tick: {}", e.getMessage());
                    return Mono.empty();
                })
                .block();
    }

    private Mono<Void> relayOne(OutboxEvent event) {
        return publisher.publish(event)
                .then(Mono.defer(() -> store.markPublished(event.id())))
                .onErrorResume(e -> {
                    log.warn("[EVENTS] Publish failed for event [{}] on subject [{}], retrying next tick: {}",
                            event.id(), event.subject(), e.getMessage());
                    return Mono.empty();
                });
    }
}
