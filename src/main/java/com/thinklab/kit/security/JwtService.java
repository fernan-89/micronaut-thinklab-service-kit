package com.thinklab.kit.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;

/**
 * Issues and verifies the platform access tokens (HS256 JWT). The algorithm is pinned: a token that announces any
 * other algorithm (including {@code none}) is rejected before its signature is even considered.
 *
 * <p>Claims: {@code sub} = user id, {@code tid} = tenant (organisation) id, {@code role} = {@link Role},
 * plus {@code iss}, {@code iat} and {@code exp}.
 */
@Singleton
public class JwtService {

    private static final int MIN_SECRET_BYTES = 32;
    private static final String TENANT_CLAIM = "tid";
    private static final String ROLE_CLAIM = "role";

    private final SecurityProperties properties;
    private final Clock clock;

    @Inject
    public JwtService(SecurityProperties properties) {
        this(properties, Clock.systemUTC());
    }

    JwtService(SecurityProperties properties, Clock clock) {
        this.properties = Objects.requireNonNull(properties, "SecurityProperties cannot be null.");
        this.clock = clock;
    }

    /** Signs an access token for the given identity. */
    public String issue(String subject, String tenantId, Role role) {
        Objects.requireNonNull(subject, "subject cannot be null.");
        Objects.requireNonNull(tenantId, "tenantId cannot be null.");
        Objects.requireNonNull(role, "role cannot be null.");
        byte[] secret = secret();

        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(properties.getIssuer())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(properties.getTtlSeconds())))
                .claim(TENANT_CLAIM, tenantId)
                .claim(ROLE_CLAIM, role.name())
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(signerFor(secret));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Unable to sign the access token.", e);
        }
    }

    /** Verifies a compact token and returns its identity, or throws {@link AuthenticationFailedException}. */
    public AuthenticatedPrincipal verify(String token) {
        if (token == null || token.isBlank()) {
            throw new AuthenticationFailedException("Access token is missing.");
        }
        byte[] secret = secret();
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) {
                throw new AuthenticationFailedException("Unsupported token algorithm.");
            }
            if (!jwt.verify(verifierFor(secret))) {
                throw new AuthenticationFailedException("Token signature is invalid.");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (!properties.getIssuer().equals(claims.getIssuer())) {
                throw new AuthenticationFailedException("Token issuer is not trusted.");
            }
            Date expiry = claims.getExpirationTime();
            if (expiry == null || !expiry.toInstant().isAfter(clock.instant())) {
                throw new AuthenticationFailedException("Token has expired.");
            }
            return principalOf(claims);
        } catch (ParseException e) {
            throw new AuthenticationFailedException("Token is malformed.", e);
        } catch (JOSEException e) {
            throw new AuthenticationFailedException("Token could not be verified.", e);
        }
    }

    /** Test seam: the signer for the shared secret. */
    JWSSigner signerFor(byte[] secret) throws JOSEException {
        return new MACSigner(secret);
    }

    /** Test seam: the verifier for the shared secret. */
    JWSVerifier verifierFor(byte[] secret) throws JOSEException {
        return new MACVerifier(secret);
    }

    private static AuthenticatedPrincipal principalOf(JWTClaimsSet claims) throws ParseException {
        String subject = claims.getSubject();
        String tenant = claims.getStringClaim(TENANT_CLAIM);
        String role = claims.getStringClaim(ROLE_CLAIM);
        if (subject == null || tenant == null || role == null) {
            throw new AuthenticationFailedException("Token is missing mandatory claims.");
        }
        try {
            return new AuthenticatedPrincipal(subject, tenant, Role.valueOf(role));
        } catch (IllegalArgumentException e) {
            throw new AuthenticationFailedException("Token carries an unknown role.", e);
        }
    }

    private byte[] secret() {
        String configured = properties.getSecret();
        if (configured == null || configured.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("thinklab.security.secret must be configured with at least 32 bytes.");
        }
        return configured.getBytes(StandardCharsets.UTF_8);
    }
}
