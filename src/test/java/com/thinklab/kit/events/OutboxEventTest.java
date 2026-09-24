package com.thinklab.kit.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxEventTest {

    @Test
    @DisplayName("newEvent stamps a fresh id and occurredAt, and starts unpublished")
    void newEvent() {
        OutboxEvent event = OutboxEvent.newEvent("thinklab.party-authentication.user.initiated", "{}");

        assertNotNull(event.id());
        assertNotNull(event.occurredAt());
        assertFalse(event.isPublished());
    }

    @Test
    @DisplayName("isPublished reflects whether publishedAt is set")
    void isPublished() {
        OutboxEvent unpublished = new OutboxEvent(UUID.randomUUID(), "s", "{}", Instant.now(), null, 0);
        OutboxEvent published = new OutboxEvent(UUID.randomUUID(), "s", "{}", Instant.now(), Instant.now(), 1);

        assertFalse(unpublished.isPublished());
        assertTrue(published.isPublished());
    }
}
