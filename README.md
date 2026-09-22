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
dependencies { implementation 'com.thinklab:thinklab-service-kit:0.3.0' }
```

and in `Application.main`: `com.thinklab.kit.telemetry.ReactorMdcBridge.register();`.

## Build

```
./gradlew check                 # tests + coverage gate (100% line / 100% branch)
./gradlew publishToMavenLocal   # make the artifact available to the sibling services
```

Publishing to GitHub Packages runs from CI on a `v*` tag. The consuming repositories must be granted read
access under **Package settings -> Manage Actions access** the first time.

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

## Versioning

Semantic versioning. A behaviour change in a shared class ships as a new version that each service adopts
deliberately; the services never share a mutable snapshot.

## License

Proprietary - all rights reserved. See [LICENSE](LICENSE). This software is not open source.
