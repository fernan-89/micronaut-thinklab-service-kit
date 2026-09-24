package com.thinklab.kit.events;

import io.nats.client.JetStream;
import io.nats.client.api.PublishAck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NatsEventPublisherTest {

    @Mock private JetStream jetStream;

    @Test
    @DisplayName("publish sends the payload bytes to the event's subject")
    void publishSuccess() throws Exception {
        when(jetStream.publish(eq("thinklab.party-authentication.user.initiated"), any(byte[].class)))
                .thenReturn(mock(PublishAck.class));

        NatsEventPublisher publisher = new NatsEventPublisher(jetStream);
        OutboxEvent event = OutboxEvent.newEvent("thinklab.party-authentication.user.initiated", "{\"a\":1}");

        StepVerifier.create(publisher.publish(event)).verifyComplete();

        verify(jetStream).publish(eq("thinklab.party-authentication.user.initiated"), eq("{\"a\":1}".getBytes()));
    }

    @Test
    @DisplayName("a broker failure is propagated as an error")
    void publishFailure() throws Exception {
        when(jetStream.publish(any(String.class), any(byte[].class)))
                .thenThrow(new java.io.IOException("nope"));

        NatsEventPublisher publisher = new NatsEventPublisher(jetStream);
        OutboxEvent event = OutboxEvent.newEvent("subject", "{}");

        StepVerifier.create(publisher.publish(event)).expectError(java.io.IOException.class).verify();
    }

    @Test
    @DisplayName("the JetStream collaborator is null-checked")
    void nullGuard() {
        assertThrows(NullPointerException.class, () -> new NatsEventPublisher(null));
        assertThrows(NullPointerException.class, () -> new NatsEventPublisher(jetStream).publish(null));
    }
}
