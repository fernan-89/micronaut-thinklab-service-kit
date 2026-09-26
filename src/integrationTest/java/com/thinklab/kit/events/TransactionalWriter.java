package com.thinklab.kit.events;

import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;

/**
 * Stands in for a producing use case (e.g. party-authentication's {@code UserCreationWriter}): appends
 * an outbox event inside a Micronaut Data transaction, then either completes or fails the boundary.
 */
@Singleton
class TransactionalWriter {

    private final OutboxStore outboxStore;

    TransactionalWriter(OutboxStore outboxStore) {
        this.outboxStore = outboxStore;
    }

    @Transactional
    public Mono<OutboxEvent> appendAndCommit(OutboxEvent event) {
        return outboxStore.append(event);
    }

    @Transactional
    public Mono<OutboxEvent> appendThenFail(OutboxEvent event) {
        return outboxStore.append(event)
                .then(Mono.error(new IllegalStateException("primary write failed after the outbox append")));
    }
}
