package com.thinklab.kit.events;

import io.micronaut.context.annotation.Secondary;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.UUID;

/**
 * Fallback {@link OutboxStore} used whenever the event backbone is off ({@code thinklab.events.enabled}
 * is not {@code true}) or no concrete store such as {@link OutboxMongoStore} is otherwise available.
 * Lets a producer unconditionally depend on {@link OutboxStore} without forcing every service that
 * merely wants to build (or run with events disabled) to also configure a broker.
 * {@link Secondary} makes Micronaut prefer any other candidate (e.g. {@link OutboxMongoStore}) whenever
 * one exists.
 */
@Singleton
@Secondary
public class NoopOutboxStore implements OutboxStore {

    private static final Logger log = LoggerFactory.getLogger(NoopOutboxStore.class);

    @Override
    public Mono<OutboxEvent> append(OutboxEvent event) {
        Objects.requireNonNull(event, "Infrastructure constraint violated: OutboxEvent cannot be null.");
        log.debug("[EVENTS] Event backbone is disabled; discarding event on subject [{}].", event.subject());
        return Mono.just(event);
    }

    @Override
    public Flux<OutboxEvent> findUnpublished(int batchSize) {
        return Flux.empty();
    }

    @Override
    public Mono<Void> markPublished(UUID eventId) {
        Objects.requireNonNull(eventId, "Infrastructure constraint violated: eventId cannot be null.");
        return Mono.empty();
    }
}
