package com.thinklab.kit.events;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.api.RetentionPolicy;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;

/**
 * Idempotently ensures the platform's JetStream stream exists on startup, so a fresh local stack needs
 * no manual broker setup step. Unlike {@link com.thinklab.kit.health.MongoWarmupObserver} this is
 * deliberately fail-open: the event backbone is a soft dependency, so a broker hiccup at boot must never
 * stop the application context (kit ADR-003).
 */
@Singleton
@Requires(property = "thinklab.events.enabled", value = "true")
public class NatsStreamInitializer implements ApplicationEventListener<StartupEvent> {

    private static final Logger log = LoggerFactory.getLogger(NatsStreamInitializer.class);
    private static final int STREAM_NOT_FOUND = 404;

    private final JetStreamManagement jetStreamManagement;
    private final EventsProperties properties;

    @Inject
    public NatsStreamInitializer(JetStreamManagement jetStreamManagement, EventsProperties properties) {
        this.jetStreamManagement = Objects.requireNonNull(jetStreamManagement, "Infrastructure constraint violated: JetStreamManagement cannot be null.");
        this.properties = Objects.requireNonNull(properties, "Infrastructure constraint violated: EventsProperties cannot be null.");
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        Objects.requireNonNull(event, "Application constraint violated: StartupEvent cannot be null.");
        try {
            ensureStream();
        } catch (Exception e) {
            log.error("[EVENTS] Could not ensure the JetStream stream [{}] exists; the event backbone stays degraded until it does. Reason: {}",
                    properties.getStreamName(), e.getMessage());
        }
    }

    private void ensureStream() throws IOException, JetStreamApiException {
        if (streamExists()) {
            log.debug("[EVENTS] JetStream stream [{}] already exists.", properties.getStreamName());
            return;
        }
        jetStreamManagement.addStream(StreamConfiguration.builder()
                .name(properties.getStreamName())
                .subjects(properties.getSubjectPrefix() + ".>")
                .storageType(StorageType.File)
                .retentionPolicy(RetentionPolicy.Limits)
                .maxAge(Duration.ofDays(7))
                .build());
        log.info("[EVENTS] Created JetStream stream [{}] on subjects [{}.>]", properties.getStreamName(), properties.getSubjectPrefix());
    }

    private boolean streamExists() throws IOException, JetStreamApiException {
        try {
            jetStreamManagement.getStreamInfo(properties.getStreamName());
            return true;
        } catch (JetStreamApiException e) {
            if (e.getErrorCode() == STREAM_NOT_FOUND) {
                return false;
            }
            throw e;
        }
    }
}
