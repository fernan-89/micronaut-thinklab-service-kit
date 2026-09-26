package com.thinklab.kit.events;

import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.reactivestreams.client.ClientSession;
import com.mongodb.reactivestreams.client.FindPublisher;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import io.micronaut.data.connection.ConnectionStatus;
import io.micronaut.data.connection.reactive.ReactorConnectionOperations;
import org.bson.BsonObjectId;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class OutboxMongoStoreTest {

    @Mock private MongoClient mongoClient;
    @Mock private MongoDatabase mongoDatabase;
    @Mock private MongoCollection<OutboxMongoStore.OutboxEventDocument> mongoCollection;
    @Mock private ReactorConnectionOperations<ClientSession> connectionOperations;

    private OutboxMongoStore store;

    @BeforeEach
    void setUp() {
        lenient().when(mongoClient.getDatabase("thinklab_events_test_db")).thenReturn(mongoDatabase);
        lenient().when(mongoDatabase.getCollection("outbox_events", OutboxMongoStore.OutboxEventDocument.class)).thenReturn(mongoCollection);
        lenient().when(mongoCollection.withCodecRegistry(any())).thenReturn(mongoCollection);

        EventsProperties properties = new EventsProperties();
        store = new OutboxMongoStore(mongoClient, "mongodb://localhost:27017/thinklab_events_test_db", properties, connectionOperations);
    }

    @Test
    @DisplayName("append inserts the document and returns the event when no ambient transaction exists")
    void append() {
        lenient().when(connectionOperations.findConnectionStatus(any())).thenReturn(Optional.empty());
        when(mongoCollection.insertOne(any(OutboxMongoStore.OutboxEventDocument.class)))
                .thenReturn(Mono.just(InsertOneResult.acknowledged(new BsonObjectId(new ObjectId()))));

        OutboxEvent event = OutboxEvent.newEvent("thinklab.party-authentication.user.initiated", "{}");

        StepVerifier.create(store.append(event))
                .expectNext(event)
                .verifyComplete();
    }

    @Test
    @DisplayName("append writes through the client that started the ambient session, not the injected proxy")
    void appendUsesTheSessionOriginator() {
        MongoClient originator = mock(MongoClient.class);
        MongoDatabase originatorDatabase = mock(MongoDatabase.class);
        MongoCollection<OutboxMongoStore.OutboxEventDocument> originatorCollection = mock(MongoCollection.class);
        when(originator.getDatabase("thinklab_events_test_db")).thenReturn(originatorDatabase);
        when(originatorDatabase.getCollection("outbox_events", OutboxMongoStore.OutboxEventDocument.class)).thenReturn(originatorCollection);
        when(originatorCollection.withCodecRegistry(any())).thenReturn(originatorCollection);
        ClientSession session = mock(ClientSession.class);
        when(session.getOriginator()).thenReturn(originator);
        ConnectionStatus<ClientSession> status = mock(ConnectionStatus.class);
        when(status.getConnection()).thenReturn(session);
        when(connectionOperations.findConnectionStatus(any())).thenReturn(Optional.of(status));
        when(originatorCollection.insertOne(eq(session), any(OutboxMongoStore.OutboxEventDocument.class)))
                .thenReturn(Mono.just(InsertOneResult.acknowledged(new BsonObjectId(new ObjectId()))));

        OutboxEvent event = OutboxEvent.newEvent("thinklab.party-authentication.user.initiated", "{}");

        StepVerifier.create(store.append(event))
                .expectNext(event)
                .verifyComplete();
        verify(originatorCollection).insertOne(eq(session), any(OutboxMongoStore.OutboxEventDocument.class));
        verifyNoInteractions(mongoCollection);
    }

    @Test
    @DisplayName("append joins the caller's ambient session when a @Transactional boundary already opened one")
    void appendJoinsAmbientSession() {
        ClientSession session = mock(ClientSession.class);
        ConnectionStatus<ClientSession> status = mock(ConnectionStatus.class);
        when(status.getConnection()).thenReturn(session);
        when(connectionOperations.findConnectionStatus(any())).thenReturn(Optional.of(status));
        when(mongoCollection.insertOne(eq(session), any(OutboxMongoStore.OutboxEventDocument.class)))
                .thenReturn(Mono.just(InsertOneResult.acknowledged(new BsonObjectId(new ObjectId()))));

        OutboxEvent event = OutboxEvent.newEvent("thinklab.party-authentication.user.initiated", "{}");

        StepVerifier.create(store.append(event))
                .expectNext(event)
                .verifyComplete();
        verify(mongoCollection).insertOne(eq(session), any(OutboxMongoStore.OutboxEventDocument.class));
    }

    @Test
    @DisplayName("append falls back to a standalone write when the service has no Micronaut Data Mongo at all")
    void appendWithoutConnectionOperationsBean() {
        OutboxMongoStore storeWithoutConnectionOperations =
                new OutboxMongoStore(mongoClient, "mongodb://localhost:27017/thinklab_events_test_db", new EventsProperties(), null);
        when(mongoCollection.insertOne(any(OutboxMongoStore.OutboxEventDocument.class)))
                .thenReturn(Mono.just(InsertOneResult.acknowledged(new BsonObjectId(new ObjectId()))));

        OutboxEvent event = OutboxEvent.newEvent("thinklab.party-authentication.user.initiated", "{}");

        StepVerifier.create(storeWithoutConnectionOperations.append(event))
                .expectNext(event)
                .verifyComplete();
    }

    @Test
    @DisplayName("findUnpublished maps every unpublished document back to the domain event")
    void findUnpublished() {
        OutboxEvent event = OutboxEvent.newEvent("thinklab.party-authentication.user.initiated", "{}");
        FindPublisher<OutboxMongoStore.OutboxEventDocument> publisher = mock(FindPublisher.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(publisher);
        when(publisher.sort(any())).thenReturn(publisher);
        when(publisher.limit(20)).thenReturn(publisher);
        doAnswer(invocation -> {
            org.reactivestreams.Subscriber<OutboxMongoStore.OutboxEventDocument> subscriber = invocation.getArgument(0);
            Flux.just(OutboxMongoStore.OutboxEventDocument.fromDomain(event)).subscribe(subscriber);
            return null;
        }).when(publisher).subscribe(any());

        StepVerifier.create(store.findUnpublished(20))
                .expectNextMatches(found -> found.id().equals(event.id()) && found.subject().equals(event.subject()))
                .verifyComplete();
    }

    @Test
    @DisplayName("findUnpublished completes empty when nothing is pending")
    void findUnpublishedEmpty() {
        FindPublisher<OutboxMongoStore.OutboxEventDocument> publisher = mock(FindPublisher.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(publisher);
        when(publisher.sort(any())).thenReturn(publisher);
        when(publisher.limit(20)).thenReturn(publisher);
        doAnswer(invocation -> {
            org.reactivestreams.Subscriber<OutboxMongoStore.OutboxEventDocument> subscriber = invocation.getArgument(0);
            Flux.<OutboxMongoStore.OutboxEventDocument>empty().subscribe(subscriber);
            return null;
        }).when(publisher).subscribe(any());

        StepVerifier.create(store.findUnpublished(20)).verifyComplete();
    }

    @Test
    @DisplayName("markPublished sets publishedAt")
    void markPublished() {
        when(mongoCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)));

        StepVerifier.create(store.markPublished(UUID.randomUUID())).verifyComplete();
    }

    @Test
    @DisplayName("mandatory collaborators and arguments are null-checked")
    void nullGuards() {
        EventsProperties properties = new EventsProperties();
        assertThrows(NullPointerException.class, () -> new OutboxMongoStore(null, "mongodb://h/db", properties, connectionOperations));
        assertThrows(NullPointerException.class, () -> new OutboxMongoStore(mongoClient, "mongodb://h/db", null, connectionOperations));
        assertThrows(NullPointerException.class, () -> new OutboxMongoStore(mongoClient, null, properties, connectionOperations));
        assertThrows(NullPointerException.class, () -> store.append(null));
        assertThrows(NullPointerException.class, () -> store.markPublished(null));
    }

    @Test
    @DisplayName("the document mapper round-trips every field")
    void documentMapperRoundTrip() {
        Instant now = Instant.now();
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), "subject", "{\"a\":1}", now, now, 2);

        OutboxMongoStore.OutboxEventDocument document = OutboxMongoStore.OutboxEventDocument.fromDomain(event);
        OutboxEvent roundTripped = document.toDomain();

        assertEquals(event, roundTripped);
        assertEquals(event.id(), document.getId());
        assertEquals(event.subject(), document.getSubject());
        assertEquals(event.payloadJson(), document.getPayloadJson());
        assertEquals(event.occurredAt(), document.getOccurredAt());
        assertEquals(event.publishedAt(), document.getPublishedAt());
        assertEquals(event.attempts(), document.getAttempts());
    }
}
