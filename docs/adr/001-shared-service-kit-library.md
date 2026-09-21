# ADR-001: A shared service-kit library instead of a copied baseline

## Status
Accepted

## Context
Every service was cloned from the same baseline, so five repositories carried byte-identical copies of
`TraceIdFilter`, `ReactorMdcBridge`, `ExternalEndpointsHealthIndicator`, `MongoWarmupObserver` and
`ApplicationShutdownObserver`. The copies had already drifted (the hash registry kept an older telemetry
matrix and lacked the `test`-environment guards), each copy needed its own tests, and a fix had to be
applied five times. The blueprint's coverage gap (66-85%) sat almost entirely in these classes.

## Decision
1. Extract them into `thinklab-service-kit`, a Micronaut library published as `com.thinklab:thinklab-service-kit`.
2. The kit is versioned semantically; services pin a version and upgrade deliberately. No snapshot sharing.
3. The kit declares the MongoDB driver `compileOnly` and does not force a logging backend: the service
   owns its runtime.
4. Domain-specific code stays out of the kit. `GlobalExceptionHandler` maps service-specific error codes
   and remains per service; each service keeps its own `ValidationExceptionHandler` that delegates to it.
5. Distribution: `mavenLocal()` for local development, GitHub Packages for CI.

## Consequences
- Positive: one implementation, one test suite (now including the health indicator and the Mongo warm-up
  that were never unit tested), and a single upgrade path for every service.
- Negative: an extra repository and a publish step; a breaking change in the kit needs a coordinated
  version bump across services.
