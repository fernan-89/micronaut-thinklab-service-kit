package com.thinklab.kit.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock private OutboxStore store;
    @Mock private EventPublisher publisher;

    private final EventsProperties properties = new EventsProperties();

    @Test
    @DisplayName("every unpublished event is published and then marked published")
    void relaysEveryPendingEvent() {
        OutboxEvent first = OutboxEvent.newEvent("subject.one", "{}");
        OutboxEvent second = OutboxEvent.newEvent("subject.two", "{}");
        when(store.findUnpublished(properties.getRelayBatchSize())).thenReturn(Flux.just(first, second));
        when(publisher.publish(any())).thenReturn(Mono.empty());
        when(store.markPublished(any())).thenReturn(Mono.empty());

        new OutboxRelay(store, publisher, properties).relay();

        verify(publisher).publish(first);
        verify(publisher).publish(second);
        verify(store).markPublished(first.id());
        verify(store).markPublished(second.id());
    }

    @Test
    @DisplayName("a publish failure for one event is logged and does not stop the batch")
    void publishFailureIsContained() {
        OutboxEvent failing = OutboxEvent.newEvent("subject.fails", "{}");
        OutboxEvent ok = OutboxEvent.newEvent("subject.ok", "{}");
        when(store.findUnpublished(properties.getRelayBatchSize())).thenReturn(Flux.just(failing, ok));
        when(publisher.publish(failing)).thenReturn(Mono.error(new IllegalStateException("broker down")));
        when(publisher.publish(ok)).thenReturn(Mono.empty());
        when(store.markPublished(ok.id())).thenReturn(Mono.empty());

        new OutboxRelay(store, publisher, properties).relay();

        verify(store, never()).markPublished(failing.id());
        verify(store).markPublished(ok.id());
    }

    @Test
    @DisplayName("a failure reading the outbox itself is contained and never escapes the scheduled tick")
    void findUnpublishedFailureIsContained() {
        when(store.findUnpublished(properties.getRelayBatchSize()))
                .thenReturn(Flux.error(new IllegalStateException("mongo down")));

        new OutboxRelay(store, publisher, properties).relay();

        verify(publisher, never()).publish(any());
    }

    @Test
    @DisplayName("mandatory collaborators are null-checked")
    void nullGuards() {
        assertThrows(NullPointerException.class, () -> new OutboxRelay(null, publisher, properties));
        assertThrows(NullPointerException.class, () -> new OutboxRelay(store, null, properties));
        assertThrows(NullPointerException.class, () -> new OutboxRelay(store, publisher, null));
    }
}
