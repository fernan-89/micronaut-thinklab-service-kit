package com.thinklab.kit.data;

import com.mongodb.ConnectionString;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.reactivestreams.client.MongoClient;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.beans.BeanProperty;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.Index;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collection;
import java.util.Objects;

/**
 * Creates the MongoDB indexes each {@code @MappedEntity} declares with {@code @Index}/{@code @Indexes}.
 *
 * <p>Micronaut Data MongoDB reads those annotations for schema metadata but never creates the indexes
 * (its {@code create-collections} option only creates collections), so on their own they are dead
 * annotations and every query they were meant to serve scans the whole collection. Found by the
 * Testcontainers suite (ADR-004; decision in ADR-005). {@code createIndex} is idempotent, so running this on every startup
 * is safe.
 *
 * <p>Names follow what Micronaut Data MongoDB actually stores, which is not the generic
 * {@code RuntimeEntityRegistry} view (that one applies the SQL snake_case strategy): the collection is the
 * {@code @MappedEntity} value (else the simple class name), the {@code @Id} property is {@code _id}, a
 * {@code @MappedProperty} value wins, and every other property keeps its declared name. Entities that set
 * a custom {@code namingStrategy} are not supported.
 *
 * <p>Fail-open, like {@code NatsStreamInitializer}: an index that cannot be created (for example a
 * conflicting existing index) is logged and skipped, and an unreachable server stops the attempt without
 * stopping the application. Turn it off with {@code thinklab.mongo.create-indexes=false} (unit-test
 * contexts that have no MongoDB do).
 */
@Singleton
@Requires(beans = MongoClient.class)
@Requires(classes = MappedEntity.class)
@Requires(property = "thinklab.mongo.create-indexes", notEquals = "false")
public class MongoIndexInitializer implements ApplicationEventListener<StartupEvent> {

    private static final Logger log = LoggerFactory.getLogger(MongoIndexInitializer.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final MongoClient mongoClient;
    private final String database;
    private final Collection<BeanIntrospection<Object>> entities;

    @Inject
    public MongoIndexInitializer(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this(mongoClient, mongoUri, BeanIntrospector.SHARED.findIntrospections(MappedEntity.class));
    }

    /** Test seam: the entity introspections to scan. */
    MongoIndexInitializer(MongoClient mongoClient, String mongoUri, Collection<BeanIntrospection<Object>> entities) {
        this.mongoClient = Objects.requireNonNull(mongoClient, "Infrastructure constraint violated: MongoClient cannot be null.");
        this.database = new ConnectionString(Objects.requireNonNull(mongoUri, "Infrastructure constraint violated: mongodb.uri cannot be null.")).getDatabase();
        this.entities = Objects.requireNonNull(entities, "Infrastructure constraint violated: entities cannot be null.");
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        Objects.requireNonNull(event, "Application constraint violated: StartupEvent cannot be null.");
        try {
            for (BeanIntrospection<Object> introspection : entities) {
                introspection.getAnnotationValuesByType(Index.class).forEach(index -> createIndex(introspection, index));
            }
        } catch (MongoTimeoutException e) {
            log.error("[MONGO_INDEXES] MongoDB unreachable; declared indexes were not created. Reason: {}", e.getMessage());
        }
    }

    private void createIndex(BeanIntrospection<Object> entity, AnnotationValue<Index> index) {
        Document keys = new Document();
        for (String column : index.stringValues("columns")) {
            keys.append(fieldName(entity, column), 1);
        }
        IndexOptions options = new IndexOptions().unique(index.isTrue("unique"));
        index.stringValue("name").filter(name -> !name.isBlank()).ifPresent(options::name);
        String collection = entity.stringValue(MappedEntity.class).filter(name -> !name.isBlank())
                .orElseGet(() -> entity.getBeanType().getSimpleName());
        try {
            String created = Mono.from(mongoClient.getDatabase(database).getCollection(collection).createIndex(keys, options))
                    .block(TIMEOUT);
            log.info("[MONGO_INDEXES] Ensured index [{}] {} on [{}.{}]", created, keys.toJson(), database, collection);
        } catch (MongoTimeoutException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("[MONGO_INDEXES] Could not create index {} on [{}.{}]: {}", keys.toJson(), database, collection, e.getMessage());
        }
    }

    /** {@code @Index} columns name properties; the stored field name can differ (the id is {@code _id}). */
    private static String fieldName(BeanIntrospection<Object> entity, String column) {
        return entity.getProperty(column)
                .map(property -> storedName(property, column))
                .orElse(column);
    }

    private static String storedName(BeanProperty<Object, Object> property, String column) {
        if (property.hasStereotype(Id.class)) {
            return "_id";
        }
        return property.stringValue(MappedProperty.class).filter(name -> !name.isBlank()).orElse(column);
    }
}
