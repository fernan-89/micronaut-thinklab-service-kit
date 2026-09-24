package com.thinklab.kit.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A domain event waiting to be relayed to the broker. {@code payloadJson} is a pre-serialized JSON
 * string — the kit stays off any JSON library; the producing service's own serializer builds it before
 * calling {@link OutboxStore#append(OutboxEvent)}.
 */
public record OutboxEvent(UUID id, String subject, String payloadJson, Instant occurredAt,
                           Instant publishedAt, int attempts) {

    public static OutboxEvent newEvent(String subject, String payloadJson) {
        return new OutboxEvent(UUID.randomUUID(), subject, payloadJson, Instant.now(), null, 0);
    }

    public boolean isPublished() {
        return publishedAt != null;
    }
}
