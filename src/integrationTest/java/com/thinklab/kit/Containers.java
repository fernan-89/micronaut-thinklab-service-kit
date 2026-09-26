package com.thinklab.kit;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The real infrastructure the event backbone runs against, started once per JVM and shared by every
 * integration test (Testcontainers' Ryuk sidecar removes them when the JVM exits).
 *
 * <p>MongoDB runs as a single-node replica set, the same topology the local stack uses since kit 0.4.2 —
 * without it there are no multi-document transactions to test. Testcontainers 2.x makes the replica set
 * opt-in ({@code withReplicaSet()}); the default is a standalone server.
 */
public final class Containers {

    public static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:7.0")).withReplicaSet();

    public static final GenericContainer<?> NATS = new GenericContainer<>(DockerImageName.parse("nats:2.10-alpine"))
            .withCommand("-js")
            .withExposedPorts(4222)
            .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));

    static {
        MONGO.start();
        NATS.start();
    }

    private Containers() {
    }

    /** Replica-set URL pointing at {@code database}; each test class uses its own database. */
    public static String mongoUri(String database) {
        return MONGO.getReplicaSetUrl(database);
    }

    public static String natsUrl() {
        return "nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222);
    }
}
