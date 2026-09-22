package com.thinklab.kit.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

/**
 * Issues access tokens (ES256). Only the authentication service instantiates it; constructing it loads or creates the
 * signing key so the public half is available from the very first request.
 *
 * <p>Claims: {@code sub} user or service, {@code tid} tenant, {@code role}, optional {@code sid} (login session, used for
 * revocation), plus {@code iss}, {@code iat}, {@code exp}, {@code jti}. The header carries the {@code kid}.
 */
@Singleton
public class JwtSigner {

    private final SecurityProperties properties;
    private final ECKey signingKey;
    private final Clock clock;

    @Inject
    public JwtSigner(SecurityProperties properties, LocalKeyStore keyStore) {
        this(properties, keyStore, Clock.systemUTC());
    }

    JwtSigner(SecurityProperties properties, LocalKeyStore keyStore, Clock clock) {
        this.properties = Objects.requireNonNull(properties, "SecurityProperties cannot be null.");
        this.signingKey = keyStore.signingKey();
        this.clock = clock;
    }

    /** Signs an access token; {@code sessionId} may be null for tokens that are not tied to a login session. */
    public String issue(String subject, String tenantId, Role role, String sessionId) {
        Objects.requireNonNull(subject, "subject cannot be null.");
        Objects.requireNonNull(tenantId, "tenantId cannot be null.");
        Objects.requireNonNull(role, "role cannot be null.");

        Instant now = clock.instant();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(properties.getIssuer())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(properties.getTtlSeconds())))
                .jwtID(UUID.randomUUID().toString())
                .claim("tid", tenantId)
                .claim("role", role.name());
        if (sessionId != null) {
            claims.claim("sid", sessionId);
        }
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(signingKey.getKeyID()).build(), claims.build());
            jwt.sign(signerFor(signingKey));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Unable to sign the access token.", e);
        }
    }

    /** Lifetime, in seconds, of the tokens this signer issues. */
    public long ttlSeconds() {
        return properties.getTtlSeconds();
    }

    /** Test seam: the JWS signer for the key. */
    JWSSigner signerFor(ECKey key) throws JOSEException {
        return new ECDSASigner(key);
    }
}
