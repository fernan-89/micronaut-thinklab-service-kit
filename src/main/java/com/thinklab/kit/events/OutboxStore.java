package com.thinklab.kit.events;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Port for the transactional-outbox pattern. There is no MongoDB replica set in any environment this
 * platform runs on, so there is no multi-document transaction spanning an aggregate write and the
 * outbox row: producers append the event immediately after their own write succeeds (see kit
 * ADR-003 for the accepted, documented gap this leaves).
 */
public interface OutboxStore {

    Mono<OutboxEvent> append(OutboxEvent event);

    Flux<OutboxEvent> findUnpublished(int batchSize);

    Mono<Void> markPublished(UUID eventId);
}
