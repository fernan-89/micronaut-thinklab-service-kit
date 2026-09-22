package com.thinklab.kit.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.text.ParseException;
import java.time.Clock;
import java.util.Date;

/**
 * Verifies access tokens with public keys only. The algorithm is pinned to ES256 (so {@code none}, HMAC and RSA
 * downgrade or key-confusion tricks are rejected), the key is looked up by {@code kid}, and the session must not be
 * on the revocation list.
 */
@Singleton
public class JwtVerifier {

    private final SecurityProperties properties;
    private final KeyProvider keyProvider;
    private final RevocationList revocationList;
    private final Clock clock;

    @Inject
    public JwtVerifier(SecurityProperties properties, KeyProvider keyProvider, RevocationList revocationList) {
        this(properties, keyProvider, revocationList, Clock.systemUTC());
    }

    JwtVerifier(SecurityProperties properties, KeyProvider keyProvider, RevocationList revocationList, Clock clock) {
        this.properties = properties;
        this.keyProvider = keyProvider;
        this.revocationList = revocationList;
        this.clock = clock;
    }

    /** Verifies a compact token and returns its identity, or throws {@link AuthenticationFailedException}. */
    public AuthenticatedPrincipal verify(String token) {
        if (token == null || token.isBlank()) {
            throw new AuthenticationFailedException("Access token is missing.");
        }
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.ES256.equals(jwt.getHeader().getAlgorithm())) {
                throw new AuthenticationFailedException("Unsupported token algorithm.");
            }
            String keyId = jwt.getHeader().getKeyID();
            if (keyId == null) {
                throw new AuthenticationFailedException("Token has no key id.");
            }
            JWK key = keyProvider.find(keyId).orElseThrow(() -> new AuthenticationFailedException("Token key is not trusted."));
            if (!(key instanceof ECKey ecKey)) {
                throw new AuthenticationFailedException("Token key is not an EC key.");
            }
            if (!jwt.verify(verifierFor(ecKey))) {
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
            AuthenticatedPrincipal principal = principalOf(claims);
            if (principal.sessionId() != null && revocationList.isRevoked(principal.sessionId())) {
                throw new AuthenticationFailedException("Session has been revoked.");
            }
            return principal;
        } catch (ParseException e) {
            throw new AuthenticationFailedException("Token is malformed.", e);
        } catch (JOSEException e) {
            throw new AuthenticationFailedException("Token could not be verified.", e);
        }
    }

    /** Test seam: the JWS verifier for the key. */
    JWSVerifier verifierFor(ECKey key) throws JOSEException {
        return new ECDSAVerifier(key);
    }

    private static AuthenticatedPrincipal principalOf(JWTClaimsSet claims) throws ParseException {
        String subject = claims.getSubject();
        String tenant = claims.getStringClaim("tid");
        String role = claims.getStringClaim("role");
        if (subject == null || tenant == null || role == null) {
            throw new AuthenticationFailedException("Token is missing mandatory claims.");
        }
        try {
            return new AuthenticatedPrincipal(subject, tenant, Role.valueOf(role), claims.getStringClaim("sid"));
        } catch (IllegalArgumentException e) {
            throw new AuthenticationFailedException("Token carries an unknown role.", e);
        }
    }
}
