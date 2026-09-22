package com.thinklab.kit.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Instant;
import java.util.Date;

/** Test helpers: key generation and hand-crafted tokens. */
final class TestKeys {

    static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");

    private TestKeys() {
    }

    static ECKey newKey(String keyId) {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID(keyId).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static SecurityProperties propertiesWith(ECKey privateKey) {
        SecurityProperties properties = new SecurityProperties();
        properties.setPrivateKey(privateKey.toJSONString());
        return properties;
    }

    /** Claims that are valid at {@link #NOW}; tests remove or override individual claims. */
    static JWTClaimsSet.Builder validClaims() {
        return new JWTClaimsSet.Builder()
                .subject("user-1")
                .issuer("thinklab")
                .expirationTime(Date.from(NOW.plusSeconds(300)))
                .claim("tid", "tenant-1")
                .claim("role", "ADMIN");
    }

    static String sign(ECKey key, String headerKeyId, JWTClaimsSet claims) {
        try {
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(headerKeyId).build();
            SignedJWT jwt = new SignedJWT(header, claims);
            jwt.sign(new ECDSASigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
