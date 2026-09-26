# thinklab-service-kit

Shared runtime infrastructure for every ThinkLab Micronaut service. It removes the copy-and-paste
baseline that used to live in each repository (five identical copies of the health and telemetry
classes, with drift already observed) so it is written, tested and fixed **once**.

## What is inside

| Package | Class | Purpose |
|---|---|---|
| `com.thinklab.kit.telemetry` | `TraceIdFilter` | W3C `traceparent` extraction, PII-safe client IP, MDC population, async reverse DNS |
| `com.thinklab.kit.telemetry` | `ReactorMdcBridge` | Reactor hook that carries the MDC across thread hops (`register()` from `main`) |
| `com.thinklab.kit.health` | `ExternalEndpointsHealthIndicator` | Startup warm-up and readiness probe of `warmup.endpoints.*` dependencies |
| `com.thinklab.kit.health` | `MongoWarmupObserver` | Startup SDAM warm-up with progressive back-off and fail-fast shutdown |
| `com.thinklab.kit.health` | `ApplicationShutdownObserver` | Structured teardown telemetry |
| `com.thinklab.kit.events` | `OutboxStore` / `OutboxMongoStore` | Transactional-outbox storage (generic, Mongo-backed) |
| `com.thinklab.kit.events` | `EventPublisher` / `NatsEventPublisher` | Publishes a relayed event to NATS JetStream |
| `com.thinklab.kit.events` | `OutboxRelay` / `NatsStreamInitializer` | Scheduled outbox-to-broker relay; idempotent stream bootstrap |

Beans are discovered automatically (`@Singleton` / `@Filter`); the two health classes are disabled in the
`test` environment. `MongoWarmupObserver` needs `mongodb.uri` and a reactive `MongoClient` bean, so the
kit declares the Mongo driver `compileOnly` and each service supplies it.

## Using it

```groovy
repositories {
    mavenLocal()          // local development: ./gradlew publishToMavenLocal in this repo
    mavenCentral()
    maven {               // CI: GitHub Packages
        url = uri('https://maven.pkg.github.com/fernan-89/thinklab-service-kit')
        credentials { username = System.getenv('GITHUB_ACTOR'); password = System.getenv('GITHUB_TOKEN') }
    }
}
dependencies { implementation 'com.thinklab:thinklab-service-kit:0.4.2' }
```

and in `Application.main`: `com.thinklab.kit.telemetry.ReactorMdcBridge.register();`.

## Build and test

```
./gradlew test                  # unit tests + coverage gate (100% line / 100% branch); no Docker needed
./gradlew integrationTest       # Testcontainers suite: real MongoDB replica set and NATS JetStream (Docker)
./gradlew check                 # both suites, as CI runs it
./gradlew publishToMavenLocal   # make the artifact available to the sibling services
```

The unit suite mocks the Mongo driver and the NATS client and alone carries the coverage gate. The
integration suite (`src/integrationTest`, [ADR-004](docs/adr/004-integration-tests-with-testcontainers.md))
runs the kit against real infrastructure: the BSON round trip, outbox query semantics, a Micronaut Data
transaction committing or rolling back an append, stream bootstrap, the scheduled relay publishing to
JetStream, and the declared MongoDB indexes being created.

## Releasing

Bump `version` in `build.gradle` in the PR. When it merges, CI publishes that version to GitHub Packages
and creates the `v<version>` tag and GitHub release. A merge that does not change the version publishes
nothing. Do not push `v*` tags by hand.

## Security (0.3.0)

Set `thinklab.security.enabled=true` to turn on `SecurityFilter`: bearer JWT (**ES256**, pinned algorithm, key
looked up by `kid`) required outside `thinklab.security.public-paths`, role-based method authorisation
(`Role`), and `X-Tenant-Id` / `X-Executor` / `X-Role` derived from the token (a conflicting tenant header is
403; `SERVICE` tokens act for any tenant).

The trust model is asymmetric: only the token **issuer** (the authentication service) holds a private key and
uses `JwtSigner` to mint tokens; every other service only **verifies**, with `JwtVerifier` and `KeyProvider`
resolving public keys from a static `thinklab.security.public-key` or fetched lazily from
`thinklab.security.jwks-url`. `LocalKeyStore` is the issuer's own EC P-256 key
(`thinklab.security.private-key`, a JWK; an ephemeral key is generated when unset — development only).
`RevocationList` + `RevocationPoller` (`thinklab.security.revocation-url`) let a session be revoked before its
access token would otherwise expire. `ServiceTokenClientFilter` attaches a service-to-service token obtained
through `ServiceTokenProvider`; the default `ClientCredentialsTokenProvider` calls the issuer's
`thinklab.security.token-url` (client id/secret), while the issuer itself replaces that bean with a local
signer (see the authentication service's `LocalServiceTokenProvider`). Errors: `ERR-AUTH-00401` /
`ERR-AUTH-00403`.

## Events (0.4.0, transactions in 0.4.2, fixed in 0.4.3)

Set `thinklab.events.enabled=true` to turn on the event backbone. A producer appends an `OutboxEvent` (via
`OutboxStore`, typically the kit's own `OutboxMongoStore`) after its own aggregate write. On a platform
running MongoDB as a replica set (true of the local stack since 2026-09-26), `OutboxMongoStore.append` will
join an ambient MongoDB session if the caller opened one with `@Transactional` — the aggregate write and
the outbox append then commit or roll back together, closing the "lost event" gap. This is opt-in per
producer: a use case that calls `outboxStore.append` outside any `@Transactional` boundary keeps the
original best-effort behavior (append fails, primary write still stands, failure logged and swallowed).
See ADR-003's 0.4.2 addendum for the mechanism and the self-invocation gotcha (the `@Transactional` method
must live on its own injected bean, not a private method called via `this::`).
`OutboxRelay` polls unpublished rows on `thinklab.events.relay-poll` (default `5s`) and
publishes each one through `EventPublisher` (`NatsEventPublisher` by default) to NATS JetStream, marking
it published on success and leaving it for the next tick on failure — delivery is at-least-once, so every
consumer must be idempotent. `NatsStreamInitializer` idempotently creates the stream
(`thinklab.events.stream-name`, default `THINKLAB_EVENTS`, subjects `thinklab.events.subject-prefix.>`)
on startup and is deliberately fail-open: an unreachable broker degrades the event backbone, it never
stops the service. Subscribing to JetStream is not a kit concern yet — with only one consumer
(`notification-dispatch-service`) it stays hand-written there until a second one justifies a shared
abstraction.

Requires the `io.nats:jnats` dependency at runtime (the kit only declares it `compileOnly`, same
philosophy as the Mongo driver) and `mongodb.uri` + a reactive `MongoClient` bean if using
`OutboxMongoStore`. A producer can always inject `OutboxStore` regardless of whether events are enabled:
`NoopOutboxStore` (`@Secondary`) is the fallback bean when `OutboxMongoStore` is not active, so turning
the event backbone on or off never changes a use case's constructor.

## MongoDB indexes (0.5.0)

`MongoIndexInitializer` creates, on startup, the indexes each `@MappedEntity` declares with
`@Indexes`/`@Index`. Micronaut Data MongoDB never creates them itself
([ADR-005](docs/adr/005-create-declared-mongodb-indexes.md)). It is on by default wherever a `MongoClient`
and Micronaut Data are present and fails open. Set `thinklab.mongo.create-indexes: false` in unit-test
contexts that have no MongoDB.

## Versioning

Semantic versioning. A behaviour change in a shared class ships as a new version that each service adopts
deliberately; the services never share a mutable snapshot.

## License

Proprietary - all rights reserved. See [LICENSE](LICENSE). This software is not open source.
