package com.thinklab.kit.security;

import io.micronaut.http.HttpMethod;

/**
 * Platform roles. Authorisation is deliberately coarse and method based (RBAC scopes, USR-03): a VIEWER can only
 * read, an OPERATOR can read and write but not delete, an ADMIN can do everything for its tenant, and SERVICE
 * identifies another ThinkLab service acting on behalf of any tenant. REQUESTER (Journey 6) gets the same method
 * envelope as OPERATOR - resource-level scoping (a requester only sees/acts on their own tickets, never internal
 * notes) is deliberately not this enum's job: it is enforced by whichever service owns that resource, comparing
 * the token-derived {@code X-Executor} against the resource's own requester id, the same "derived from the token,
 * never trusted from the client" posture {@link SecurityFilter} already applies platform-wide.
 */
public enum Role {
    VIEWER, OPERATOR, ADMIN, SERVICE, REQUESTER;

    /** Whether the role may invoke the given HTTP method. */
    public boolean allows(HttpMethod method) {
        return switch (this) {
            case VIEWER -> method == HttpMethod.GET || method == HttpMethod.HEAD || method == HttpMethod.OPTIONS;
            case OPERATOR, REQUESTER -> method != HttpMethod.DELETE;
            case ADMIN, SERVICE -> true;
        };
    }
}
