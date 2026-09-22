package com.thinklab.kit.security;

import com.nimbusds.jose.util.JSONObjectUtils;
import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.text.ParseException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the local {@link RevocationList} in step with the issuer. The issuer answers
 * {@code {"revoked":[{"sid":"...","until":<epoch seconds>}]}}. A failed poll keeps the previous list, so a brief
 * issuer outage never un-revokes anything; the price is that a revocation reaches a service within one poll interval.
 */
@Singleton
@Requires(property = "thinklab.security.revocation-url")
public class RevocationPoller {

    private static final Logger log = LoggerFactory.getLogger(RevocationPoller.class);

    private final SecurityProperties properties;
    private final RevocationList revocationList;
    private final HttpTransport transport;

    @Inject
    public RevocationPoller(SecurityProperties properties, RevocationList revocationList) {
        this(properties, revocationList, new HttpTransport.Default());
    }

    RevocationPoller(SecurityProperties properties, RevocationList revocationList, HttpTransport transport) {
        this.properties = properties;
        this.revocationList = revocationList;
        this.transport = transport;
    }

    @Scheduled(fixedDelay = "${thinklab.security.revocation-poll:15s}", initialDelay = "2s")
    public void poll() {
        try {
            Map<String, Object> body = JSONObjectUtils.parse(transport.get(properties.getRevocationUrl()));
            Map<String, Instant> current = new HashMap<>();
            for (Object entry : (List<?>) body.getOrDefault("revoked", List.of())) {
                Map<?, ?> item = (Map<?, ?>) entry;
                current.put((String) item.get("sid"), Instant.ofEpochSecond(((Number) item.get("until")).longValue()));
            }
            revocationList.replaceAll(current);
        } catch (IOException | ParseException | RuntimeException e) {
            log.warn("[SECURITY] Revocation poll failed, keeping the previous list: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
