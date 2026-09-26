package com.thinklab.kit.data;

import com.mongodb.reactivestreams.client.MongoClient;
import com.thinklab.kit.Containers;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link MongoIndexInitializer} in a real context: the declared indexes exist once the application has started. */
@MicronautTest(transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoIndexInitializerIT implements TestPropertyProvider {

    private static final String DATABASE = "kit_indexes_it";

    @Override
    public Map<String, String> getProperties() {
        return Map.of("mongodb.uri", Containers.mongoUri(DATABASE), "mongodb.uuid-representation", "STANDARD");
    }

    @Inject
    MongoClient mongoClient;

    @Test
    @DisplayName("the @Indexes of a @MappedEntity are created on startup, with their unique flag, name and stored field names")
    void declaredIndexesAreCreated() {
        List<Document> indexes = Flux.from(mongoClient.getDatabase(DATABASE).getCollection("indexed_widget").listIndexes())
                .collectList().block();

        assertTrue(indexes.stream().anyMatch(i -> i.get("key", Document.class).equals(new Document("tenantId", 1).append("status", 1))),
                () -> "indexes: " + indexes);
        Document code = indexes.stream().filter(i -> "uq_widget_code".equals(i.getString("name"))).findFirst().orElseThrow();
        assertEquals(new Document("code", 1), code.get("key", Document.class));
        assertTrue(code.getBoolean("unique"));
        assertTrue(indexes.stream().anyMatch(i -> i.get("key", Document.class).equals(new Document("ext_ref", 1))),
                () -> "indexes: " + indexes);
    }
}
