package com.thinklab.kit.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

class NoopOutboxStoreTest {

    private final NoopOutboxStore store = new NoopOutboxStore();

    @Test
    @DisplayName("append discards the event and still completes with it")
    void append() {
        OutboxEvent event = OutboxEvent.newEvent("subject", "{}");

        StepVerifier.create(store.append(event)).expectNext(event).verifyComplete();
    }

    @Test
    @DisplayName("findUnpublished is always empty")
    void findUnpublished() {
        StepVerifier.create(store.findUnpublished(10)).verifyComplete();
    }

    @Test
    @DisplayName("markPublished is a no-op")
    void markPublished() {
        StepVerifier.create(store.markPublished(UUID.randomUUID())).verifyComplete();
    }

    @Test
    @DisplayName("arguments are null-checked")
    void nullGuards() {
        assertThrows(NullPointerException.class, () -> store.append(null));
        assertThrows(NullPointerException.class, () -> store.markPublished(null));
    }
}
