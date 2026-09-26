package com.thinklab.kit.data;

import com.mongodb.MongoTimeoutException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanProperty;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.Index;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class MongoIndexInitializerTest {

    private static final String URI = "mongodb://localhost:27017/thinklab_it_db";

    @Mock private MongoClient mongoClient;
    @Mock private MongoDatabase mongoDatabase;
    @Mock private MongoCollection<Document> collection;

    private final StartupEvent startup = mock(StartupEvent.class);

    @BeforeEach
    void setUp() {
        lenient().when(mongoClient.getDatabase("thinklab_it_db")).thenReturn(mongoDatabase);
        lenient().when(mongoDatabase.getCollection(any(String.class))).thenReturn(collection);
    }

    /** An entity mapped to {@code collectionName} (empty = no @MappedEntity value) declaring {@code indexes}. */
    private static BeanIntrospection<Object> entity(Optional<String> collectionName, List<AnnotationValue<Index>> indexes) {
        BeanIntrospection<Object> introspection = mock(BeanIntrospection.class);
        when(introspection.getAnnotationValuesByType(Index.class)).thenReturn(indexes);
        lenient().when(introspection.stringValue(MappedEntity.class)).thenReturn(collectionName);
        lenient().when(introspection.getBeanType()).thenReturn((Class<Object>) (Class<?>) Widget.class);
        lenient().when(introspection.getProperty(any(String.class))).thenReturn(Optional.empty());
        return introspection;
    }

    private static BeanProperty<Object, Object> property(boolean id, Optional<String> mappedName) {
        BeanProperty<Object, Object> property = mock(BeanProperty.class);
        when(property.hasStereotype(Id.class)).thenReturn(id);
        lenient().when(property.stringValue(MappedProperty.class)).thenReturn(mappedName);
        return property;
    }

    private static AnnotationValue<Index> index(String... columns) {
        return AnnotationValue.builder(Index.class).member("columns", columns).build();
    }

    private MongoIndexInitializer initializer(BeanIntrospection<Object>... entities) {
        return new MongoIndexInitializer(mongoClient, URI, List.of(entities));
    }

    private Bson onlyKeys() {
        ArgumentCaptor<Bson> keys = ArgumentCaptor.forClass(Bson.class);
        verify(collection).createIndex(keys.capture(), any(IndexOptions.class));
        return keys.getValue();
    }

    @Test
    @DisplayName("columns map to stored field names: @Id -> _id, @MappedProperty value, otherwise the declared name")
    void storedFieldNames() {
        BeanIntrospection<Object> widget = entity(Optional.of("widgets"), List.of(index("tenantId", "id", "ref", "blankRef", "plain")));
        BeanProperty<Object, Object> id = property(true, Optional.empty());
        BeanProperty<Object, Object> ref = property(false, Optional.of("ext_ref"));
        BeanProperty<Object, Object> blankRef = property(false, Optional.of(" "));
        BeanProperty<Object, Object> plain = property(false, Optional.empty());
        when(widget.getProperty("id")).thenReturn(Optional.of(id));
        when(widget.getProperty("ref")).thenReturn(Optional.of(ref));
        when(widget.getProperty("blankRef")).thenReturn(Optional.of(blankRef));
        when(widget.getProperty("plain")).thenReturn(Optional.of(plain));
        when(collection.createIndex(any(Bson.class), any(IndexOptions.class))).thenReturn(Mono.just("idx"));

        initializer(widget).onApplicationEvent(startup);

        verify(mongoDatabase).getCollection("widgets");
        assertEquals(new Document("tenantId", 1).append("_id", 1).append("ext_ref", 1).append("blankRef", 1).append("plain", 1),
                onlyKeys());
    }

    @Test
    @DisplayName("without a @MappedEntity value (or a blank one) the collection is the simple class name")
    void defaultCollectionName() {
        when(collection.createIndex(any(Bson.class), any(IndexOptions.class))).thenReturn(Mono.just("idx"));

        initializer(entity(Optional.empty(), List.of(index("a"))), entity(Optional.of(""), List.of(index("b"))))
                .onApplicationEvent(startup);

        verify(mongoDatabase, times(2)).getCollection("Widget");
    }

    @Test
    @DisplayName("unique and name are carried over; a blank name is left to MongoDB")
    void uniqueAndName() {
        when(collection.createIndex(any(Bson.class), any(IndexOptions.class))).thenReturn(Mono.just("idx"));
        AnnotationValue<Index> named = AnnotationValue.builder(Index.class)
                .member("columns", new String[]{"code"}).member("unique", true).member("name", "uq_code").build();
        AnnotationValue<Index> blankName = AnnotationValue.builder(Index.class)
                .member("columns", new String[]{"other"}).member("name", " ").build();

        initializer(entity(Optional.of("widgets"), List.of(named, blankName, index("third")))).onApplicationEvent(startup);

        ArgumentCaptor<IndexOptions> options = ArgumentCaptor.forClass(IndexOptions.class);
        verify(collection, times(3)).createIndex(any(Bson.class), options.capture());
        assertTrue(options.getAllValues().get(0).isUnique());
        assertEquals("uq_code", options.getAllValues().get(0).getName());
        assertNull(options.getAllValues().get(1).getName());
        assertFalse(options.getAllValues().get(2).isUnique());
        assertNull(options.getAllValues().get(2).getName());
    }

    @Test
    @DisplayName("entities without indexes create nothing")
    void entitiesWithoutIndexes() {
        initializer(entity(Optional.of("widgets"), List.of())).onApplicationEvent(startup);

        verify(mongoClient, never()).getDatabase(any());
    }

    @Test
    @DisplayName("a failing index is logged and the next one is still attempted")
    void failOpenPerIndex() {
        when(collection.createIndex(any(Bson.class), any(IndexOptions.class)))
                .thenReturn(Mono.error(new IllegalStateException("IndexOptionsConflict")))
                .thenReturn(Mono.just("idx"));

        initializer(entity(Optional.of("widgets"), List.of(index("a"), index("b")))).onApplicationEvent(startup);

        verify(collection, times(2)).createIndex(any(Bson.class), any(IndexOptions.class));
    }

    @Test
    @DisplayName("an unreachable server stops the attempt without failing startup")
    void unreachableServerStopsTheAttempt() {
        when(collection.createIndex(any(Bson.class), any(IndexOptions.class)))
                .thenReturn(Mono.error(new MongoTimeoutException("no server")));

        initializer(entity(Optional.of("widgets"), List.of(index("a"), index("b")))).onApplicationEvent(startup);

        verify(collection, times(1)).createIndex(any(Bson.class), any(IndexOptions.class));
    }

    @Test
    @DisplayName("constructor and event guards")
    void guards() {
        List<BeanIntrospection<Object>> none = List.of();
        assertThrows(NullPointerException.class, () -> new MongoIndexInitializer(null, URI, none));
        assertThrows(NullPointerException.class, () -> new MongoIndexInitializer(mongoClient, null, none));
        assertThrows(NullPointerException.class, () -> new MongoIndexInitializer(mongoClient, URI, null));
        assertThrows(NullPointerException.class, () -> new MongoIndexInitializer(mongoClient, URI, none).onApplicationEvent(null));
    }

    @Test
    @DisplayName("the injection constructor scans the @MappedEntity introspections on the classpath")
    void injectionConstructor() {
        new MongoIndexInitializer(mongoClient, URI).onApplicationEvent(startup);

        verify(mongoClient, never()).getDatabase(any());
    }

    private static final class Widget {
    }
}
