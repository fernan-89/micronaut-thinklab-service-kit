package com.thinklab.kit.events;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Introspected;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.codecs.pojo.annotations.BsonId;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Generic, Mongo-backed {@link OutboxStore}. Every service that turns on {@code thinklab.events.enabled}
 * gets this bean for free instead of hand-writing its own Mongo adapter — the database name is derived
 * from the same {@code mongodb.uri} property the service already configures (same trick as
 * {@link com.thinklab.kit.health.MongoWarmupObserver}), so no service-specific wiring is needed.
 */
@Singleton
@Requires(property = "thinklab.events.enabled", value = "true")
@Requires(beans = MongoClient.class)
public class OutboxMongoStore implements OutboxStore {

    /**
     * The MongoDB driver's default codec registry has no codec for arbitrary POJOs such as
     * {@link OutboxEventDocument}. Without a {@link PojoCodecProvider} every read/write fails with
     * {@code CodecConfigurationException} (lesson learned from the Party Reference Data Directory rollout,
     * repeated in every Mongo adapter since — see {@code AssetMongoRepositoryAdapter}).
     */
    private static final CodecRegistry POJO_CODEC_REGISTRY = CodecRegistries.fromRegistries(
            MongoClientSettings.getDefaultCodecRegistry(),
            CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build())
    );

    private final MongoClient mongoClient;
    private final String database;
    private final String collectionName;

    @Inject
    public OutboxMongoStore(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri, EventsProperties properties) {
        this.mongoClient = Objects.requireNonNull(mongoClient, "Infrastructure constraint violated: MongoClient cannot be null.");
        Objects.requireNonNull(properties, "Infrastructure constraint violated: EventsProperties cannot be null.");
        this.database = new ConnectionString(Objects.requireNonNull(mongoUri, "Infrastructure constraint violated: mongodb.uri cannot be null.")).getDatabase();
        this.collectionName = properties.getOutboxCollection();
    }

    private MongoCollection<OutboxEventDocument> getCollection() {
        return mongoClient.getDatabase(database)
                .getCollection(collectionName, OutboxEventDocument.class)
                .withCodecRegistry(POJO_CODEC_REGISTRY);
    }

    @Override
    public Mono<OutboxEvent> append(OutboxEvent event) {
        Objects.requireNonNull(event, "Infrastructure constraint violated: OutboxEvent cannot be null.");
        return Mono.from(getCollection().insertOne(OutboxEventDocument.fromDomain(event)))
                .thenReturn(event);
    }

    @Override
    public Flux<OutboxEvent> findUnpublished(int batchSize) {
        return Flux.from(getCollection()
                        .find(Filters.eq("publishedAt", null))
                        .sort(Sorts.ascending("occurredAt"))
                        .limit(batchSize))
                .map(OutboxEventDocument::toDomain);
    }

    @Override
    public Mono<Void> markPublished(UUID eventId) {
        Objects.requireNonNull(eventId, "Infrastructure constraint violated: eventId cannot be null.");
        return Mono.from(getCollection().updateOne(Filters.eq("_id", eventId), Updates.set("publishedAt", Instant.now())))
                .then();
    }

    // Must be public: the BSON PojoCodecProvider reflects into the getters, and a public method on a
    // package-private class is not reflectively accessible (found live: IllegalAccessException /
    // CodecConfigurationException on every outbox append, only surfaced once a real Mongo write ran).
    @Introspected
    public static final class OutboxEventDocument {

        @BsonId
        private UUID id;
        private String subject;
        private String payloadJson;
        private Instant occurredAt;
        private Instant publishedAt;
        private int attempts;

        static OutboxEventDocument fromDomain(OutboxEvent event) {
            OutboxEventDocument doc = new OutboxEventDocument();
            doc.setId(event.id());
            doc.setSubject(event.subject());
            doc.setPayloadJson(event.payloadJson());
            doc.setOccurredAt(event.occurredAt());
            doc.setPublishedAt(event.publishedAt());
            doc.setAttempts(event.attempts());
            return doc;
        }

        OutboxEvent toDomain() {
            return new OutboxEvent(getId(), getSubject(), getPayloadJson(), getOccurredAt(), getPublishedAt(), getAttempts());
        }

        public UUID getId() { return id; }
        public void setId(UUID id) { this.id = id; }
        public String getSubject() { return subject; }
        public void setSubject(String subject) { this.subject = subject; }
        public String getPayloadJson() { return payloadJson; }
        public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
        public Instant getOccurredAt() { return occurredAt; }
        public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
        public Instant getPublishedAt() { return publishedAt; }
        public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
        public int getAttempts() { return attempts; }
        public void setAttempts(int attempts) { this.attempts = attempts; }
    }
}
