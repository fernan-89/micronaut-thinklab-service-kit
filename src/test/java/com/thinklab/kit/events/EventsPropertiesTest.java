package com.thinklab.kit.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EventsPropertiesTest {

    @Test
    @DisplayName("defaults keep the event backbone off and point at a local broker")
    void defaults() {
        EventsProperties properties = new EventsProperties();

        assertFalse(properties.isEnabled());
        assertEquals("nats://localhost:4222", properties.getNatsUrl());
        assertEquals("THINKLAB_EVENTS", properties.getStreamName());
        assertEquals("thinklab", properties.getSubjectPrefix());
        assertEquals("outbox_events", properties.getOutboxCollection());
        assertEquals(20, properties.getRelayBatchSize());
        assertEquals("5s", properties.getRelayPollInterval());
        assertEquals(10, properties.getRelayMaxAttempts());
    }

    @Test
    @DisplayName("every property is settable")
    void settersRoundTrip() {
        EventsProperties properties = new EventsProperties();

        properties.setEnabled(true);
        properties.setNatsUrl("nats://broker:4222");
        properties.setStreamName("STREAM");
        properties.setSubjectPrefix("prefix");
        properties.setOutboxCollection("collection");
        properties.setRelayBatchSize(5);
        properties.setRelayPollInterval("10s");
        properties.setRelayMaxAttempts(3);

        assertEquals(true, properties.isEnabled());
        assertEquals("nats://broker:4222", properties.getNatsUrl());
        assertEquals("STREAM", properties.getStreamName());
        assertEquals("prefix", properties.getSubjectPrefix());
        assertEquals("collection", properties.getOutboxCollection());
        assertEquals(5, properties.getRelayBatchSize());
        assertEquals("10s", properties.getRelayPollInterval());
        assertEquals(3, properties.getRelayMaxAttempts());
    }
}
