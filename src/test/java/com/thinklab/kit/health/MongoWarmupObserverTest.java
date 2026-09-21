package com.thinklab.kit.health;

import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoDatabase;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.event.StartupEvent;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MongoWarmupObserverTest {

    private MongoClient client;
    private MongoDatabase admin;
    private MongoDatabase app;
    private ApplicationContext context;
    private StartupEvent event;

    @BeforeEach
    void setUp() {
        client = mock(MongoClient.class);
        admin = mock(MongoDatabase.class);
        app = mock(MongoDatabase.class);
        context = mock(ApplicationContext.class);
        event = mock(StartupEvent.class);
        when(client.getDatabase("admin")).thenReturn(admin);
        when(client.getDatabase("thinklab")).thenReturn(app);
    }

    @Test
    @DisplayName("a healthy cluster is pinged on admin and on the URI database, and the context stays up")
    void warmupSucceeds() {
        when(admin.runCommand(any(Bson.class))).thenReturn(Mono.just(new Document("ok", 1)));
        when(app.runCommand(any(Bson.class))).thenReturn(Mono.just(new Document("ok", 1)));

        new MongoWarmupObserver(client, "mongodb://localhost:27017/thinklab", context).onApplicationEvent(event);

        verify(admin).runCommand(any(Bson.class));
        verify(app).runCommand(any(Bson.class));
        verify(context, never()).stop();
    }

    @Test
    @DisplayName("a URI without a database falls back to admin")
    void missingDatabaseFallsBackToAdmin() {
        when(admin.runCommand(any(Bson.class))).thenReturn(Mono.just(new Document("ok", 1)));

        new MongoWarmupObserver(client, "mongodb://localhost:27017", context).onApplicationEvent(event);

        verify(client, never()).getDatabase(eq("thinklab"));
        verify(context, never()).stop();
    }

    @Test
    @DisplayName("an unparsable URI is tolerated and falls back to admin")
    void unparsableUri() {
        when(admin.runCommand(any(Bson.class))).thenReturn(Mono.just(new Document("ok", 1)));

        new MongoWarmupObserver(client, "not-a-mongo-uri", context).onApplicationEvent(event);

        verify(context, never()).stop();
    }

    @Test
    @DisplayName("mandatory collaborators and the startup event are null-checked")
    void nullGuards() {
        assertThrows(NullPointerException.class, () -> new MongoWarmupObserver(null, "mongodb://h/db", context));
        assertThrows(NullPointerException.class, () -> new MongoWarmupObserver(client, "mongodb://h/db", null));
        assertThrows(NullPointerException.class,
                () -> new MongoWarmupObserver(client, "mongodb://h/db", context).onApplicationEvent(null));
    }
}
