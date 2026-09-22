package com.thinklab.kit.security;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sessions whose access tokens must no longer be honoured. An entry only needs to live until the latest access token
 * of that session could have expired, so the list stays small.
 */
@Singleton
public class RevocationList {

    private final Map<String, Instant> revoked = new ConcurrentHashMap<>();
    private final Clock clock;

    @Inject
    public RevocationList() {
        this(Clock.systemUTC());
    }

    public RevocationList(Clock clock) {
        this.clock = clock;
    }

    /** Marks a session revoked until the given instant (the expiry of its last access token). */
    public void revoke(String sessionId, Instant until) {
        revoked.put(sessionId, until);
    }

    /** Whether the session is revoked right now; expired entries are dropped lazily. */
    public boolean isRevoked(String sessionId) {
        Instant until = revoked.get(sessionId);
        if (until == null) {
            return false;
        }
        if (until.isAfter(clock.instant())) {
            return true;
        }
        revoked.remove(sessionId, until);
        return false;
    }

    /** Replaces the whole list with the issuer's current view (used by the poller). */
    public void replaceAll(Map<String, Instant> current) {
        revoked.keySet().retainAll(current.keySet());
        revoked.putAll(current);
    }
}
