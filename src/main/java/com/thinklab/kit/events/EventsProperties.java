package com.thinklab.kit.events;

import io.micronaut.context.annotation.ConfigurationProperties;

/**
 * Binds {@code thinklab.events.*}. The event backbone is off by default so local development stays
 * simple; a service opts in by setting {@link #enabled} and depends on NATS JetStream being reachable.
 */
@ConfigurationProperties("thinklab.events")
public class EventsProperties {

    private boolean enabled;
    private String natsUrl = "nats://localhost:4222";
    private String streamName = "THINKLAB_EVENTS";
    private String subjectPrefix = "thinklab";
    private String outboxCollection = "outbox_events";
    private int relayBatchSize = 20;
    private String relayPollInterval = "5s";
    private int relayMaxAttempts = 10;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getNatsUrl() {
        return natsUrl;
    }

    public void setNatsUrl(String natsUrl) {
        this.natsUrl = natsUrl;
    }

    public String getStreamName() {
        return streamName;
    }

    public void setStreamName(String streamName) {
        this.streamName = streamName;
    }

    /** Every subject this platform publishes starts with this prefix; the stream captures {@code <prefix>.>}. */
    public String getSubjectPrefix() {
        return subjectPrefix;
    }

    public void setSubjectPrefix(String subjectPrefix) {
        this.subjectPrefix = subjectPrefix;
    }

    public String getOutboxCollection() {
        return outboxCollection;
    }

    public void setOutboxCollection(String outboxCollection) {
        this.outboxCollection = outboxCollection;
    }

    public int getRelayBatchSize() {
        return relayBatchSize;
    }

    public void setRelayBatchSize(int relayBatchSize) {
        this.relayBatchSize = relayBatchSize;
    }

    public String getRelayPollInterval() {
        return relayPollInterval;
    }

    public void setRelayPollInterval(String relayPollInterval) {
        this.relayPollInterval = relayPollInterval;
    }

    /** Reserved for a future dead-letter/backoff policy; not yet enforced by {@link OutboxRelay}. */
    public int getRelayMaxAttempts() {
        return relayMaxAttempts;
    }

    public void setRelayMaxAttempts(int relayMaxAttempts) {
        this.relayMaxAttempts = relayMaxAttempts;
    }
}
