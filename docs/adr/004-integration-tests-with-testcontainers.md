# ADR-004: Integration tests against real infrastructure with Testcontainers

## Status
Accepted (0.4.3). Template for the ThinkLab services, which adopt it one repository at a time.

## Context
Every test in the kit mocked the MongoDB driver and the NATS client. That keeps the suite fast and carries
the 100% line/branch gate, but the bugs that reached the live stack were all of a kind mocks cannot show:
a POJO class the BSON codec could not reflect into (ADR-003, 0.4.1), UUID `@Id` codec failures in
party-authentication, and — found while introducing this ADR — a transactional append that failed on
every call because the session and the collection came from different `MongoClient` instances (ADR-003,
0.4.3 addendum). MongoDB transactions also need a replica set, which no unit test can provide.

## Decision
- A separate `integrationTest` suite (Gradle JVM Test Suite, `src/integrationTest`) runs against real
  containers started with Testcontainers 2.x: `mongo:7.0` as a **single-node replica set**
  (`MongoDBContainer.withReplicaSet()` — the replica set is opt-in in 2.x) and `nats:2.10-alpine -js`.
- Containers start once per JVM (`Containers`) and are shared; each test isolates itself with its own
  database or collection.
- `./gradlew check` runs both suites, so CI always exercises the integration suite (GitHub-hosted runners
  have Docker). `./gradlew test` stays Docker-free.
- **The coverage gate stays on the unit suite only.** Integration tests add confidence about behaviour
  against real infrastructure; they are not a way to reach coverage, and a build without Docker must still
  be able to prove the gate.
- Plain Testcontainers rather than Micronaut Test Resources: the infrastructure each test depends on
  stays visible in the test code, and NATS JetStream has no Test Resources module.

## Consequences
- Contributors need Docker to run `check` locally; `test` alone does not.
- CI time grows by the container start-up (roughly 10-20 s for both images).
- Each service repeats a small `Containers` holder; if it grows beyond that, it can move to a published
  test-fixtures artifact of this kit.
