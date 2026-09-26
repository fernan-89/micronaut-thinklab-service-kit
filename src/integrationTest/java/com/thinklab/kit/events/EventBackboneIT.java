package com.thinklab.kit.events;

import com.thinklab.kit.Containers;
import com.mongodb.client.model.Filters;
import com.mongodb.reactivestreams.client.MongoClient;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import io.nats.client.JetStreamManagement;
import io.nats.client.api.MessageInfo;
import io.nats.client.api.StreamInfo;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole event backbone wired by Micronaut, exactly as a producing service gets it with
 * {@code thinklab.events.enabled=true}: stream bootstrap, transactional append, and the scheduled relay
 * publishing to JetStream and marking the event as published.
 *
 * <p>{@code transactional = false}: the test itself must not run inside a rollback transaction, or the
 * commit/rollback behaviour under test would be masked.
 */
@MicronautTest(transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventBackboneIT implements TestPropertyProvider {

    private static final String DATABASE = "kit_backbone_it";
    private static final String COLLECTION = "outbox_events";

    @Override
    public Map<String, String> getProperties() {
        return Map.of(
                "mongodb.uri", Containers.mongoUri(DATABASE),
                "mongodb.uuid-representation", "STANDARD",
                "thinklab.events.enabled", "true",
                "thinklab.events.nats-url", Containers.natsUrl(),
                "thinklab.events.outbox-collection", COLLECTION,
                "thinklab.events.relay-poll", "200ms");
    }

    @Inject
    EventsProperties properties;

    @Inject
    JetStreamManagement jetStreamManagement;

    @Inject
    TransactionalWriter writer;

    @Inject
    MongoClient mongoClient;

    private boolean stored(UUID id) {
        Long count = Flux.from(mongoClient.getDatabase(DATABASE).getCollection(COLLECTION)
                .countDocuments(Filters.eq("_id", id))).blockFirst();
        return count != null && count > 0;
    }

    private Document storedDocument(UUID id) {
        return Flux.from(mongoClient.getDatabase(DATABASE).getCollection(COLLECTION)
                .find(Filters.eq("_id", id))).blockFirst();
    }

    @Test
    @DisplayName("the JetStream stream is created on startup and captures every platform subject")
    void streamIsBootstrapped() throws Exception {
        StreamInfo info = jetStreamManagement.getStreamInfo(properties.getStreamName());

        assertEquals(java.util.List.of(properties.getSubjectPrefix() + ".>"), info.getConfiguration().getSubjects());
    }

    @Test
    @DisplayName("an append inside a transaction that fails is rolled back with it")
    void rollbackDiscardsTheEvent() {
        OutboxEvent event = OutboxEvent.newEvent("thinklab.it.rolled-back", "{}");

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> writer.appendThenFail(event).block());

        // The append itself must have succeeded; only the boundary's own failure may surface here.
        assertEquals("primary write failed after the outbox append", failure.getMessage());
        assertFalse(stored(event.id()));
    }

    @Test
    @DisplayName("a committed event is relayed to JetStream and marked as published")
    void committedEventIsRelayed() throws Exception {
        String subject = "thinklab.it.relayed." + UUID.randomUUID();
        OutboxEvent event = OutboxEvent.newEvent(subject, "{\"hello\":\"world\"}");

        writer.appendAndCommit(event).block();
        assertTrue(stored(event.id()));

        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (storedDocument(event.id()).get("publishedAt") == null && Instant.now().isBefore(deadline)) {
            Thread.sleep(100);
        }

        assertTrue(storedDocument(event.id()).get("publishedAt") != null, "relay did not mark the event as published");
        MessageInfo message = jetStreamManagement.getLastMessage(properties.getStreamName(), subject);
        assertEquals("{\"hello\":\"world\"}", new String(message.getData(), StandardCharsets.UTF_8));
    }
}
