package com.thinklab.kit.events;

import com.thinklab.kit.Containers;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;
import org.bson.UuidRepresentation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OutboxMongoStore} against a real MongoDB: the BSON round trip (UUID {@code _id}, {@link Instant}
 * fields, the POJO codec) and the query semantics that the unit test can only mock.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboxMongoStoreIT {

    private MongoClient client;
    private String uri;

    @BeforeAll
    void connect() {
        uri = Containers.mongoUri("kit_outbox_it");
        // Same UUID representation every service configures (mongodb.uuid-representation: STANDARD).
        client = MongoClients.create(MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(uri))
                .uuidRepresentation(UuidRepresentation.STANDARD)
                .build());
    }

    @AfterAll
    void close() {
        client.close();
    }

    /** A store on its own collection, so tests never see each other's events. */
    private OutboxMongoStore store() {
        EventsProperties properties = new EventsProperties();
        properties.setOutboxCollection("outbox_" + UUID.randomUUID().toString().replace("-", ""));
        return new OutboxMongoStore(client, uri, properties, null);
    }

    private static OutboxEvent event(String subject, Instant occurredAt) {
        return new OutboxEvent(UUID.randomUUID(), subject, "{\"k\":\"v\"}", occurredAt, null, 0);
    }

    @Test
    @DisplayName("an appended event is read back unchanged (UUID id, Instant fields, payload)")
    void roundTrip() {
        OutboxMongoStore store = store();
        OutboxEvent original = event("thinklab.it.round-trip", Instant.now().truncatedTo(ChronoUnit.MILLIS));

        store.append(original).block();
        List<OutboxEvent> unpublished = store.findUnpublished(10).collectList().block();

        assertEquals(List.of(original), unpublished);
    }

    @Test
    @DisplayName("unpublished events come back oldest first and capped at the batch size")
    void orderingAndBatchSize() {
        OutboxMongoStore store = store();
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        OutboxEvent newest = event("thinklab.it.c", base.plusSeconds(3));
        OutboxEvent oldest = event("thinklab.it.a", base.plusSeconds(1));
        OutboxEvent middle = event("thinklab.it.b", base.plusSeconds(2));
        store.append(newest).then(store.append(oldest)).then(store.append(middle)).block();

        List<OutboxEvent> batch = store.findUnpublished(2).collectList().block();

        assertEquals(List.of(oldest.id(), middle.id()), batch.stream().map(OutboxEvent::id).toList());
    }

    @Test
    @DisplayName("markPublished stamps the event and removes it from the unpublished set")
    void markPublished() {
        OutboxMongoStore store = store();
        OutboxEvent kept = event("thinklab.it.kept", Instant.parse("2026-01-01T00:00:00Z"));
        OutboxEvent sent = event("thinklab.it.sent", Instant.parse("2026-01-01T00:00:01Z"));
        store.append(kept).then(store.append(sent)).block();

        store.markPublished(sent.id()).block();
        List<OutboxEvent> unpublished = store.findUnpublished(10).collectList().block();

        assertEquals(List.of(kept.id()), unpublished.stream().map(OutboxEvent::id).toList());
        assertNull(unpublished.get(0).publishedAt());
        assertTrue(unpublished.stream().noneMatch(OutboxEvent::isPublished));
    }
}
