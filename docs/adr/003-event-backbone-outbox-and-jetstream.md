# ADR-003: Event backbone — transactional outbox and NATS JetStream

## Status
Accepted

## Context
Journey 4 of the platform roadmap asks for an "event backbone (outbox + broker)" and a
`notification-dispatch-service` able to react to what happens in other services — the first concrete
case being party-authentication's user creation, which should trigger a welcome notification without
either service calling the other synchronously.

No environment this platform runs on has a MongoDB replica set (everything runs as portable, single-node
binaries, no Docker), so there is no multi-document transaction available to make an aggregate write and
an outbox row atomic in the classical sense. `it-operation-window`'s ADR-018 already accepted a related
kind of non-atomicity (check-then-write across requests) rather than adding infrastructure weight to
close it completely, and documented the residual gap instead of hiding it — that is the precedent this
ADR follows for *style* (accept and document a known limitation), not for the underlying problem, which
is different (a single-writer dual write, not a race between concurrent writers).

Broker choice was decided directly with the product owner: NATS JetStream, not Kafka. JetStream ships as
a single portable Windows binary with no external dependency (no ZooKeeper/KRaft, no JVM of its own),
matching the same no-Docker, portable-binary pattern already used for MongoDB, Node and newman.

## Decision

### Best-effort outbox, not a distributed transaction
A producing use case writes its aggregate first (as it always did) and, only after that write succeeds,
appends an `OutboxEvent` as a second, independent write. A crash between the two writes loses the event
silently — there is no compensating write, change-stream capture, or outbox-then-aggregate ordering that
would close this gap in v1. This is deliberate: closing it fully needs either a replica-set transaction
(infrastructure this platform does not have) or an event-sourced write model (a much larger redesign),
and the cost is not justified for a "M"-sized journey. Every producer must accept that an outbox append
failure is logged and swallowed — it must never fail the primary write.

### The kit owns the outbox storage and the publish side; a consumer's subscribe side stays local
`OutboxStore` is a port (`append`/`findUnpublished`/`markPublished`) with a single concrete, generic
implementation shipped in the kit: `OutboxMongoStore`. It derives its database from the same
`mongodb.uri` property every service already configures — the same trick
`com.thinklab.kit.health.MongoWarmupObserver` uses — so no producing service writes its own Mongo
adapter or repeats the `PojoCodecProvider` codec-registry setup that has already caused real bugs
elsewhere in this platform (`AssetMongoRepositoryAdapter`'s comment, "lesson learned from the Party
Reference Data Directory rollout", applies here too and is not worth relearning per service).

`EventPublisher` (the outbound seam) and its default `NatsEventPublisher` implementation, plus
`NatsStreamInitializer` (idempotent stream creation on startup) and `OutboxRelay` (the scheduled
poll-publish-mark loop), also live in the kit — every future producer gets them for free by turning on
`thinklab.events.enabled` and adding the `io.nats:jnats` runtime dependency.

JetStream *subscribing* is different: only one consumer exists today (`notification-dispatch-service`),
so a generic subscriber abstraction would be speculative. It stays hand-written in that service until a
second consumer actually exists.

### A producer can always inject `OutboxStore`, even with events disabled
`OutboxMongoStore` only exists as a bean when `thinklab.events.enabled=true`. A producing use case,
though, should not have to change its constructor or add conditional wiring depending on whether events
happen to be on for a given environment — that would leak the flag into application code far from where
it is configured. `NoopOutboxStore` is a `@Secondary` fallback bean (always present, always
lowest-priority) that discards `append` calls and returns empty for reads; Micronaut resolves
`OutboxMongoStore` in its place automatically whenever events are enabled. This was found live, not
designed up front: the first integration test run against party-authentication with events disabled
returned 500 on every request, because `InitiateUserUseCase` requires an `OutboxStore` bean that simply
did not exist without it.

### Testability without a live broker
`Connection`, `JetStream` and `JetStreamManagement` are public interfaces, so every class downstream of
the literal `Nats.connect(url)` call is unit-tested the same way this platform already tests the Mongo
driver — direct Mockito interface mocking, no custom transport seam. The one call that cannot be
unit-tested is the connection bootstrap itself (`NatsConnectionFactory`), which needs a live broker; it is
excluded from the JaCoCo gate in `build.gradle`, the same exemption every service already grants its own
`Application.class`.

### `NatsStreamInitializer` is fail-open, unlike `MongoWarmupObserver`
`MongoWarmupObserver` is fail-fast by design (ADR-002 of that observer's own history): Mongo is a hard
dependency, so an unreachable database stops the application context. The event backbone is a soft
dependency by contrast — a service that cannot reach NATS at boot should still serve its normal HTTP
traffic, just without publishing or consuming events until the broker comes back. `NatsStreamInitializer`
therefore only logs at `ERROR` on failure and never calls `ApplicationContext.stop()`.

### Retention policy is `Limits`, not `WorkQueue`
`RetentionPolicy.WorkQueue` deletes a message as soon as *any* durable consumer acknowledges it — correct
for a single dedicated worker queue, but silently wrong the day a second independent consumer of the same
subject appears (a very likely shape for this platform, where several future services will each want to
react to `user.initiated`-style events on their own schedule). `RetentionPolicy.Limits` lets every durable
consumer track its own acknowledgement floor independently.

## Consequences
- Positive: a producer never blocks on a downstream consumer; the outbox/publish/relay machinery is
  written once in the kit instead of once per producing service; the event backbone can be disabled with
  a single property and adds zero runtime cost to a service that does not opt in (proven live already by
  the gateway's coexistence with `MongoWarmupObserver`'s bean-gated behaviour).
- Negative: delivery is at-least-once, never exactly-once — a crash between a successful JetStream publish
  and `markPublished` redelivers the same event on the next relay tick, and a crash between the aggregate
  write and the outbox append loses the event outright. Every consumer must be idempotent. There is no
  dead-letter subject in v1: a consumer that keeps failing a message exhausts `MaxDeliver` and the message
  is terminated (not retried, not parked anywhere) — see `notification-dispatch-service`'s own ADR-024 for
  the consumer-side consequence of this.

## Addendum (0.4.1) — live-found bug: a package-private nested POJO class breaks the BSON codec
`OutboxMongoStore.OutboxEventDocument` was originally declared as a package-private nested class with
only its getters/setters marked `public`. Unit tests passed (Mockito never touches real reflection), but
the first live write threw `CodecConfigurationException` / `IllegalAccessException`: the BSON
`PojoCodecProvider` reflects into the getters at runtime, and a `public` method on a non-`public` class is
not reflectively accessible without the caller calling `setAccessible(true)` first, which this codec does
not do. The class itself must be `public`, not just its members — the same rule `AssetDocument` already
followed by being a top-level public class, which is why this class of bug had never surfaced before.
Fixed in 0.4.1; the earlier 0.4.0 tag is broken for anyone constructing `OutboxMongoStore` and should not
be adopted.
