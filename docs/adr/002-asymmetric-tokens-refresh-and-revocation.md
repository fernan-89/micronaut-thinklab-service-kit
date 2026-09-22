# ADR-002: Asymmetric tokens, refresh rotation and session revocation

## Status
Accepted — supersedes the token-issuance and service-token parts of the party-authentication service's
[ADR-020](https://github.com/fernan-89/micronaut-party-authentication-service/blob/master/docs/adr/020-credentials-sessions-and-platform-security.md)
(0.2.x shipped a single shared HMAC secret, every service could both sign and verify, and there was no
refresh or revocation).

## Context
0.2.x's `JwtService` used HS256 with one secret shared by every service. That means any compromised service —
or any service at all, by design — could mint tokens for any tenant, any role. It also had no way to end a
session before its access token expired, and no refresh flow, so clients were pushed toward long-lived access
tokens to avoid re-authenticating constantly, which makes the "no revocation" gap worse.

## Decision
1. **Split signing from verification.** `JwtSigner` (issuer only) and `JwtVerifier` (everyone) replace the
   single `JwtService`. Tokens are ES256 (EC P-256), never HS256: a verifying service holds no secret capable
   of forging a token, only public keys.
2. **Keys are looked up by `kid`, not assumed.** `LocalKeyStore` holds the issuer's own key (configured or, in
   development, generated once at startup — a restart invalidates every token, which is acceptable outside
   production). `KeyProvider` resolves a verifier's key from a static `public-key` or the issuer's JWKS
   endpoint, caching the remote set and refreshing it — at most once per `jwks-min-refresh-seconds` — only when
   an unknown `kid` appears, so key rotation needs no restart and a flood of forged `kid` values cannot hammer
   the issuer.
3. **Sessions, not just tokens.** A login now carries an opaque `sid` claim. `RevocationList` is an in-memory
   set of revoked sessions (revoked until the instant their last access token would have expired, then
   forgotten); `RevocationPoller` keeps it in step with the issuer's published list
   (`thinklab.security.revocation-url`), tolerating a transient outage by keeping the previous list rather than
   failing open.
4. **Service-to-service tokens are also brokered, not shared.** `ServiceTokenProvider` is the seam:
   `ClientCredentialsTokenProvider` is the default (calls the issuer's `token-url` with a client id/secret and
   caches the result), and the issuer itself provides a local implementation that signs directly — see
   party-authentication's `LocalServiceTokenProvider` — since it never needs to call itself over the network.
5. **The transport is a plain interface.** `HttpTransport` wraps the two blocking calls this package needs
   (fetch the JWKS, POST the token endpoint) behind an interface purely so every failure mode — timeout,
   malformed JSON, connection refused — is unit-testable without a real server.

## Consequences
- Positive: a compromised or buggy non-issuer service cannot forge tokens; key rotation and session revocation
  both work without a restart; the service-to-service credential is not a value copy-pasted into every
  service's configuration.
- Negative: verifying services now depend on reaching the issuer's JWKS and revocation endpoints (mitigated by
  caching and fail-safe-open-on-poll-failure, which is a deliberate availability-over-strictness trade: a
  revocation can take up to one poll interval to take effect everywhere). The issuer is a harder single point
  of trust than before — losing its private key means reissuing it and invalidating every outstanding token.
