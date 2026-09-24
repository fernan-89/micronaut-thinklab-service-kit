package com.thinklab.kit.events;

import reactor.core.publisher.Mono;

/** Port for publishing a relayed {@link OutboxEvent} to the broker. */
public interface EventPublisher {

    Mono<Void> publish(OutboxEvent event);
}
