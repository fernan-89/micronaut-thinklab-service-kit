package com.thinklab.kit.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.ParseException;
import java.util.Optional;

/**
 * The issuer's own EC P-256 signing key. It is read from {@code thinklab.security.private-key} (a JWK) or, when
 * that is absent, generated once in memory (development only: tokens then die with the process). Services that never
 * sign never touch this class, so they never hold a private key.
 */
@Singleton
public class LocalKeyStore {

    private static final Logger log = LoggerFactory.getLogger(LocalKeyStore.class);

    private final SecurityProperties properties;
    private volatile ECKey signingKey;

    @Inject
    public LocalKeyStore(SecurityProperties properties) {
        this.properties = properties;
    }

    /** Returns the signing key, creating it on first use. */
    public synchronized ECKey signingKey() {
        if (signingKey == null) {
            signingKey = load();
        }
        return signingKey;
    }

    /** The public half as a JWK Set, or empty when this service has never needed a signing key. */
    public Optional<JWKSet> publicSet() {
        ECKey key = signingKey;
        return key == null ? Optional.empty() : Optional.of(new JWKSet(key.toPublicJWK()));
    }

    /** Test seam: creates the ephemeral key. */
    ECKey generateKey() throws JOSEException {
        return new ECKeyGenerator(Curve.P_256).keyUse(KeyUse.SIGNATURE).keyIDFromThumbprint(true).generate();
    }

    private ECKey load() {
        String configured = properties.getPrivateKey();
        try {
            if (configured != null && !configured.isBlank()) {
                JWK jwk = JWK.parse(configured);
                if (!(jwk instanceof ECKey ec) || !ec.isPrivate() || !Curve.P_256.equals(ec.getCurve())) {
                    throw new IllegalStateException("thinklab.security.private-key must be an EC P-256 private JWK.");
                }
                return ec;
            }
            log.warn("[SECURITY] No thinklab.security.private-key configured: generating an EPHEMERAL signing key. Development only.");
            return generateKey();
        } catch (ParseException e) {
            throw new IllegalStateException("thinklab.security.private-key is not a valid JWK.", e);
        } catch (JOSEException e) {
            throw new IllegalStateException("Unable to generate a signing key.", e);
        }
    }
}
