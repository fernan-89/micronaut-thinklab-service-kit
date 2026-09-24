package com.thinklab.kit.events;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamManagement;
import io.nats.client.Nats;
import jakarta.inject.Singleton;

/**
 * Bootstraps the NATS connection and its JetStream contexts. This is the one class in the event
 * backbone that is deliberately excluded from the JaCoCo coverage gate (see build.gradle) — the literal
 * {@code Nats.connect(url)} call needs a live broker and cannot be exercised in a unit test, the same
 * reasoning every service already applies to its own {@code Application.class}. Everything downstream
 * of this factory (which only ever receives the resulting {@link Connection}/{@link JetStream}/
 * {@link JetStreamManagement} as constructor arguments) stays under the 100%/100% gate via plain
 * interface mocking.
 */
@Factory
@Requires(property = "thinklab.events.enabled", value = "true")
public class NatsConnectionFactory {

    @Singleton
    @Bean(preDestroy = "close")
    public Connection natsConnection(EventsProperties properties) throws Exception {
        return Nats.connect(properties.getNatsUrl());
    }

    @Singleton
    public JetStream jetStream(Connection connection) throws Exception {
        return connection.jetStream();
    }

    @Singleton
    public JetStreamManagement jetStreamManagement(Connection connection) throws Exception {
        return connection.jetStreamManagement();
    }
}
