# ADR-005: Create the MongoDB indexes the entities declare

## Status
Accepted (0.5.0).

## Context
Every ThinkLab service declares its query indexes with Micronaut Data's `@Indexes`/`@Index` on its
`@MappedEntity` classes (for example `hash_token` on `tenantId+status`, `tenantId+payload`,
`tenantId+generatedHash`). The Testcontainers suite (ADR-004) showed that none of them existed: a
freshly started service had only the `_id` index. Micronaut Data MongoDB reads those annotations as
metadata but never creates the indexes; its `micronaut.data.mongodb.create-collections` option only
creates collections. Every query the indexes were written for — including the duplicate check on each hash
generation — was a full collection scan.

## Decision
The kit ships `MongoIndexInitializer`. On startup it scans every `@MappedEntity` introspection and calls
`createIndex` for each declared `@Index` (idempotent), carrying `unique` and `name`.

Field names follow what Micronaut Data MongoDB stores, not the generic `RuntimeEntityRegistry` view,
which applies the SQL snake_case naming strategy (`tenantId` would become `tenant_id`, an index on a field
no document has): the collection is the `@MappedEntity` value (else the simple class name), the `@Id`
property is `_id`, a `@MappedProperty` value wins, and every other property keeps its declared name.

It is fail-open: an index that cannot be created is logged and skipped, and an unreachable server stops
the attempt without stopping the application. It is on by default wherever a `MongoClient` bean and
Micronaut Data are present; `thinklab.mongo.create-indexes=false` turns it off (unit-test contexts without
MongoDB set this).

## Consequences
- Services get their declared indexes by moving to 0.5.0, with no code of their own.
- Index builds run at startup. On large existing collections the first start after adoption can take
  longer while MongoDB builds them.
- Entities with a custom `namingStrategy` are not supported; none exists today.
