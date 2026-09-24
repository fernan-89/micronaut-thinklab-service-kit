package com.thinklab.kit.events;

import io.micronaut.context.event.StartupEvent;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.api.StreamInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NatsStreamInitializerTest {

    @Mock private JetStreamManagement jetStreamManagement;
    @Mock private StartupEvent startupEvent;

    private final EventsProperties properties = new EventsProperties();

    @Test
    @DisplayName("an already-existing stream is left untouched")
    void streamAlreadyExists() throws Exception {
        when(jetStreamManagement.getStreamInfo("THINKLAB_EVENTS")).thenReturn(mock(StreamInfo.class));

        new NatsStreamInitializer(jetStreamManagement, properties).onApplicationEvent(startupEvent);

        verify(jetStreamManagement, never()).addStream(any());
    }

    @Test
    @DisplayName("a missing stream (404) is created")
    void streamMissingIsCreated() throws Exception {
        JetStreamApiException notFound = mock(JetStreamApiException.class);
        when(notFound.getErrorCode()).thenReturn(404);
        when(jetStreamManagement.getStreamInfo("THINKLAB_EVENTS")).thenThrow(notFound);

        new NatsStreamInitializer(jetStreamManagement, properties).onApplicationEvent(startupEvent);

        verify(jetStreamManagement).addStream(any());
    }

    @Test
    @DisplayName("a non-404 API error is not treated as missing and does not create a stream")
    void otherApiErrorIsNotCreated() throws Exception {
        JetStreamApiException serverError = mock(JetStreamApiException.class);
        when(serverError.getErrorCode()).thenReturn(500);
        when(jetStreamManagement.getStreamInfo("THINKLAB_EVENTS")).thenThrow(serverError);

        new NatsStreamInitializer(jetStreamManagement, properties).onApplicationEvent(startupEvent);

        verify(jetStreamManagement, never()).addStream(any());
    }

    @Test
    @DisplayName("a connectivity failure at startup is contained, never propagated")
    void connectivityFailureIsContained() throws Exception {
        when(jetStreamManagement.getStreamInfo("THINKLAB_EVENTS")).thenThrow(new IOException("broker down"));

        new NatsStreamInitializer(jetStreamManagement, properties).onApplicationEvent(startupEvent);

        verify(jetStreamManagement, never()).addStream(any());
    }

    @Test
    @DisplayName("mandatory collaborators and the startup event are null-checked")
    void nullGuards() {
        assertThrows(NullPointerException.class, () -> new NatsStreamInitializer(null, properties));
        assertThrows(NullPointerException.class, () -> new NatsStreamInitializer(jetStreamManagement, null));
        assertThrows(NullPointerException.class,
                () -> new NatsStreamInitializer(jetStreamManagement, properties).onApplicationEvent(null));
    }
}
