package com.thinklab.kit.events;

import io.micronaut.context.annotation.Requires;
import io.nats.client.JetStream;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Default {@link EventPublisher}: publishes to NATS JetStream on the subject the event carries. */
@Singleton
@Requires(property = "thinklab.events.enabled", value = "true")
public class NatsEventPublisher implements EventPublisher {

    private final JetStream jetStream;

    @Inject
    public NatsEventPublisher(JetStream jetStream) {
        this.jetStream = Objects.requireNonNull(jetStream, "Infrastructure constraint violated: JetStream cannot be null.");
    }

    @Override
    public Mono<Void> publish(OutboxEvent event) {
        Objects.requireNonNull(event, "Infrastructure constraint violated: OutboxEvent cannot be null.");
        return Mono.fromCallable(() -> jetStream.publish(event.subject(), event.payloadJson().getBytes(StandardCharsets.UTF_8)))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
}
