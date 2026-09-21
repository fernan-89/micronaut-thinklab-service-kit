package com.thinklab.kit.security;

import io.micronaut.http.HttpMethod;

/**
 * Platform roles. Authorisation is deliberately coarse and method based (RBAC scopes, USR-03): a VIEWER can only
 * read, an OPERATOR can read and write but not delete, an ADMIN can do everything for its tenant, and SERVICE
 * identifies another ThinkLab service acting on behalf of any tenant.
 */
public enum Role {
    VIEWER, OPERATOR, ADMIN, SERVICE;

    /** Whether the role may invoke the given HTTP method. */
    public boolean allows(HttpMethod method) {
        return switch (this) {
            case VIEWER -> method == HttpMethod.GET || method == HttpMethod.HEAD || method == HttpMethod.OPTIONS;
            case OPERATOR -> method != HttpMethod.DELETE;
            case ADMIN, SERVICE -> true;
        };
    }
}
